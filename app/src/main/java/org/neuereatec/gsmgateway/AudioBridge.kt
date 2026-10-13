package org.neuereatec.gsmgateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Moves audio between the cellular call and the RTP leg.
 *  INTERCEPTION: AudioManager.getCallDownlinkExtractionAudioRecord / getCallUplinkInjectionAudioTrack
 *                (@SystemApi, needs CALL_AUDIO_INTERCEPTION = priv-app on Android 13+).
 *  VOICE_CALL:   AudioRecord(VOICE_DOWNLINK / VOICE_CALL) with CAPTURE_AUDIO_OUTPUT (priv-app);
 *                PBX audio is played on the loudspeaker so the phone's mic carries it into the call.
 *  ACOUSTIC:     speakerphone on; mic (VOICE_COMMUNICATION, echo-cancelled) hears the caller,
 *                loudspeaker plays the PBX side. Lower quality.
 */
class AudioBridge(private val ctx: Context, private val rtp: Rtp) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var running = false
    var mode: AudioMode = AudioMode.ACOUSTIC; private set

    companion object {
        const val RATE = 8000
        const val FRAME = 160

        private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

        /** What this install can do (before a call). */
        fun probe(c: Context, forceAcoustic: Boolean): AudioMode = when {
            forceAcoustic -> AudioMode.ACOUSTIC
            Build.VERSION.SDK_INT >= 33 && granted(c, "android.permission.CALL_AUDIO_INTERCEPTION") -> AudioMode.INTERCEPTION
            granted(c, "android.permission.CAPTURE_AUDIO_OUTPUT") -> AudioMode.VOICE_CALL
            else -> AudioMode.ACOUSTIC
        }

        private val fmtIn = AudioFormat.Builder().setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        private val fmtOut = AudioFormat.Builder().setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
    }

    /** Opens devices for the best workable mode and starts pumping. Returns the mode actually used. */
    fun start(preferred: AudioMode): AudioMode {
        var m = preferred
        if (m == AudioMode.INTERCEPTION && !openInterception()) m = AudioMode.VOICE_CALL.takeIf {
            granted(ctx, "android.permission.CAPTURE_AUDIO_OUTPUT") } ?: AudioMode.ACOUSTIC
        if (m == AudioMode.VOICE_CALL && !openVoiceCall()) m = AudioMode.ACOUSTIC
        if (m == AudioMode.ACOUSTIC) openAcoustic()
        mode = m
        running = true
        rtp.onAudio = { pcm -> if (running) track?.write(pcm, 0, pcm.size) }
        rtp.onDtmf = { d -> if (running) playDtmf(d) }
        track?.play()
        record?.startRecording()
        Thread(::captureLoop, "gsm-capture").apply { priority = Thread.MAX_PRIORITY; isDaemon = true }.start()
        GatewayState.log("Audio bridge started: ${m.label}")
        return m
    }

    private fun openInterception(): Boolean = try {
        val rec = AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
            .invoke(am, fmtIn) as AudioRecord
        val trk = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            .invoke(am, fmtOut) as AudioTrack
        if (rec.state != AudioRecord.STATE_INITIALIZED || trk.state != AudioTrack.STATE_INITIALIZED) {
            rec.release(); trk.release(); false
        } else { record = rec; track = trk; true }
    } catch (e: Throwable) {
        GatewayState.log("Call audio interception unavailable: ${e.cause?.message ?: e.message}")
        false
    }

    @SuppressLint("MissingPermission")
    private fun openVoiceCall(): Boolean {
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_DOWNLINK, MediaRecorder.AudioSource.VOICE_CALL)) {
            try {
                val rec = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn())
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    speaker(true)
                    track = speakerTrack()
                    return true
                }
                rec.release()
            } catch (e: Throwable) {
                GatewayState.log("VOICE_CALL source $src refused: ${e.message}")
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun openAcoustic() {
        speaker(true)
        if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) { GatewayState.log("Microphone permission missing"); }
        else for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            try {
                val rec = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn())
                if (rec.state == AudioRecord.STATE_INITIALIZED) { record = rec; break }
                rec.release()
            } catch (_: Throwable) {}
        }
        track = speakerTrack()
    }

    private fun minIn() = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT), FRAME * 2 * 4)

    private fun speakerTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(fmtOut)
            .setBufferSizeInBytes(maxOf(min, FRAME * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun speaker(on: Boolean) {
        try {
            if (on && Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }?.let { am.setCommunicationDevice(it) }
            }
            am.isSpeakerphoneOn = on
            if (on) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        } catch (e: Exception) {
            GatewayState.log("Speakerphone: ${e.message}")
        }
    }

    private fun captureLoop() {
        val buf = ShortArray(FRAME)
        val rec = record
        while (running) {
            if (rec == null) { // no capture possible: keep RTP alive with silence
                rtp.send(ShortArray(FRAME)); Thread.sleep(20); continue
            }
            var got = 0
            while (got < FRAME && running) {
                val n = rec.read(buf, got, FRAME - got)
                if (n <= 0) break
                got += n
            }
            if (got < FRAME) { Thread.sleep(5); continue }
            var peak = 0
            for (v in buf) peak = maxOf(peak, abs(v.toInt()))
            GatewayState.level = peak * 100 / 32768
            rtp.send(buf)
        }
    }

    /** PBX pressed a key (RFC 2833): play the DTMF pair into the cellular leg. */
    private fun playDtmf(d: Char) {
        val rows = intArrayOf(697, 770, 852, 941)
        val cols = intArrayOf(1209, 1336, 1477, 1633)
        val keys = "123A456B789C*0#D"
        val i = keys.indexOf(d).takeIf { it >= 0 } ?: return
        val f1 = rows[i / 4].toDouble(); val f2 = cols[i % 4].toDouble()
        val n = RATE / 10 // 100 ms
        val pcm = ShortArray(n) { k ->
            val t = k.toDouble() / RATE
            (8000 * (sin(2 * PI * f1 * t) + sin(2 * PI * f2 * t))).toInt().toShort()
        }
        track?.write(pcm, 0, pcm.size)
        GatewayState.log("DTMF from PBX: $d")
    }

    fun stop() {
        running = false
        rtp.onAudio = null; rtp.onDtmf = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        record = null; track = null
        if (mode != AudioMode.INTERCEPTION) speaker(false)
        GatewayState.level = 0
    }
}
