package org.neuereatec.gsmgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import org.neuereatec.sipdesk.tunnel.tunnel.Host
import org.neuereatec.sipdesk.tunnel.tunnel.Tunnel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embedded Headscale (Tailscale) client, same Go engine (tunnel.aar) and supervision loop as
 * SIPDesk 2.1.x. Routes only the tailnet (100.64.0.0/10), so cellular/Wi-Fi traffic is untouched.
 * Foreground + START_STICKY, rebuilds with backoff 2..60 s, node key kept across rebuilds.
 * GatewayService starts SIP only after this reports Running with a 100.x address.
 */
class TunnelVpnService : VpnService() {
    private val generation = AtomicInteger(0)
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Office tunnel", NotificationManager.IMPORTANCE_LOW))
        val n = note("Starting tunnel")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
        val s = Settings.load(this)
        if (!s.tunnelWanted) { stopSelf(); return START_NOT_STICKY }
        val reset = intent?.getBooleanExtra("reset", false) == true
        if (reset || worker?.isAlive != true) {
            val gen = generation.incrementAndGet()
            worker?.interrupt()
            worker = Thread({ supervise(reset, gen) }, "gsmgw-tunnel").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        generation.incrementAndGet(); worker?.interrupt()
        set("Stopped", "", "")
        // Tunnel.shutdown() (Go engine) can block for minutes; never run it on the main thread
        // (that caused the 1.2.0 "keeps crashing" ANRs on Stop / Reconnect / settings save).
        shutdownAsync(applicationContext)
        super.onDestroy()
    }

    override fun onRevoke() {
        GatewayState.log("Tunnel: VPN permission revoked (another VPN app took over?)")
        set("Stopped", "", "VPN permission revoked")
        stopSelf()
        super.onRevoke()
    }

    private fun set(state: String, ip: String, detail: String) {
        GatewayState.tunnelState = state; GatewayState.tunnelIp = ip; GatewayState.tunnelDetail = detail
    }

