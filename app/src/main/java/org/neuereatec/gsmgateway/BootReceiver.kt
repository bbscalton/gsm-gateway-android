package org.neuereatec.gsmgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot / app update: tunnel first (VPN consent survives reboots), then the gateway service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Settings.load(context)
        if (!s.usable) return
        if (s.tunnelWanted) TunnelVpnService.start(context)
        GatewayService.start(context)
    }
}
