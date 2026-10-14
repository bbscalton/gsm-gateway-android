package org.neuereatec.gsmgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Isolated OTP SMS sender (1.2.0). Separate service, own thread, own notification; it never touches
 * GatewayService, SipUa, AudioBridge or the tunnel. It only polls the Neuereatec OTP queue over the
 * tailnet with the token from the office QR, sends with SmsManager and reports sent/failed.
 * SMS sending runs on the radio's SMS path, which does not interrupt an active GSM call.
 */
class OtpSmsService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = OtpSmsPrefs.url(this); val token = OtpSmsPrefs.token(this)
        if (url.isNullOrBlank() || token.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (!running) { running = true; thread(name = "otp-sms", isDaemon = true) { loop(url, token) } }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "OTP SMS sender", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("OTP SMS sender").setContentText("Waiting for login codes").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NID, n)
    }

    private fun loop(base: String, token: String) {
        var wait = 3000L
        while (running) {
            try {
                val c = (URL("$base/gw/queue").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 10000; setRequestProperty("authorization", "Bearer $token")
                }
                if (c.responseCode == 200) {
                    OtpSmsPrefs.lastPoll = System.currentTimeMillis(); OtpSmsPrefs.lastError = ""
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val msgs = j.optJSONArray("messages")
                    for (i in 0 until (msgs?.length() ?: 0)) {
                        val m = msgs!!.getJSONObject(i)
                        // Drop anything that would arrive already expired (5-minute codes).
                        if (m.optLong("expiresAt") - System.currentTimeMillis() < 15_000) { report(base, token, m.getString("id"), false, "expired before send"); continue }
                        send(base, token, m.getString("id"), m.getString("to"), m.getString("text"))
                    }
                    wait = j.optLong("pollMs", 3000).coerceIn(1000, 30000)
                } else { OtpSmsPrefs.lastError = "queue HTTP ${c.responseCode}"; wait = 15000 }
            } catch (t: Throwable) { OtpSmsPrefs.lastError = t.javaClass.simpleName; wait = 10000 }
            try { Thread.sleep(wait) } catch (_: InterruptedException) {}
        }
    }

    private fun send(base: String, token: String, id: String, to: String, text: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            report(base, token, id, false, "SEND_SMS not granted"); return
        }
        val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        val pending = AtomicInteger(parts.size); var failed: String? = null
        val action = "org.neuereatec.gsmgateway.OTP_SENT.$id"
        val rx = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (resultCode != android.app.Activity.RESULT_OK) failed = "radio error $resultCode"
                if (pending.decrementAndGet() == 0) {
                    runCatching { unregisterReceiver(this) }
                    thread { report(base, token, id, failed == null, failed) }
                }
            }
        }
        ContextCompat.registerReceiver(this, rx, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val sent = ArrayList(parts.indices.map { k -> PendingIntent.getBroadcast(this, (id.hashCode() + k), Intent(action).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT) })
        try {
            if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, sent, null) else sms.sendTextMessage(to, null, text, sent[0], null)
            Log.i(TAG, "OTP SMS queued to radio id=$id parts=${parts.size}")
        } catch (t: Throwable) { runCatching { unregisterReceiver(rx) }; report(base, token, id, false, t.javaClass.simpleName) }
    }

    private fun report(base: String, token: String, id: String, ok: Boolean, err: String?) {
        if (ok) OtpSmsPrefs.bumpSent(this) else OtpSmsPrefs.bumpFailed(this)
        Log.i(TAG, "OTP SMS id=$id ${if (ok) "sent" else "failed: $err"}")
        runCatching {
            val c = (URL("$base/gw/report").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 8000
                setRequestProperty("authorization", "Bearer $token"); setRequestProperty("content-type", "application/json")
            }
            c.outputStream.use { it.write(JSONObject().put("id", id).put("status", if (ok) "sent" else "failed").put("error", err ?: "").toString().toByteArray()) }
            c.responseCode
        }
    }

    companion object {
        private const val CH = "otp_sms"; private const val NID = 4711; private const val TAG = "OtpSms"
        fun start(ctx: Context) { if (OtpSmsPrefs.configured(ctx)) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, OtpSmsService::class.java)) } }
    }
}

object OtpSmsPrefs {
    @Volatile var lastPoll = 0L
    @Volatile var lastError = ""
    private fun p(ctx: Context) = ctx.getSharedPreferences("otp_sms", Context.MODE_PRIVATE)
    fun url(ctx: Context) = p(ctx).getString("url", null)
    fun token(ctx: Context) = p(ctx).getString("token", null)
    fun configured(ctx: Context) = !url(ctx).isNullOrBlank() && !token(ctx).isNullOrBlank()
    fun sent(ctx: Context) = p(ctx).getInt("sent", 0)
    fun failed(ctx: Context) = p(ctx).getInt("failed", 0)
    fun bumpSent(ctx: Context) = p(ctx).edit().putInt("sent", sent(ctx) + 1).apply()
    fun bumpFailed(ctx: Context) = p(ctx).edit().putInt("failed", failed(ctx) + 1).apply()
    /** gsmgw-otp://setup?url=http://100.64.0.1:3100&token=... (office QR only; never in the APK). */
    fun applyLink(ctx: Context, raw: String): Boolean {
        val u = runCatching { android.net.Uri.parse(raw.trim()) }.getOrNull() ?: return false
        if (u.scheme != "gsmgw-otp") return false
        val url = u.getQueryParameter("url")?.trimEnd('/') ?: return false
        val token = u.getQueryParameter("token") ?: return false
        p(ctx).edit().putString("url", url).putString("token", token).apply()
        return true
    }
    fun statusLine(ctx: Context): String = if (!configured(ctx)) "OTP SMS: not set up (scan the office OTP QR)" else
        "OTP SMS: sent ${sent(ctx)} / failed ${failed(ctx)}" + (if (lastError.isNotEmpty()) " ($lastError)" else "")
}
