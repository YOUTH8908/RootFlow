package com.example.clashforlsposed.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ProxyCoreService.isAutoRunEnabled(context) || !ProxyConfigStore.hasConfig(context)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, ProxyCoreService::class.java).setAction(ProxyCoreService.ACTION_START)
        )
    }
}
