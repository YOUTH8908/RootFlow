package com.example.clashforlsposed.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Process as AndroidProcess
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.clashforlsposed.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

object ProxyRuntimeState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var message: String = ""

    @Volatile
    var ipv6Redirect: Boolean = false

    @Volatile
    var rootAvailable: Boolean? = null

    @Volatile
    var starting: Boolean = false

    @Volatile
    var waitingForVpn: Boolean = false

    @Volatile
    var retrying: Boolean = false
}

class ProxyCoreService : Service() {
    private var coreProcess: java.lang.Process? = null
    private var rulesApplied = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var vpnCallbackRegistered = false
    private var retryJob: Job? = null
    private var vpnWaitJob: Job? = null
    private var retryAttempt = 0
    private var retryGeneration = 0
    @Volatile
    private var latestStartId = 0

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            serviceScope.launch {
                operationMutex.withLock {
                    if (isAutoRunEnabled(this@ProxyCoreService) && NetworkStatus.hasActiveVpn(this@ProxyCoreService)) {
                        pauseForOtherVpn()
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            serviceScope.launch {
                delay(VPN_SETTLE_DELAY_MS)
                operationMutex.withLock {
                    if (isAutoRunEnabled(this@ProxyCoreService) && !NetworkStatus.hasActiveVpn(this@ProxyCoreService)) {
                        retryJob?.cancel()
                        retryJob = null
                        startProxy(latestStartId)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Google 代理", NotificationManager.IMPORTANCE_LOW)
        )
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, vpnCallback)
            vpnCallbackRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            setAutoRunEnabled(this, false)
            cancelRetry()
            vpnWaitJob?.cancel()
            vpnWaitJob = null
            retryAttempt = 0
            serviceScope.launch {
                operationMutex.withLock {
                    stopProxy()
                    ProxyRuntimeState.waitingForVpn = false
                }
                if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            setAutoRunEnabled(this, true)
            cancelRetry()
            retryAttempt = 0
        }
        if (intent == null && !isAutoRunEnabled(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            notification("正在启动本地代理"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        serviceScope.launch { operationMutex.withLock { startProxy(startId) } }
        return START_STICKY
    }

    private fun startProxy(startId: Int, fromRetry: Boolean = false) {
        if (!fromRetry) cancelRetry()
        if (coreProcess?.isAlive == true) return
        ProxyRuntimeState.starting = true
        ProxyRuntimeState.waitingForVpn = false
        ProxyRuntimeState.retrying = false
        ProxyRuntimeState.message = "正在验证配置并启动核心"
        var rootUnavailable = false
        try {
            val config = ProxyConfigStore.configForStartup(this)
            require(config != null) { "请先导入 Clash 配置、订阅或节点链接" }
            if (NetworkStatus.hasActiveVpn(this)) {
                pauseForOtherVpn()
                return
            }
            rootUnavailable = !RootShell.isAvailable()
            require(!rootUnavailable) { "未获得 root 权限，等待授权后自动重试" }
            // A previous force-stop can leave the dedicated chains behind because Android
            // does not guarantee onDestroy(). Remove only our own chains before starting.
            val staleRules = RootRedirector.clear()
            require(staleRules.succeeded) {
                "无法清理上次残留的 root 规则：${staleRules.output.ifBlank { "请检查 root 权限" }}"
            }
            val home = File(filesDir, "mihomo").apply { mkdirs() }
            val configFile = File(home, "config.yaml").apply { writeText(config) }
            val logFile = File(home, "core.log")
            val nativePath = File(applicationInfo.nativeLibraryDir, "libmihomo.so")
            require(nativePath.canExecute() || nativePath.setExecutable(true)) {
                "Mihomo 核心不可执行"
            }

            val validationLog = File(home, "config-test.log")
            val validationProcess = ProcessBuilder(
                nativePath.absolutePath,
                "-t", "-d", home.absolutePath,
                "-f", configFile.absolutePath
            ).redirectErrorStream(true).redirectOutput(validationLog).start()
            if (!validationProcess.waitFor(15, TimeUnit.SECONDS)) {
                validationProcess.destroyForcibly()
                error("Mihomo 配置检查超时")
            }
            check(validationProcess.exitValue() == 0) {
                "Mihomo 配置无效：${tail(validationLog)}"
            }

            coreProcess = ProcessBuilder(
                nativePath.absolutePath,
                "-d", home.absolutePath,
                "-f", configFile.absolutePath
            ).redirectErrorStream(true).redirectOutput(logFile).start()
            Thread.sleep(700)
            check(coreProcess?.isAlive == true) {
                "核心启动失败：${tail(logFile)}"
            }
            val process = coreProcess ?: error("核心进程不存在")

            rulesApplied = true
            val ipv6 = RootRedirector.apply(
                GooglePackageCatalog.uids(this),
                AndroidProcess.myPid()
            ).getOrElse { throw it }
            ProxyRuntimeState.rootAvailable = true
            ProxyRuntimeState.starting = false
            ProxyRuntimeState.ipv6Redirect = ipv6
            ProxyRuntimeState.running = true
            ProxyRuntimeState.waitingForVpn = false
            retryAttempt = 0
            ProxyRuntimeState.message = if (ipv6) {
                "已启动，Google 系统应用的 TCP 和 UDP DNS 已转发"
            } else {
                "已启动，但设备缺少 IPv6 NAT；IPv6 流量可能直连"
            }
            updateNotification("代理运行中，开机和 VPN 退出后会自动恢复")
            watchRuntime(process)
        } catch (error: Throwable) {
            val rootFailure = rootUnavailable || error.message?.contains("root", ignoreCase = true) == true
            if (rootFailure) {
                ProxyRuntimeState.rootAvailable = false
            }
            stopProxy(updateMessage = false)
            if (rootFailure && isAutoRunEnabled(this)) {
                scheduleRetry(
                    startId,
                    error.message ?: "未获得 root 权限",
                    "root 尚未就绪，稍后自动重试"
                )
            } else {
                fail(error.message ?: "代理启动失败", startId)
            }
        }
    }

    private fun scheduleRetry(startId: Int, message: String, notificationText: String) {
        if (!isAutoRunEnabled(this) || retryJob?.isActive == true) return
        val delayMs = (ROOT_RETRY_INITIAL_MS shl retryAttempt.coerceAtMost(5)).coerceAtMost(ROOT_RETRY_MAX_MS)
        retryAttempt = (retryAttempt + 1).coerceAtMost(6)
        val generation = ++retryGeneration
        ProxyRuntimeState.starting = false
        ProxyRuntimeState.waitingForVpn = false
        ProxyRuntimeState.retrying = true
        ProxyRuntimeState.message = "$message；${delayMs / 1_000} 秒后自动重试"
        updateNotification("$notificationText，${delayMs / 1_000} 秒后自动重试")
        retryJob = serviceScope.launch {
            delay(delayMs)
            operationMutex.withLock {
                if (generation != retryGeneration) return@withLock
                retryJob = null
                if (isAutoRunEnabled(this@ProxyCoreService)) startProxy(startId, fromRetry = true)
            }
        }
    }

    private fun cancelRetry() {
        retryGeneration++
        retryJob?.cancel()
        retryJob = null
    }

    private fun stopProxy(updateMessage: Boolean = true) {
        val clearResult = if (rulesApplied || ProxyRuntimeState.running) {
            RootRedirector.clear()
        } else {
            ShellResult(0, "没有本次运行的 root 规则")
        }
        rulesApplied = false
        coreProcess?.let { process ->
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        coreProcess = null
        ProxyRuntimeState.running = false
        ProxyRuntimeState.starting = false
        ProxyRuntimeState.ipv6Redirect = false
        ProxyRuntimeState.retrying = false
        if (updateMessage) {
            ProxyRuntimeState.message = if (clearResult.succeeded) {
                "已停止，root 规则已清理"
            } else {
                "核心已停止，但 root 规则清理失败：${clearResult.output.ifBlank { "请重新授权 root 后重试" }}"
            }
        }
    }

    private fun pauseForOtherVpn() {
        cancelRetry()
        retryAttempt = 0
        stopProxy(updateMessage = false)
        ProxyRuntimeState.waitingForVpn = true
        ProxyRuntimeState.retrying = false
        ProxyRuntimeState.message = "检测到其他 VPN，代理已暂停；VPN 退出后会自动恢复"
        updateNotification("等待其他 VPN 退出，之后自动恢复代理")
        if (vpnWaitJob?.isActive != true) {
            vpnWaitJob = serviceScope.launch {
                try {
                    while (isAutoRunEnabled(this@ProxyCoreService) && ProxyRuntimeState.waitingForVpn) {
                        delay(VPN_POLL_INTERVAL_MS)
                        operationMutex.withLock {
                            if (isAutoRunEnabled(this@ProxyCoreService) &&
                                ProxyRuntimeState.waitingForVpn &&
                                !NetworkStatus.hasActiveVpn(this@ProxyCoreService)
                            ) {
                                startProxy(latestStartId)
                                return@withLock
                            }
                        }
                    }
                } finally {
                    vpnWaitJob = null
                }
            }
        }
    }

    private fun fail(message: String, startId: Int) {
        ProxyRuntimeState.starting = false
        ProxyRuntimeState.running = false
        ProxyRuntimeState.waitingForVpn = false
        ProxyRuntimeState.retrying = false
        ProxyRuntimeState.message = message
        if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun watchRuntime(process: java.lang.Process) {
        serviceScope.launch {
            while (coreProcess === process && ProxyRuntimeState.running) {
                delay(2_000)
                if (coreProcess !== process || !ProxyRuntimeState.running) return@launch
                if (NetworkStatus.hasActiveVpn(this@ProxyCoreService)) {
                    operationMutex.withLock {
                        if (coreProcess === process && ProxyRuntimeState.running) {
                            pauseForOtherVpn()
                        }
                    }
                    return@launch
                }
                if (!process.isAlive) {
                    operationMutex.withLock {
                        if (coreProcess === process) {
                            stopProxy(updateMessage = false)
                            if (isAutoRunEnabled(this@ProxyCoreService)) {
                                scheduleRetry(
                                    latestStartId,
                                    "Mihomo 核心已退出，root 规则已清理",
                                    "代理核心已退出，稍后自动重启"
                                )
                            } else {
                                ProxyRuntimeState.message = "Mihomo 核心已退出，root 规则已清理"
                                updateNotification("代理核心已退出，请在应用中重新启动")
                            }
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun tail(file: File): String =
        if (!file.exists()) "无日志" else file.readLines().takeLast(5).joinToString(" ")

    override fun onDestroy() {
        val hadRuntime = rulesApplied || coreProcess != null
        val process = coreProcess
        if (vpnCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(vpnCallback) }
            vpnCallbackRegistered = false
        }
        coreProcess = null
        rulesApplied = false
        ProxyRuntimeState.running = false
        ProxyRuntimeState.starting = false
        ProxyRuntimeState.waitingForVpn = false
        ProxyRuntimeState.retrying = false
        retryJob?.cancel()
        vpnWaitJob?.cancel()
        retryJob = null
        vpnWaitJob = null
        if (hadRuntime) {
            Thread {
                runCatching { RootRedirector.clear() }
                process?.destroy()
            }.apply {
                name = "google-proxy-cleanup"
                isDaemon = true
                start()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.clashforlsposed.proxy.START"
        const val ACTION_STOP = "com.example.clashforlsposed.proxy.STOP"
        private const val PREFS = "proxy_config"
        private const val AUTO_RUN = "auto_run_enabled"
        private const val VPN_SETTLE_DELAY_MS = 1_500L
        private const val VPN_POLL_INTERVAL_MS = 3_000L
        private const val ROOT_RETRY_INITIAL_MS = 3_000L
        private const val ROOT_RETRY_MAX_MS = 30_000L
        private const val CHANNEL = "google_proxy"
        private const val NOTIFICATION_ID = 1001

        fun isAutoRunEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(AUTO_RUN, true)

        private fun setAutoRunEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AUTO_RUN, enabled)
                .apply()
        }
    }
}
