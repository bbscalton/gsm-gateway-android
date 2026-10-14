package org.neuereatec.gsmgateway

import android.annotation.SuppressLint
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Foreground service: keeps the SIP registration, watches the cellular call state,
 * auto-answers, places the INVITE to the target extension and runs the audio bridge.
 * One bridged call at a time; a second cellular call while busy is left alone.
 */
class GatewayService : Service(), SipUa.Listener {

    companion object {
        private const val CH = "gateway"
        private const val NID = 1
        fun start(c: Context) {
            c.startForegroundService(Intent(c, GatewayService::class.java))
        }
        fun stop(c: Context) { c.stopService(Intent(c, GatewayService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var sip: SipUa? = null
    private var rtp: Rtp? = null
    private var bridge: AudioBridge? = null
    private var wake: PowerManager.WakeLock? = null

    private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    private var ringingNumber: String? = null
    private var answerPending = false
    private var bridging = false
    private var sipLegUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "GSM Gateway", NotificationManager.IMPORTANCE_LOW))
        goForeground(false, "Starting")
        settings = Settings.load(this)
        GatewayState.audioMode = AudioBridge.probe(this, settings.forceAcoustic)
        GatewayState.gateway = "Idle"
        registerReceiver(phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        if (!settings.usable) GatewayState.sip = "Not configured"
        else {
            if (settings.tunnelWanted) {
                GatewayState.sip = "Waiting for tunnel"
                TunnelVpnService.start(this)
            }
            main.post(gate)
        }
        GatewayState.log("Service started; audio mode ${GatewayState.audioMode.label}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered, also when the service already runs.
        updateNote()
        return START_STICKY
    }

    /** Tunnel first: SIP registers only once the tailnet is up, and is dropped if it goes down. */
    private var lostSince = 0L
    private val gate = object : Runnable {
        override fun run() {
            val up = !settings.tunnelWanted || GatewayState.tunnelReady
            if (up) lostSince = 0L
            if (up && sip == null) {
                GatewayState.sip = "Registering to ${settings.server}:${settings.port}"
                GatewayState.log(if (settings.tunnelWanted) "Tunnel up (${GatewayState.tunnelIp}); starting SIP" else "Starting SIP (no tunnel configured)")
                sip = SipUa(settings, this@GatewayService).also { it.start() }
            } else if (!up && bridging) {
                // Data lost mid-call: give the network 20 s, then end both legs cleanly.
                if (lostSince == 0L) lostSince = System.currentTimeMillis()
                else if (System.currentTimeMillis() - lostSince > 20_000) {
                    GatewayState.log("Tunnel lost for 20s during a call; ending it")
                    endBridge("Data connection lost", hangGsm = true); lostSince = 0L
                }
            } else if (!up && sip != null && !bridging) {
                GatewayState.log("Tunnel down; SIP paused until it reconnects")
                sip?.stop(); sip = null
                GatewayState.registered = false; GatewayState.sip = "Waiting for tunnel"; updateNote()
            }
            main.postDelayed(this, 2000)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(gate)
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        endBridge("Service stopped", hangGsm = false)
        sip?.stop(); sip = null
        GatewayState.registered = false
        GatewayState.sip = "Stopped"
        GatewayState.gateway = "Stopped"
        super.onDestroy()
    }

    private fun goForeground(inCall: Boolean, text: String) {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Neuereatec GSM Gateway")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (inCall) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try { startForeground(NID, n, type) }
            catch (e: Exception) {
                GatewayState.log("Foreground (mic) refused: ${e.message}")
                try { startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL) } catch (_: Exception) { startForeground(NID, n) }
            }
        } else startForeground(NID, n)
    }

    private fun updateNote() {
        val t = "${if (GatewayState.tunnelReady) "Tunnel ${GatewayState.tunnelIp} \u00b7 " else ""}${if (GatewayState.registered) "Registered" else "Not registered"} · ${GatewayState.gateway}"
        goForeground(bridging, t)
    }

    // ---------------------------------------------------------------- cellular side

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!number.isNullOrBlank()) ringingNumber = number
            if (state == lastState) return
            val prev = lastState
            lastState = state
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> onRinging(prev)
                TelephonyManager.EXTRA_STATE_OFFHOOK -> onOffhook(prev)
                TelephonyManager.EXTRA_STATE_IDLE -> onIdle()
            }
        }
    }

    private fun onRinging(prev: String) {
        if (bridging || answerPending || prev == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            // One SIM = one voice call. Behave like a person on a call: reject the waiting call
            // so the carrier gives busy / voicemail. Telecom rejects the RINGING call first.
            GatewayState.log("Second cellular call rejected as busy (one call at a time)")
            rejectWaiting(); return
        }
        GatewayState.gateway = "Ringing"
        updateNote()
        if (!settings.autoAnswer) { GatewayState.log("Ringing; auto-answer off"); return }
        if (!GatewayState.registered) { GatewayState.log("Ringing but SIP not registered: not answering"); return }
        answerPending = true
        main.postDelayed({ answerGsm() }, settings.answerDelayMs.toLong())
    }

    @SuppressLint("MissingPermission")
    private fun rejectWaiting() {
        try {
            @Suppress("DEPRECATION")
            getSystemService(TelecomManager::class.java).endCall()
        } catch (e: Exception) { GatewayState.log("Could not reject waiting call: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun answerGsm() {
        if (!answerPending || lastState != TelephonyManager.EXTRA_STATE_RINGING) return
        try {
            getSystemService(TelecomManager::class.java).acceptRingingCall()
            GatewayState.log("Answered cellular call")
        } catch (e: Exception) {
            answerPending = false
            GatewayState.log("Auto-answer failed: ${e.message} (grant 'Answer phone calls')")
        }
    }

    private fun onOffhook(prev: String) {
        if (prev != TelephonyManager.EXTRA_STATE_RINGING || bridging) return
        if (!answerPending && !settings.autoAnswer) return
        answerPending = false
        startBridge(ringingNumber ?: "")
    }

    private fun onIdle() {
        answerPending = false
        ringingNumber = null
        if (bridging) endBridge("Caller hung up", hangGsm = false)
        else { GatewayState.gateway = "Idle"; updateNote() }
    }

    // ---------------------------------------------------------------- bridge

    private fun startBridge(caller: String) {
        bridging = true
        sipLegUp = false
        GatewayState.lastCaller = caller.ifBlank { "Withheld" }
        GatewayState.lastCallAt = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date())
        hop = -1
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gsmgw:call").apply { acquire(4 * 60 * 60 * 1000L) }
        updateNote()
        val r = Rtp()
        rtp = r
        GatewayState.log("Bridging ${GatewayState.lastCaller} -> ${settings.targetLabel}")
        sip?.huntList = settings.huntHeader
        nextHop(caller)
    }

    private var hop = -1
    private var bridgeCaller = ""
    private val ringTimeout = Runnable {
        if (bridging && !sipLegUp) {
            GatewayState.log("No answer in ${settings.ringSeconds}s at ${settings.dialPlan.getOrNull(hop)}")
            sip?.hangup()   // CANCEL; no onEnded comes back for our own cancel
            main.postDelayed({ if (bridging && !sipLegUp) nextHop(bridgeCaller) }, 300)
        }
    }

    /** Ring the next extension in the list; route mode has a single hop and no app timer. */
    private fun nextHop(caller: String) {
        bridgeCaller = caller
        main.removeCallbacks(ringTimeout)
        val plan = settings.dialPlan
        hop++
        if (hop >= plan.size) { endBridge("Nobody answered (${settings.targetLabel})", hangGsm = true); return }
        val t = plan[hop]
        GatewayState.gateway = "Ringing $t (${hop + 1}/${plan.size})"
        updateNote()
        GatewayState.log("Ringing $t")
        val r = rtp ?: return
        if (sip == null) { endBridge("SIP not running", hangGsm = true); return }
        sip?.invite(caller, r.localPort, t)
    }

    override fun onAnswered(remoteIp: String, remotePort: Int, payloadType: Int, dtmfPt: Int) = main.post {
        val r = rtp ?: return@post
        if (!bridging) { sip?.hangup(); return@post }
        sipLegUp = true
        main.removeCallbacks(ringTimeout)
        r.start(remoteIp, remotePort, payloadType, dtmfPt)
        val b = AudioBridge(this, r)
        bridge = b
        val used = b.start(AudioBridge.probe(this, settings.forceAcoustic))
        GatewayState.audioMode = used
        GatewayState.gateway = "In call: ${GatewayState.lastCaller} ↔ ${settings.dialPlan.getOrNull(hop)}"
        updateNote()
    }.let {}

    override fun onEnded(reason: String) = main.post {
        GatewayState.log("SIP leg ended: $reason")
        if (bridging) endBridge(reason, hangGsm = true)
    }.let {}

    @SuppressLint("MissingPermission")
    private fun endBridge(reason: String, hangGsm: Boolean) {
        if (!bridging) return
        bridging = false
        main.removeCallbacks(ringTimeout)
        bridge?.stop(); bridge = null
        sip?.hangup()
        rtp?.close(); rtp = null
        if (hangGsm && lastState != TelephonyManager.EXTRA_STATE_IDLE) {
            try {
                @Suppress("DEPRECATION")
                getSystemService(TelecomManager::class.java).endCall()
            } catch (e: Exception) { GatewayState.log("Could not hang up cellular call: ${e.message}") }
        }
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        GatewayState.gateway = "Idle (last: $reason)"
        GatewayState.log("Call ended: $reason")
        updateNote()
    }

    override fun onRegistration(ok: Boolean, info: String) = main.post {
        val changed = GatewayState.registered != ok
        GatewayState.registered = ok
        GatewayState.sip = info
        if (changed) { GatewayState.log(info); updateNote() }
    }.let {}
}
