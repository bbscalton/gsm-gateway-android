package org.neuereatec.gsmgateway

import android.content.Context
import java.net.URLDecoder

/**
 * All settings, stored in plain SharedPreferences (device-local, never in the APK).
 *
 * Forwarding:
 *  - [targets] is an ordered hunt list ("100,105,1001"). The app rings each in turn for
 *    [ringSeconds], hopping on no-answer, busy or any failure.
 *  - Blank list (or "s"/"route") = route mode: the app always INVITEs [route]@server and
 *    CPBX decides (ring group / follow-me / voicemail). Recommended once CPBX has the route.
 */
data class Settings(
    val server: String = DEFAULT_SERVER,
    val port: Int = 5060,
    val ext: String = "",
    val password: String = "",
    val targets: List<String> = emptyList(),
    val ringSeconds: Int = 20,
    val route: String = DEFAULT_ROUTE,
    val autoAnswer: Boolean = true,
    val answerDelayMs: Int = 1500,
    val forceAcoustic: Boolean = false,
    val hsUrl: String = "",
    val hsKey: String = "",
) {
    val usable get() = server.isNotBlank() && ext.isNotBlank()
    val tunnelWanted get() = hsUrl.isNotBlank()
    val routeMode get() = targets.isEmpty()
    /** What the bridge dials, in order. */
    val dialPlan: List<String> get() = if (routeMode) listOf(route.ifBlank { DEFAULT_ROUTE }) else targets
    val targetLabel get() = if (routeMode) "CPBX route '${route}'" else targets.joinToString(" \u2192 ")

    companion object {
        const val DEFAULT_SERVER = "100.64.0.1"
        const val PUBLIC_SERVER = "neuereatec.org"
        const val DEFAULT_ROUTE = "gsmgw"

        /** Parses "100, 105 1001" into ["100","105","1001"]; null if any item is not digits. "s"/"route" = route mode. */
        fun parseTargets(raw: String): List<String>? {
            val items = raw.split(',', ';', ' ', '\n', '>').map { it.trim() }.filter { it.isNotEmpty() }
            if (items.size == 1 && items[0].lowercase() in setOf("s", "route")) return emptyList()
            if (items.any { !it.all(Char::isDigit) || it.length > 15 }) return null
            return items.distinct()
        }

        fun load(c: Context): Settings {
            val p = c.getSharedPreferences("gw", Context.MODE_PRIVATE)
            // 1.0.0 stored a single "target" (default 105); keep it as the first hop.
            val raw = p.getString("targets", null) ?: p.getString("target", "") ?: ""
            return Settings(
                server = p.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER,
                port = p.getInt("port", 5060),
                ext = p.getString("ext", "") ?: "",
                password = p.getString("pw", "") ?: "",
                targets = parseTargets(raw) ?: emptyList(),
                ringSeconds = p.getInt("ring", 20),
                route = p.getString("route", DEFAULT_ROUTE) ?: DEFAULT_ROUTE,
                autoAnswer = p.getBoolean("auto", true),
                answerDelayMs = p.getInt("delay", 1500),
                forceAcoustic = p.getBoolean("acoustic", false),
                hsUrl = p.getString("hs", "") ?: "",
                hsKey = p.getString("hskey", "") ?: "",
            )
        }

        /** True once the user answered the "which extension should ring?" question (or a QR set it). */
        fun targetAsked(c: Context) = c.getSharedPreferences("gw", Context.MODE_PRIVATE).contains("targets")

        fun save(c: Context, s: Settings) {
            c.getSharedPreferences("gw", Context.MODE_PRIVATE).edit()
                .putString("server", s.server.trim()).putInt("port", s.port)
                .putString("ext", s.ext.trim()).putString("pw", s.password)
                .putString("targets", s.targets.joinToString(",")).remove("target")
                .putInt("ring", s.ringSeconds.coerceIn(5, 120)).putString("route", s.route.trim())
                .putBoolean("auto", s.autoAnswer)
                .putInt("delay", s.answerDelayMs).putBoolean("acoustic", s.forceAcoustic)
                .putString("hs", s.hsUrl.trim()).putString("hskey", s.hsKey.trim())
                .apply()
        }

        sealed class LinkResult {
            data class Ok(val settings: Settings, val targetGiven: Boolean) : LinkResult()
            object Softphone : LinkResult()
            object Bad : LinkResult()
        }

        /**
         * Only gsmgw://setup?server=&port=&ext=&pw=&hs=&hskey=&target=&ring=&route= configures the gateway.
         * sipdesk:// codes are SIPDesk softphone codes and are refused (they would make this
         * phone register as somebody's desk extension).
         */
        fun fromLink(raw: String, base: Settings): LinkResult {
            val t = raw.trim()
            if (t.startsWith("sipdesk:", true)) return LinkResult.Softphone
            if (!t.startsWith("gsmgw://setup", true)) return LinkResult.Bad
            val q = t.substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return LinkResult.Bad
            val m = q.split('&').filter { it.contains('=') }.associate {
                dec(it.substringBefore('=')).lowercase() to dec(it.substringAfter('='))
            }
            fun pick(vararg k: String) = k.firstNotNullOfOrNull { m[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }
            var server = pick("server", "host") ?: base.server
            var port = pick("port")?.toIntOrNull()
            if (server.count { it == ':' } == 1) {
                port = port ?: server.substringAfter(':').toIntOrNull()
                server = server.substringBefore(':')
            }
            val tg = pick("target", "targets")
            val s = base.copy(
                server = server, port = port ?: base.port,
                ext = pick("ext", "user") ?: base.ext,
                password = pick("pw", "password") ?: base.password,
                targets = tg?.let { parseTargets(it) } ?: base.targets,
                ringSeconds = pick("ring")?.toIntOrNull()?.coerceIn(5, 120) ?: base.ringSeconds,
                route = pick("route") ?: base.route,
                hsUrl = pick("hs") ?: base.hsUrl,
                hsKey = pick("hskey") ?: base.hsKey,
            )
            return if (s.usable) LinkResult.Ok(s, tg != null) else LinkResult.Bad
        }

        private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }
}

enum class AudioMode(val label: String, val detail: String) {
    INTERCEPTION("Rooted: call audio interception", "Full quality both ways (priv-app, Android 13+)"),
    VOICE_CALL("Rooted: VOICE_CALL capture", "Caller audio captured cleanly; PBX audio goes out by speakerphone"),
    ACOUSTIC("Speakerphone (lower quality)", "Normal phone: audio crosses by loudspeaker and microphone"),
}

/** Live status shared between services and UI (UI polls it). */
object GatewayState {
    @Volatile var sip: String = "Not started"
    @Volatile var registered = false
    @Volatile var gateway: String = "Idle"
    @Volatile var audioMode: AudioMode = AudioMode.ACOUSTIC
    @Volatile var lastCaller: String = "-"
    @Volatile var lastCallAt: String = ""
    @Volatile var level: Int = 0
    /** Headscale tunnel: state from the Go backend ("Running", "Starting", "NeedsLogin", ...). */
    @Volatile var tunnelState: String = ""
    @Volatile var tunnelIp: String = ""
    @Volatile var tunnelDetail: String = ""
    val tunnelReady get() = tunnelState == "Running" && tunnelIp.startsWith("100.")
    private val logLines = ArrayDeque<String>()

    private val secret = Regex("(hskey-auth-|tskey-auth-|tskey-)[A-Za-z0-9._-]+")
    fun redact(s: String) = secret.replace(s) { it.value.take(12) + "\u2026" }

    fun log(s: String) {
        val safe = redact(s)
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(logLines) {
            logLines.addLast("$t $safe")
            while (logLines.size > 80) logLines.removeFirst()
        }
        android.util.Log.i("GsmGateway", safe)
    }

    fun logText(): String = synchronized(logLines) { logLines.reversed().joinToString("\n") }
}
