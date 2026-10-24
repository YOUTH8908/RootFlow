package com.example.clashforlsposed

/** Framework service connection state shared by the app process and the status screen. */
object LsposedStatus {
    @Volatile
    private var connected = false

    @Volatile
    private var apiVersion = 0

    @Volatile
    private var frameworkName = ""

    @Volatile
    private var frameworkVersion = ""

    @Volatile
    private var frameworkVersionCode = 0L

    @Volatile
    private var scope = emptyList<String>()

    @Volatile
    private var runningTargets = emptyList<String>()

    @JvmStatic
    fun onServiceConnected(
        api: Int,
        name: String,
        version: String,
        versionCode: Long,
        moduleScope: List<String>,
        targetProcesses: List<String>
    ) {
        connected = true
        apiVersion = api
        frameworkName = name
        frameworkVersion = version
        frameworkVersionCode = versionCode
        scope = moduleScope.toList()
        runningTargets = targetProcesses.toList()
    }

    @JvmStatic
    fun onServiceDisconnected() {
        connected = false
        apiVersion = 0
        frameworkName = ""
        frameworkVersion = ""
        frameworkVersionCode = 0L
        scope = emptyList()
        runningTargets = emptyList()
    }

    fun isConnected(): Boolean = connected

    fun apiVersion(): Int = apiVersion

    fun frameworkName(): String = frameworkName

    fun frameworkVersion(): String = frameworkVersion

    fun frameworkVersionCode(): Long = frameworkVersionCode

    fun scope(): List<String> = scope

    fun runningTargets(): List<String> = runningTargets
}
