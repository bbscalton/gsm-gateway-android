package org.neuereatec.gsmgateway

import android.content.Context
import java.net.URLDecoder

/** All settings, stored in plain SharedPreferences (device-local). */
data class Settings(
    val server: String = DEFAULT_SERVER,
    val port: Int = 5060,
    val ext: String = "",
    val password: String = "",
    val target: String = "105",
    val autoAnswer: Boolean = true,
    val answerDelayMs: Int = 1500,
    val forceAcoustic: Boolean = false,
) {
    val usable get() = server.isNotBlank() && ext.isNotBlank()

    companion object {
        /** Works today over Headscale. */
        const val DEFAULT_SERVER = "100.64.0.1"
        /** Public name; not reachable yet (no DNS-only record / port forward). */
        const val PUBLIC_SERVER = "neuereatec.org"

        fun load(c: Context): Settings {
            val p = c.getSharedPreferences("gw", Context.MODE_PRIVATE)
            return Settings(
                server = p.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER,
                port = p.getInt("port", 5060),
                ext = p.getString("ext", "") ?: "",
                password = p.getString("pw", "") ?: "",
                target = p.getString("target", "105") ?: "105",
                autoAnswer = p.getBoolean("auto", true),
                answerDelayMs = p.getInt("delay", 1500),
                forceAcoustic = p.getBoolean("acoustic", false),
            )
        }

        fun save(c: Context, s: Settings) {
            c.getSharedPreferences("gw", Context.MODE_PRIVATE).edit()
                .putString("server", s.server.trim()).putInt("port", s.port)
                .putString("ext", s.ext.trim()).putString("pw", s.password)
                .putString("target", s.target.trim()).putBoolean("auto", s.autoAnswer)
                .putInt("delay", s.answerDelayMs).putBoolean("acoustic", s.forceAcoustic)
                .apply()
        }

        /**
         * Parses gsmgw://setup?... or sipdesk://setup?server=&port=&ext=&pw=&target=
         * (server may carry ":port"). Returns null when unusable.
         */
        fun fromLink(raw: String, base: Settings): Settings? {
            val q = raw.trim().substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return null
            val m = q.split('&').filter { it.contains('=') }.associate {
                dec(it.substringBefore('=')).lowercase() to dec(it.substringAfter('='))
            }
            fun pick(vararg k: String) = k.firstNotNullOfOrNull { m[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }
            var server = pick("server", "host", "s") ?: base.server
            var port = pick("port")?.toIntOrNull()
            if (server.count { it == ':' } == 1) {
                port = port ?: server.substringAfter(':').toIntOrNull()
                server = server.substringBefore(':')
            }
            val s = base.copy(
                server = server,
                port = port ?: base.port,
                ext = pick("ext", "extension", "user", "u") ?: base.ext,
                password = pick("pw", "password", "p") ?: base.password,
                target = pick("target", "to", "dest") ?: base.target,
            )
            return s.takeIf { it.usable }
        }

        private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }
}

enum class AudioMode(val label: String, val detail: String) {
    INTERCEPTION("Rooted: call audio interception", "Full quality both ways (priv-app, Android 13+)"),
    VOICE_CALL("Rooted: VOICE_CALL capture", "Caller audio captured cleanly; PBX audio goes out by speakerphone"),
    ACOUSTIC("Speakerphone (lower quality)", "Normal phone: audio crosses by loudspeaker and microphone"),
}

/** Live status shared between service and UI (UI polls it). */
object GatewayState {
    @Volatile var sip: String = "Not started"
    @Volatile var registered = false
    @Volatile var gateway: String = "Idle"
    @Volatile var audioMode: AudioMode = AudioMode.ACOUSTIC
    @Volatile var lastCaller: String = "-"
    @Volatile var lastCallAt: String = ""
    @Volatile var level: Int = 0
    private val logLines = ArrayDeque<String>()

    fun log(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(logLines) {
            logLines.addLast("$t $s")
            while (logLines.size > 60) logLines.removeFirst()
        }
        android.util.Log.i("GsmGateway", s)
    }

    fun logText(): String = synchronized(logLines) { logLines.reversed().joinToString("\n") }
}
