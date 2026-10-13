package org.neuereatec.gsmgateway

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.random.Random

/** G.711 RTP endpoint: 20 ms frames (160 samples at 8 kHz), RFC 2833 events in. */
class Rtp {
    val socket = DatagramSocket(0)
    val localPort get() = socket.localPort
    private var remote: InetSocketAddress? = null
    private var pt = 0
    private var dtmfPt = 101
    private var seq = Random.nextInt(0, 65535)
    private var ts = Random.nextInt()
    private val ssrc = Random.nextInt()
    @Volatile private var running = false
    private var lastEventTs = -1L

    var onAudio: ((ShortArray) -> Unit)? = null
    var onDtmf: ((Char) -> Unit)? = null

    fun start(ip: String, port: Int, payloadType: Int, telephoneEventPt: Int) {
        remote = InetSocketAddress(ip, port)
        pt = payloadType
        if (telephoneEventPt >= 0) dtmfPt = telephoneEventPt
        running = true
        Thread(::rx, "rtp-rx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    fun send(pcm: ShortArray) {
        val r = remote ?: return
        val out = ByteArray(12 + pcm.size)
        out[0] = 0x80.toByte(); out[1] = pt.toByte()
        out[2] = (seq shr 8).toByte(); out[3] = seq.toByte()
        out[4] = (ts ushr 24).toByte(); out[5] = (ts ushr 16).toByte(); out[6] = (ts ushr 8).toByte(); out[7] = ts.toByte()
        out[8] = (ssrc ushr 24).toByte(); out[9] = (ssrc ushr 16).toByte(); out[10] = (ssrc ushr 8).toByte(); out[11] = ssrc.toByte()
        for (i in pcm.indices) out[12 + i] = if (pt == 8) G711.linToAlaw(pcm[i]) else G711.linToUlaw(pcm[i])
        seq = (seq + 1) and 0xffff
        ts += pcm.size
        try { socket.send(DatagramPacket(out, out.size, r)) } catch (_: Exception) {}
    }

    private fun rx() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                if (p.length < 12) continue
                val cc = buf[0].toInt() and 0x0f
                val ext = (buf[0].toInt() and 0x10) != 0
                var off = 12 + cc * 4
                if (ext && p.length >= off + 4) off += 4 + 4 * (((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff))
                val rpt = buf[1].toInt() and 0x7f
                val len = p.length - off
                if (len <= 0) continue
                if (rpt == dtmfPt) {
                    val stamp = ((buf[4].toLong() and 0xff) shl 24) or ((buf[5].toLong() and 0xff) shl 16) or
                        ((buf[6].toLong() and 0xff) shl 8) or (buf[7].toLong() and 0xff)
                    if (stamp != lastEventTs) {
                        lastEventTs = stamp
                        val ev = buf[off].toInt() and 0xff
                        "0123456789*#ABCD".getOrNull(ev)?.let { onDtmf?.invoke(it) }
                    }
                    continue
                }
                if (rpt != 0 && rpt != 8) continue
                val pcm = ShortArray(len)
                for (i in 0 until len) pcm[i] = if (rpt == 8) G711.alawToLin(buf[off + i]) else G711.ulawToLin(buf[off + i])
                onAudio?.invoke(pcm)
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    fun close() {
        running = false
        try { socket.close() } catch (_: Exception) {}
    }
}

object G711 {
    fun linToUlaw(sample: Short): Byte {
        var pcm = sample.toInt()
        val sign = if (pcm < 0) { pcm = -pcm; 0x80 } else 0
        if (pcm > 32635) pcm = 32635
        pcm += 0x84
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (pcm and mask) == 0) { exp--; mask = mask shr 1 }
        val mant = (pcm shr (exp + 3)) and 0x0f
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    fun ulawToLin(b: Byte): Short {
        val u = b.toInt().inv() and 0xff
        val sign = u and 0x80
        val exp = (u shr 4) and 0x07
        val mant = u and 0x0f
        var s = ((mant shl 3) + 0x84) shl exp
        s -= 0x84
        return (if (sign != 0) -s else s).toShort()
    }

    private val SEG_AEND = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)
    fun linToAlaw(sample: Short): Byte {
        var pcm = sample.toInt() shr 3
        val mask: Int
        if (pcm >= 0) mask = 0xD5 else { mask = 0x55; pcm = -pcm - 1 }
        var seg = 0
        while (seg < 8 && pcm > SEG_AEND[seg]) seg++
        val aval = if (seg >= 8) 0x7F else {
            val base = seg shl 4
            if (seg < 2) base or ((pcm shr 1) and 0xF) else base or ((pcm shr seg) and 0xF)
        }
        return (aval xor mask).toByte()
    }

    fun alawToLin(b: Byte): Short {
        val a = (b.toInt() and 0xff) xor 0x55
        val sign = a and 0x80
        val exp = (a shr 4) and 0x07
        val mant = a and 0x0f
        var s = if (exp == 0) (mant shl 4) + 8 else ((mant shl 4) + 0x108) shl (exp - 1)
        return (if (sign != 0) s else -s).toShort()
    }
}
