package com.example.clashforlsposed

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ClashApplication : Application(), XposedServiceHelper.OnServiceListener {
    @Volatile
    private var xposedService: XposedService? = null

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        refreshFrameworkState()
    }

    fun refreshFrameworkState() {
        val service = xposedService ?: return
        runCatching {
            val api = service.apiVersion
            val frameworkName = service.frameworkName
            val frameworkVersion = service.frameworkVersion
            val frameworkVersionCode = service.frameworkVersionCode
            val scope = runCatching { service.scope }.getOrDefault(emptyList())
            val targets = if (api >= XposedService.API_102) {
                runCatching { service.runningTargets.map { it.processName } }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            LsposedStatus.onServiceConnected(
                api,
                frameworkName,
                frameworkVersion,
                frameworkVersionCode,
                scope,
                targets
            )
        }.onFailure {
            LsposedStatus.onServiceDisconnected()
        }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) xposedService = null
        LsposedStatus.onServiceDisconnected()
    }
}