    private fun supervise(reset: Boolean, gen: Int) {
        var doReset = reset
        var attempt = 0
        while (generation.get() == gen && !Thread.currentThread().isInterrupted) {
            val started = System.currentTimeMillis()
            val stop = try { runOnce(doReset, gen) } catch (t: Throwable) {
                GatewayState.log("Tunnel error: ${t.javaClass.simpleName}: ${t.message}"); false
            }
            doReset = false
            if (stop || generation.get() != gen) return
            if (System.currentTimeMillis() - started > 120_000) attempt = 0
            attempt++
            val delay = minOf(60L, 2L shl minOf(attempt - 1, 5))
            GatewayState.log("Tunnel reconnecting in ${delay}s (attempt $attempt)")
            if (!boundedShutdown()) {
                GatewayState.log("Tunnel: engine shutdown hung >30s; restarting app process")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            set("Starting", "", "reconnecting")
            try { Thread.sleep(delay * 1000) } catch (_: InterruptedException) { return }
        }
    }

    /** @return true = stop for good, false = retry. */
    private fun runOnce(reset: Boolean, gen: Int): Boolean {
        val s = Settings.load(this)
        if (!s.tunnelWanted) return true
        // Node/machine keys live in persistent app storage (files/, not cache/), so a reboot or app
        // update logs back in without spending the pre-auth key again. Only a reset or uninstall wipes it.
        val dir = File(filesDir, "tailscale").apply { mkdirs() }.absolutePath
        if (reset) synchronized(engineLock) { Tunnel.resetState(dir) }
        val cu = File(dir, "control.url")
        if (!cu.exists() || cu.readText().trim() != s.hsUrl) cu.writeText(s.hsUrl)
        set("Starting", "", "logging in to ${s.hsUrl}")
        GatewayState.log("Tunnel: joining ${s.hsUrl} key=${s.hsKey.take(12)}\u2026")
        try {
            synchronized(engineLock) {
                if (generation.get() != gen) return true
                Tunnel.start(dir, host)
            }
            Tunnel.login(s.hsUrl, s.hsKey, nodeName(dir))
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            GatewayState.log("Tunnel login failed: ${GatewayState.redact(msg)}")
            set("NeedsLogin", "", if (msg.contains("already used", true)) KEY_USED else msg)
            return false
        }
        var lastShown = ""
        var notReadySince = System.currentTimeMillis()
        var nextProbe = System.currentTimeMillis() + PROBE_EVERY_MS
        var probeFails = 0
        while (generation.get() == gen && !Thread.currentThread().isInterrupted) {
            val raw = runCatching { Tunnel.currentStatus() }.getOrElse { "NoState||${it.message}" }
            val p = raw.split("|", limit = 3)
            val st = p.getOrElse(0) { "" }; val ip = p.getOrElse(1) { "" }; val det = p.getOrElse(2) { "" }
            // The engine can report Running while no packets flow; show that instead of a stale "Online".
            if (probeFails >= PROBE_MAX_FAILS - 1 && st == "Running") set("Stalled", ip, "no traffic to office server")
            else set(st, ip, det)
            val line = "$st $ip"
            if (line != lastShown) {
                lastShown = line
                GatewayState.log("Tunnel: ${st.ifBlank { "NoState" }} ${ip.ifBlank { "" }} ${GatewayState.redact(det)}".trim())
                getSystemService(NotificationManager::class.java).notify(NID,
                    note(if (GatewayState.tunnelReady) "Tunnel $ip" else "Tunnel ${st.ifBlank { "connecting" }}"))
            }
            if (det.contains("already used", true)) {
                set(st, ip, KEY_USED)
                GatewayState.log("Tunnel: $KEY_USED")
                return false
            }
            if (st == "NeedsLogin" && (det.contains("auth", true) || det.contains("401") || det.contains("invalid", true) || det.contains("expired", true))) {
                set(st, ip, "Key rejected or expired \u2014 tap New key on the QR page and scan again")
                GatewayState.log("Tunnel: Headscale rejected the key (${GatewayState.redact(det)})")
                return false
            }
            // Liveness: the engine state alone is not proof that the tunnel carries traffic.
            if (st == "Running" && ip.startsWith("100.") && System.currentTimeMillis() >= nextProbe) {
                val ok = probeOffice()
                nextProbe = System.currentTimeMillis() + if (ok) PROBE_EVERY_MS else PROBE_RETRY_MS
                if (ok) { if (probeFails > 0) GatewayState.log("Tunnel: office server reachable again"); probeFails = 0 }
                else {
                    probeFails++
                    GatewayState.log("Tunnel: office server not reachable ($probeFails/$PROBE_MAX_FAILS)")
                    if (probeFails >= PROBE_MAX_FAILS) {
                        set("Stalled", ip, "no traffic to office server; rebuilding")
                        GatewayState.log("Tunnel: no traffic through tunnel; rebuilding")
                        return false
                    }
                }
            }
            if (GatewayState.tunnelReady) notReadySince = System.currentTimeMillis()
            else if (System.currentTimeMillis() - notReadySince > 60_000) {
                GatewayState.log("Tunnel not running for 60s; rebuilding"); return false
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return true }
        }
        return true
    }

    /** TCP connect to the office server through the tunnel (OTP 3100, then SIP 5060). */
    private fun probeOffice(): Boolean = PROBE_PORTS.any { port ->
        runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(OFFICE_HOST, port), 5000) }; true }.getOrDefault(false)
    }

    /** Shutdown on a helper thread with a deadline; false = the Go engine is wedged. */
    private fun boundedShutdown(): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        Thread({ synchronized(engineLock) { runCatching { Tunnel.shutdown() } }; done.countDown() }, "gsmgw-tunnel-rebuild")
            .apply { isDaemon = true; start() }
        return try { done.await(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { true }
    }

    /** Per-install suffix so a reinstall enrols as a new node instead of colliding. */
    private fun nodeName(dir: String): String {
        val f = File(dir, "node.id")
        val id = if (f.exists()) f.readText().trim() else ""
        if (id.isNotEmpty()) return "gsmgw-$id"
        val fresh = java.util.UUID.randomUUID().toString().take(6)
        f.writeText(fresh)
        return "gsmgw-$fresh"
    }

    private val host = object : Host {
        override fun log(msg: String) { android.util.Log.i("GsmGwTunnel", GatewayState.redact(msg)) }
        override fun protect(fd: Long): Boolean = this@TunnelVpnService.protect(fd.toInt())
        override fun establish(addrs: String, routes: String, dns: String, mtu: Long): Long {
            val b = Builder().setSession("GSM Gateway").setMtu(mtu.toInt()).setBlocking(true)
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            add(b, addrs, true); add(b, routes, false)
            val pfd = try { b.establish() } catch (t: Throwable) {
                GatewayState.log("Tunnel: establish failed ${t.message}"); return -1
            }
            if (pfd == null) {
                GatewayState.log("Tunnel: VPN not allowed (open the app once, or turn off another VPN app)")
                set("Stopped", "", "VPN permission missing / other VPN active"); return -1
            }
            return pfd.detachFd().toLong()
        }
    }

    private fun add(b: Builder, enc: String, address: Boolean) {
        enc.split(',').map { it.trim() }.filter { it.contains('/') }.forEach {
            val ip = it.substringBeforeLast('/'); val bits = it.substringAfterLast('/').toIntOrNull() ?: return@forEach
            if (!address && bits == 0) return@forEach
            if (address) b.addAddress(ip, bits) else b.addRoute(ip, bits)
        }
    }

    private fun note(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("GSM Gateway tunnel").setContentText(text).setContentIntent(pi).setOngoing(true).build()
    }

    companion object {
        private const val CH = "tunnel"
        private const val NID = 2
        /** Serialises engine start/shutdown so a restart never races a slow background shutdown. */
        private val engineLock = Any()
        private const val OFFICE_HOST = "100.64.0.1"
        private val PROBE_PORTS = intArrayOf(3100, 5060)
        private const val PROBE_EVERY_MS = 60_000L
        private const val PROBE_RETRY_MS = 20_000L
        private const val PROBE_MAX_FAILS = 3

        private fun shutdownAsync(c: Context) {
            val done = java.util.concurrent.CountDownLatch(1)
            Thread({
                synchronized(engineLock) { runCatching { Tunnel.shutdown() } }
                done.countDown()
            }, "gsmgw-tunnel-shutdown").apply { isDaemon = true; start() }
            // Watchdog: if the engine is wedged, restart the process so START_STICKY brings
            // the tunnel and SIP back cleanly instead of leaving it stuck forever.
            Thread({
                if (!done.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    GatewayState.log("Tunnel: engine shutdown hung >30s; restarting app process")
                    if (Settings.load(c).tunnelWanted) android.os.Process.killProcess(android.os.Process.myPid())
                }
            }, "gsmgw-tunnel-watchdog").apply { isDaemon = true; start() }
        }

        const val KEY_USED = "Key used up \u2014 tap New key on the QR page and scan again"
        /** Starts the tunnel if configured and VPN consent exists. Returns false if consent is missing. */
        fun start(c: Context, reset: Boolean = false): Boolean {
            if (!Settings.load(c).tunnelWanted) return true
            if (prepare(c) != null) { GatewayState.log("Tunnel: VPN consent needed - open the app"); return false }
            c.startForegroundService(Intent(c, TunnelVpnService::class.java).putExtra("reset", reset))
            return true
        }
        fun stop(c: Context) { c.stopService(Intent(c, TunnelVpnService::class.java)) }
    }
}
