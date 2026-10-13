package org.neuereatec.gsmgateway

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val navy = Color.parseColor("#0B2545")
    private val teal = Color.parseColor("#13C4A3")
    private val ink = Color.parseColor("#E8EEF6")
    private val muted = Color.parseColor("#9AABBF")

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var server: EditText
    private lateinit var port: EditText
    private lateinit var ext: EditText
    private lateinit var pw: EditText
    private lateinit var target: EditText
    private lateinit var delay: EditText
    private lateinit var auto: CheckBox
    private lateinit var acoustic: CheckBox
    private val ui = Handler(Looper.getMainLooper())

    private val scan = registerForActivityResult(ScanContract()) { r -> r.contents?.let { applyLink(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        buildUi()
        fill(Settings.load(this))
        intent?.data?.let { applyLink(it.toString()) }
        askPermissions()
        if (Settings.load(this).usable) GatewayService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { applyLink(it.toString()) }
    }

    override fun onResume() { super.onResume(); tick() }
    override fun onPause() { super.onPause(); ui.removeCallbacksAndMessages(null) }

    private fun tick() {
        val g = GatewayState
        val mode = g.audioMode
        status.text = buildString {
            append("SIP:          ").append(if (g.registered) "Registered" else "Not registered").append('\n')
            append("              ").append(g.sip).append('\n')
            append("Gateway:      ").append(g.gateway).append('\n')
            append("Audio mode:   ").append(mode.label).append('\n')
            append("              ").append(mode.detail).append('\n')
            append("Last caller:  ").append(g.lastCaller).append(if (g.lastCallAt.isNotEmpty()) "  (${g.lastCallAt})" else "").append('\n')
            if (g.gateway.startsWith("In call")) append("Capture level: ").append("█".repeat(g.level / 5)).append(" ${g.level}%")
        }
        log.text = g.logText()
        ui.postDelayed(::tick, 1000)
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        val missing = p.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Battery: already unrestricted"); return }
        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun applyLink(link: String) {
        val s = Settings.fromLink(link, read())
        if (s == null) { toast("Setup link not recognised"); return }
        fill(s); toast("Imported. Check the fields, then tap Save & restart.")
    }

    private fun read() = Settings(
        server = server.text.toString().trim(),
        port = port.text.toString().toIntOrNull() ?: 5060,
        ext = ext.text.toString().trim(),
        password = pw.text.toString(),
        target = target.text.toString().trim().ifEmpty { "105" },
        autoAnswer = auto.isChecked,
        answerDelayMs = delay.text.toString().toIntOrNull()?.coerceIn(0, 20000) ?: 1500,
        forceAcoustic = acoustic.isChecked,
    )

    private fun fill(s: Settings) {
        server.setText(s.server); port.setText(s.port.toString()); ext.setText(s.ext); pw.setText(s.password)
        target.setText(s.target); delay.setText(s.answerDelayMs.toString())
        auto.isChecked = s.autoAnswer; acoustic.isChecked = s.forceAcoustic
    }

    private fun save() {
        val s = read()
        if (!s.usable) { toast("Server and extension are required"); return }
        Settings.save(this, s)
        GatewayService.stop(this)
        ui.postDelayed({ GatewayService.start(this) }, 600)
        toast("Saved. Re-registering…")
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(40)) }
        root.addView(text("Neuereatec GSM Gateway", 24f, ink, bold = true))
        root.addView(text("Answers cellular calls and bridges them into the phone system.", 14f, muted))
        status = text("", 13f, Color.parseColor("#1C2B26")).apply {
            typeface = Typeface.MONOSPACE
            background = round(Color.parseColor("#EEF2F0"), 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(status, lp(top = 16))

        root.addView(section("SIP server"))
        server = edit("Server (100.64.0.1 Headscale)")
        root.addView(server)
        root.addView(LinearLayout(this).apply {
            addView(chip("Headscale 100.64.0.1") { server.setText(Settings.DEFAULT_SERVER) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip("neuereatec.org (soon)") { server.setText(Settings.PUBLIC_SERVER) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(text("neuereatec.org is not reachable from outside yet; use the Headscale address (Tailscale app connected) or a LAN address such as 192.168.x.x.", 12f, muted))
        port = edit("Port", InputType.TYPE_CLASS_NUMBER); root.addView(port)
        ext = edit("Extension", InputType.TYPE_CLASS_NUMBER); root.addView(ext)
        pw = edit("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(pw)

        root.addView(section("Bridge"))
        target = edit("Target extension (105)", InputType.TYPE_CLASS_PHONE); root.addView(target)
        delay = edit("Answer after (ms)", InputType.TYPE_CLASS_NUMBER); root.addView(delay)
        auto = check("Auto-answer cellular calls"); root.addView(auto)
        acoustic = check("Force speakerphone mode (ignore root audio)"); root.addView(acoustic)

        root.addView(button("Save & restart", primary = true) { save() }, lp(top = 12))
        root.addView(LinearLayout(this).apply {
            addView(button("Scan QR") {
                scan.launch(ScanOptions().setPrompt("Scan setup QR").setBeepEnabled(false).setOrientationLocked(false))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Paste link") {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                if (clip.isNullOrBlank()) toast("Clipboard is empty") else applyLink(clip)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(LinearLayout(this).apply {
            addView(button("Permissions") { askPermissions() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Battery") { askBattery() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("Stop") { GatewayService.stop(this@MainActivity) }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        root.addView(section("Audio modes"))
        root.addView(text(
            "Normal phone: the app turns on the speakerphone. The microphone hears the caller and the loudspeaker plays the extension. It works, but expect echo and room noise; put the phone somewhere quiet.\n\n" +
            "Rooted phone: install the Magisk priv-app module from the download page. Android then grants call-audio permissions, and the status shows a Rooted mode. On Android 13+ both directions are clean. On older versions, only the caller's side is clean.",
            13f, muted))

        root.addView(section("Log"))
        log = text("", 11f, muted).apply { typeface = Typeface.MONOSPACE }
        root.addView(log)

        setContentView(ScrollView(this).apply { setBackgroundColor(navy); addView(root) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun round(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4))
    }
    private fun section(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(20), 0, dp(4)) }
    private fun edit(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint; inputType = type; setTextColor(ink); setHintTextColor(muted); isSingleLine = true
    }
    private fun check(t: String) = CheckBox(this).apply { text = t; setTextColor(ink) }
    private fun chip(t: String, on: () -> Unit) = button(t, small = true) { on() }
    private fun button(t: String, primary: Boolean = false, small: Boolean = false, on: (View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = if (small) 12f else 14f
        setTextColor(if (primary) Color.parseColor("#04281F") else ink)
        background = round(if (primary) teal else Color.parseColor("#2A333D"), 24)
        gravity = Gravity.CENTER
        setOnClickListener(on)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
