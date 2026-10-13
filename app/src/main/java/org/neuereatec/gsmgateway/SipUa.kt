package org.neuereatec.gsmgateway

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Minimal SIP user agent over UDP: REGISTER (digest auth), one outgoing INVITE with
 * G.711 + RFC 2833, CANCEL/BYE, and replies to OPTIONS/BYE/NOTIFY. Written for the
 * gateway so the audio path can be fed from any AudioRecord/AudioTrack (Linphone, used
 * by SIPDesk, owns the audio device itself and cannot take call-audio sources).
 */
class SipUa(private val s: Settings, private val l: Listener) {

    interface Listener {
        fun onRegistration(ok: Boolean, info: String)
        fun onAnswered(remoteIp: String, remotePort: Int, payloadType: Int, dtmfPt: Int)
        fun onEnded(reason: String)
    }

    private class Msg(val first: String, val headers: List<Pair<String, String>>, val body: String) {
        val isResponse get() = first.startsWith("SIP/2.0")
        val code get() = if (isResponse) first.split(" ").getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val method get() = if (isResponse) cseq.substringAfter(' ').trim() else first.substringBefore(' ')
        fun h(name: String) = headers.firstOrNull { it.first == name }?.second
        fun all(name: String) = headers.filter { it.first == name }.map { it.second }
        val cseq get() = h("cseq") ?: ""
        val cseqNum get() = cseq.substringBefore(' ').trim().toIntOrNull() ?: 0
        val callId get() = h("call-id") ?: ""
    }

    private class Call(
        val callId: String, val fromTag: String, val caller: String, val rtpPort: Int,
        var cseq: Int = 1, var branch: String = "", var toTag: String? = null,
        var remoteTarget: String? = null, var provisional: Boolean = false,
        var established: Boolean = false, var authTries: Int = 0, var lastInvite: String = "",
        var tries: Int = 0,
    )

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private lateinit var sock: DatagramSocket
    private var dest: InetSocketAddress? = null
    var localIp = ""; private set
    private var localPort = 0
    @Volatile private var running = false

    private val regCallId = rnd() + "@gsmgw"
    private val regTag = rnd()
    private var regCseq = 0
    private var regAuthTries = 0
    private var regTimer: ScheduledFuture<*>? = null
    private var call: Call? = null

    private val domain get() = s.server
    private val aor get() = "sip:${s.ext}@$domain"

