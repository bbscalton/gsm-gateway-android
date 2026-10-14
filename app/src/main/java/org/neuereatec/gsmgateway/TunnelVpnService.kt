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
        runCatching { Tunnel.shutdown() }
        set("Stopped", "", "")
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
            runCatching { Tunnel.shutdown() }
            set("Starting", "", "reconnecting")
            try { Thread.sleep(delay * 1000) } catch (_: InterruptedException) { return }
        }
    }

    /** @return true = stop for good, false = retry. */
    private fun runOnce(reset: Boolean, gen: Int): Boolean {
        val s = Settings.load(this)
        if (!s.tunnelWanted) return true
        val dir = File(filesDir, "tailscale").apply { mkdirs() }.absolutePath
        if (reset) Tunnel.resetState(dir)
        val cu = File(dir, "control.url")
        if (!cu.exists() || cu.readText().trim() != s.hsUrl) cu.writeText(s.hsUrl)
        set("Starting", "", "logging in to ${s.hsUrl}")
        GatewayState.log("Tunnel: joining ${s.hsUrl} key=${s.hsKey.take(12)}\u2026")
        try {
            Tunnel.start(dir, host)
            Tunnel.login(s.hsUrl, s.hsKey, nodeName(dir))
        } catch (t: Throwable) {
            GatewayState.log("Tunnel login failed: ${GatewayState.redact(t.message ?: t.javaClass.simpleName)}")
            set("NeedsLogin", "", t.message ?: "login failed")
            return false
        }
        var lastShown = ""
        var notReadySince = System.currentTimeMillis()
        while (generation.get() == gen && !Thread.currentThread().isInterrupted) {
            val raw = runCatching { Tunnel.currentStatus() }.getOrElse { "NoState||${it.message}" }
            val p = raw.split("|", limit = 3)
            val st = p.getOrElse(0) { "" }; val ip = p.getOrElse(1) { "" }; val det = p.getOrElse(2) { "" }
            set(st, ip, det)
            val line = "$st $ip"
            if (line != lastShown) {
                lastShown = line
                GatewayState.log("Tunnel: ${st.ifBlank { "NoState" }} ${ip.ifBlank { "" }} ${GatewayState.redact(det)}".trim())
                getSystemService(NotificationManager::class.java).notify(NID,
                    note(if (GatewayState.tunnelReady) "Tunnel $ip" else "Tunnel ${st.ifBlank { "connecting" }}"))
            }
            if (st == "NeedsLogin" && (det.contains("auth", true) || det.contains("401") || det.contains("invalid", true))) {
                GatewayState.log("Tunnel: Headscale rejected the key (spent/expired?). Scan a fresh gateway QR.")
                return false
            }
            if (GatewayState.tunnelReady) notReadySince = System.currentTimeMillis()
            else if (System.currentTimeMillis() - notReadySince > 60_000) {
                GatewayState.log("Tunnel not running for 60s; rebuilding"); return false
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return true }
        }
        return true
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