    fun start() {
        running = true
        exec.execute {
            try {
                sock = DatagramSocket()
                localPort = sock.localPort
                Thread(::recvLoop, "sip-rx").apply { isDaemon = true }.start()
                exec.scheduleWithFixedDelay({ keepAlive() }, 25, 25, TimeUnit.SECONDS)
                register()
            } catch (e: Exception) {
                l.onRegistration(false, "Socket error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        exec.execute {
            try { hangupInternal() } catch (_: Exception) {}
            try { if (regCseq > 0) sendRegister(0, null) } catch (_: Exception) {}
            exec.schedule({ try { sock.close() } catch (_: Exception) {}; exec.shutdownNow() }, 300, TimeUnit.MILLISECONDS)
        }
    }

    /** Resolve the server (DNS may change) and work out which local address reaches it. */
    private fun resolve(): Boolean {
        return try {
            val addr = InetAddress.getByName(s.server)
            dest = InetSocketAddress(addr, s.port)
            DatagramSocket().use { probe ->
                probe.connect(addr, s.port)
                localIp = probe.localAddress.hostAddress ?: ""
            }
            true
        } catch (e: Exception) {
            l.onRegistration(false, "Cannot reach ${s.server}: ${e.message}")
            false
        }
    }

    private fun keepAlive() {
        if (!running) return
        try { send("\r\n\r\n") } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- REGISTER

    private fun register() {
        if (!running) return
        if (!resolve()) { retryRegister(30); return }
        regAuthTries = 0
        sendRegister(300, null)
    }

    private fun sendRegister(expires: Int, auth: String?) {
        regCseq++
        val sent = regCseq
        val m = buildRequest("REGISTER", "sip:$domain", regCallId, regCseq, regTag, aor, null,
            listOfNotNull("Expires: $expires", auth), "", null, null)
        send(m)
        if (expires > 0) exec.schedule({
            if (running && regCseq == sent && !GatewayState.registered) {
                l.onRegistration(false, "No reply from ${s.server}:${s.port}")
                retryRegister(20)
            }
        }, 6, TimeUnit.SECONDS)
    }

    private fun retryRegister(sec: Long) {
        regTimer?.cancel(false)
        regTimer = exec.schedule({ register() }, sec, TimeUnit.SECONDS)
    }

    private fun onRegisterResponse(m: Msg) {
        if (m.cseqNum != regCseq) return
        when (m.code) {
            in 100..199 -> {}
            200 -> {
                val exp = Regex("expires=(\\d+)").find(m.h("contact") ?: "")?.groupValues?.get(1)?.toIntOrNull()
                    ?: m.h("expires")?.trim()?.toIntOrNull() ?: 300
                if (!running) return
                l.onRegistration(true, "Registered as ${s.ext}@${s.server}")
                retryRegister(maxOf(30L, (exp * 0.8).toLong()))
            }
            401, 407 -> {
                if (regAuthTries++ >= 2) {
                    l.onRegistration(false, "Wrong extension or password (${m.code})"); retryRegister(60); return
                }
                sendRegister(300, authHeader(m, "REGISTER", "sip:$domain"))
            }
            else -> {
                l.onRegistration(false, "Server refused: ${m.first.removePrefix("SIP/2.0 ")}")
                retryRegister(60)
            }
        }
    }

    // ---------------------------------------------------------------- INVITE

    /** Place the bridge call. [rtpPort] is the local RTP socket already opened by the caller. */
    fun invite(callerId: String, rtpPort: Int) = exec.execute {
        if (call != null) return@execute
        if (dest == null && !resolve()) { l.onEnded("Server unreachable"); return@execute }
        val c = Call(rnd() + "@gsmgw", rnd(), callerId, rtpPort)
        call = c
        sendInvite(c, null)
    }

    private fun targetUri() = "sip:${s.target}@$domain"

    private fun sendInvite(c: Call, auth: String?) {
        c.branch = "z9hG4bK" + rnd()
        val sdp = sdp(c.rtpPort)
        val display = c.caller.replace("\"", "")
        val extra = mutableListOf<String>()
        if (auth != null) extra += auth
        if (c.caller.isNotBlank()) {
            extra += "P-Asserted-Identity: \"$display\" <sip:${c.caller}@$domain>"
            extra += "X-GSM-Caller: ${c.caller}"
        }
        extra += "Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, INFO, NOTIFY"
        val m = buildRequest("INVITE", targetUri(), c.callId, c.cseq, c.fromTag, targetUri(), null,
            extra, sdp, "application/sdp", c.branch, fromDisplay = display)
        c.lastInvite = m
        c.tries = 0
        send(m)
        retransmitInvite(c, c.cseq, 500)
    }

    private fun retransmitInvite(c: Call, cseq: Int, delay: Long) {
        exec.schedule({
            if (call !== c || c.cseq != cseq || c.provisional || c.established) return@schedule
            if (++c.tries > 6) { call = null; l.onEnded("No answer from PBX"); return@schedule }
            send(c.lastInvite)
            retransmitInvite(c, cseq, minOf(delay * 2, 4000))
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun onInviteResponse(m: Msg) {
        val c = call ?: return
        if (m.callId != c.callId) return
        val code = m.code
        when {
            code < 200 -> c.provisional = true
            code < 300 -> {
                c.toTag = tagOf(m.h("to"))
                c.remoteTarget = Regex("<([^>]+)>").find(m.h("contact") ?: "")?.groupValues?.get(1) ?: targetUri()
                sendAck2xx(c)
                if (c.established) return
                c.established = true
                val (ip, port, pt, dtmf) = parseSdp(m.body)
                if (ip == null || port == 0) { hangupInternal(); l.onEnded("PBX sent no audio address"); return }
                l.onAnswered(ip, port, pt, dtmf)
            }
            (code == 401 || code == 407) && c.authTries < 2 -> {
                sendAckNon2xx(c, m)
                c.authTries++
                c.cseq++
                sendInvite(c, authHeader(m, "INVITE", targetUri()))
            }
            else -> {
                sendAckNon2xx(c, m)
                call = null
                l.onEnded("PBX: ${m.first.removePrefix("SIP/2.0 ")}")
            }
        }
    }

    private fun sendAck2xx(c: Call) {
        val uri = c.remoteTarget ?: targetUri()
        send(buildRequest("ACK", uri, c.callId, c.cseq, c.fromTag, targetUri(), c.toTag, emptyList(), "", null, null))
    }

    private fun sendAckNon2xx(c: Call, m: Msg) {
        send(buildRequest("ACK", targetUri(), c.callId, c.cseq, c.fromTag, targetUri(), tagOf(m.h("to")),
            emptyList(), "", null, c.branch))
    }

    fun hangup() = exec.execute { hangupInternal() }

    private fun hangupInternal() {
        val c = call ?: return
        call = null
        if (c.established) {
            c.cseq++
            send(buildRequest("BYE", c.remoteTarget ?: targetUri(), c.callId, c.cseq, c.fromTag, targetUri(),
                c.toTag, emptyList(), "", null, null))
        } else {
            send(buildRequest("CANCEL", targetUri(), c.callId, c.cseq, c.fromTag, targetUri(), null,
                emptyList(), "", null, c.branch))
        }
    }

    // ---------------------------------------------------------------- incoming

    private fun recvLoop() {
        val buf = ByteArray(8192)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val text = String(p.data, 0, p.length, Charsets.UTF_8)
                if (text.isBlank()) continue
                val m = parse(text) ?: continue
                exec.execute { handle(m) }
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun handle(m: Msg) {
        if (m.isResponse) {
            when (m.method) {
                "REGISTER" -> onRegisterResponse(m)
                "INVITE" -> onInviteResponse(m)
            }
            return
        }
        when (m.method) {
            "ACK" -> {}
            "BYE" -> {
                reply(m, 200, "OK")
                val c = call
                if (c != null && c.callId == m.callId) { call = null; l.onEnded("PBX hung up") }
            }
            "INVITE" -> {
                val c = call
                if (c != null && c.callId == m.callId) reply(m, 200, "OK", sdp(c.rtpPort)) // re-INVITE: keep media
                else reply(m, 486, "Busy Here")
            }
            "CANCEL", "OPTIONS", "NOTIFY", "INFO", "UPDATE" -> reply(m, 200, "OK")
            else -> reply(m, 501, "Not Implemented")
        }
    }

    private fun reply(req: Msg, code: Int, reason: String, sdpBody: String = "") {
        val sb = StringBuilder("SIP/2.0 $code $reason\r\n")
        req.all("via").forEach { sb.append("Via: ").append(it).append("\r\n") }
        sb.append("From: ").append(req.h("from")).append("\r\n")
        val to = req.h("to") ?: ""
        sb.append("To: ").append(if (to.contains("tag=")) to else "$to;tag=${rnd()}").append("\r\n")
        sb.append("Call-ID: ").append(req.callId).append("\r\n")
        sb.append("CSeq: ").append(req.cseq).append("\r\n")
        sb.append("Contact: <sip:${s.ext}@$localIp:$localPort;transport=udp>\r\n")
        sb.append("User-Agent: $UA\r\n")
        if (sdpBody.isNotEmpty()) sb.append("Content-Type: application/sdp\r\n")
        sb.append("Content-Length: ${sdpBody.toByteArray().size}\r\n\r\n").append(sdpBody)
        send(sb.toString())
    }

    // ---------------------------------------------------------------- helpers

    private fun buildRequest(
        method: String, uri: String, callId: String, cseq: Int, fromTag: String, toUri: String,
        toTag: String?, extra: List<String>, body: String, contentType: String?, branch: String?,
        fromDisplay: String = "",
    ): String {
        val sb = StringBuilder("$method $uri SIP/2.0\r\n")
        sb.append("Via: SIP/2.0/UDP $localIp:$localPort;branch=${branch ?: "z9hG4bK" + rnd()};rport\r\n")
        sb.append("Max-Forwards: 70\r\n")
        val disp = if (fromDisplay.isNotBlank()) "\"$fromDisplay\" " else ""
        sb.append("From: $disp<$aor>;tag=$fromTag\r\n")
        sb.append("To: <$toUri>").append(if (toTag != null) ";tag=$toTag" else "").append("\r\n")
        sb.append("Call-ID: $callId\r\n")
        sb.append("CSeq: $cseq $method\r\n")
        sb.append("Contact: <sip:${s.ext}@$localIp:$localPort;transport=udp>\r\n")
        sb.append("User-Agent: $UA\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.toByteArray().size}\r\n\r\n").append(body)
        return sb.toString()
    }

    private fun sdp(port: Int) = buildString {
        append("v=0\r\no=gsmgw ${System.currentTimeMillis() / 1000} 1 IN IP4 $localIp\r\ns=GSM Gateway\r\n")
        append("c=IN IP4 $localIp\r\nt=0 0\r\nm=audio $port RTP/AVP 0 8 101\r\n")
        append("a=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=rtpmap:101 telephone-event/8000\r\n")
        append("a=fmtp:101 0-16\r\na=ptime:20\r\na=sendrecv\r\n")
    }

    private data class SdpInfo(val ip: String?, val port: Int, val pt: Int, val dtmf: Int)

    private fun parseSdp(body: String): SdpInfo {
        var ip: String? = null; var port = 0; var pt = 0; var dtmf = -1
        var inAudio = false
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("m=") -> {
                    inAudio = line.startsWith("m=audio")
                    if (inAudio) {
                        val parts = line.split(" ")
                        port = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val pts = parts.drop(3).mapNotNull { it.toIntOrNull() }
                        pt = pts.firstOrNull { it == 0 || it == 8 } ?: 0
                    }
                }
                line.startsWith("c=") && (ip == null || inAudio) -> ip = line.split(" ").lastOrNull()
                inAudio && line.startsWith("a=rtpmap:") && line.contains("telephone-event", true) ->
                    dtmf = line.substringAfter(':').substringBefore(' ').toIntOrNull() ?: -1
            }
        }
        return SdpInfo(ip, port, pt, dtmf)
    }

    private fun authHeader(m: Msg, method: String, uri: String): String {
        val proxy = m.code == 407
        val ch = (if (proxy) m.h("proxy-authenticate") else m.h("www-authenticate")) ?: ""
        fun p(k: String) = Regex("$k=\"?([^\",]+)\"?", RegexOption.IGNORE_CASE).find(ch)?.groupValues?.get(1)
        val realm = p("realm") ?: domain
        val nonce = p("nonce") ?: ""
        val opaque = p("opaque")
        val qop = p("qop")?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("${s.ext}:$realm:${s.password}")
        val ha2 = md5("$method:$uri")
        val sb = StringBuilder(if (proxy) "Proxy-Authorization: " else "Authorization: ")
        sb.append("Digest username=\"${s.ext}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", algorithm=MD5")
        if (qop != null) {
            val cnonce = rnd()
            val resp = md5("$ha1:$nonce:00000001:$cnonce:$qop:$ha2")
            sb.append(", response=\"$resp\", qop=$qop, nc=00000001, cnonce=\"$cnonce\"")
        } else {
            sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
        }
        if (opaque != null) sb.append(", opaque=\"$opaque\"")
        return sb.toString()
    }

    private fun send(text: String) {
        val d = dest ?: return
        val b = text.toByteArray(Charsets.UTF_8)
        try { sock.send(DatagramPacket(b, b.size, d)) } catch (_: Exception) {}
    }

    companion object {
        const val UA = "NeuereatecGSMGateway/1.0"
        private val COMPACT = mapOf("i" to "call-id", "v" to "via", "f" to "from", "t" to "to", "m" to "contact",
            "l" to "content-length", "c" to "content-type")

        private fun parse(text: String): Msg? {
            val idx = text.indexOf("\r\n\r\n").let { if (it < 0) text.length else it }
            val head = text.substring(0, idx)
            val body = if (idx + 4 <= text.length) text.substring(idx + 4) else ""
            val lines = head.split("\r\n")
            if (lines.isEmpty() || !lines[0].contains("SIP/2.0")) return null
            val headers = ArrayList<Pair<String, String>>()
            for (line in lines.drop(1)) {
                val c = line.indexOf(':'); if (c <= 0) continue
                var name = line.substring(0, c).trim().lowercase()
                name = COMPACT[name] ?: name
                val value = line.substring(c + 1).trim()
                if (name == "via") value.split(Regex(",(?=\\s*SIP/)")).forEach { headers += name to it.trim() }
                else headers += name to value
            }
            return Msg(lines[0], headers, body)
        }

        private fun tagOf(h: String?) = h?.let { Regex(";tag=([^;>\\s]+)").find(it)?.groupValues?.get(1) }
        private fun rnd() = UUID.randomUUID().toString().replace("-", "").take(16)
        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
