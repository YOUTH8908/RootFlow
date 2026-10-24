package com.example.clashforlsposed.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.clashforlsposed.R
import com.example.clashforlsposed.LsposedStatus
import com.example.clashforlsposed.proxy.NetworkStatus
import com.example.clashforlsposed.proxy.ProxyConfigStore
import com.example.clashforlsposed.proxy.ProxyCoreService
import com.example.clashforlsposed.proxy.ProxyRuntimeState
import com.example.clashforlsposed.proxy.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource

private enum class DashboardTab(val title: String) {
    HOME("首页"),
    PROXIES("代理"),
    PROFILES("配置")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ClashDashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(DashboardTab.HOME.name) }
    var refreshToken by remember { mutableLongStateOf(0L) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var linkDialogVisible by remember { mutableStateOf(false) }
    var linkText by rememberSaveable { mutableStateOf("") }
    val currentTab = DashboardTab.valueOf(tab)
    val snapshot = remember(refreshToken) { ProxyConfigStore.snapshot(context) }
    val rootAvailable = remember(refreshToken) { ProxyRuntimeState.rootAvailable }
    val vpnActive = remember(refreshToken) { NetworkStatus.hasActiveVpn(context) }
    val proxyRunning = remember(refreshToken) { ProxyRuntimeState.running }
    val proxyStarting = remember(refreshToken) { ProxyRuntimeState.starting }
    val waitingForVpn = remember(refreshToken) { ProxyRuntimeState.waitingForVpn }
    val retrying = remember(refreshToken) { ProxyRuntimeState.retrying }
    val frameworkDetected = remember(refreshToken) { LsposedStatus.isConnected() }
    val runningTargets = remember(refreshToken) { LsposedStatus.runningTargets() }
    val apiVersion = remember(refreshToken) { LsposedStatus.apiVersion() }

    fun refresh() {
        refreshToken++
    }

    fun showImportResult(result: Result<ProxyConfigStore.ImportSummary>) {
        scope.launch {
            snackbarHostState.showSnackbar(
                result.fold(
                    onSuccess = { it.successMessage() },
                    onFailure = { it.message ?: "导入失败" }
                )
            )
            refresh()
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ProxyConfigStore.importUri(context, uri) }
            }
            showImportResult(result)
        }
    }
    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { importQrImage(context, uri) }
            }
            showImportResult(result)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                ProxyRuntimeState.rootAvailable = RootShell.isAvailable()
                (context.applicationContext as? com.example.clashforlsposed.ClashApplication)
                    ?.refreshFrameworkState()
            }
            refresh()
            delay(1_500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentTab.title) },
                actions = {
                    if (currentTab == DashboardTab.PROFILES) {
                        IconButton(onClick = { addMenuExpanded = true }) {
                            Icon(Icons.Default.Add, contentDescription = "添加配置")
                        }
                        DropdownMenu(
                            expanded = addMenuExpanded,
                            onDismissRequest = { addMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("二维码图片") },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) },
                                onClick = {
                                    addMenuExpanded = false
                                    qrLauncher.launch(arrayOf("image/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("配置文件") },
                                leadingIcon = { Icon(Icons.Default.Folder, null) },
                                onClick = {
                                    addMenuExpanded = false
                                    fileLauncher.launch(arrayOf("application/*", "text/*", "*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("链接或节点分享") },
                                leadingIcon = { Icon(Icons.Default.Link, null) },
                                onClick = {
                                    addMenuExpanded = false
                                    linkText = ""
                                    linkDialogVisible = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                DashboardTab.values().forEach { destination ->
                    NavigationBarItem(
                        selected = currentTab == destination,
                        onClick = { tab = destination.name },
                        icon = {
                            Icon(
                                imageVector = when (destination) {
                                    DashboardTab.HOME -> Icons.Default.Home
                                    DashboardTab.PROXIES -> Icons.Default.Router
                                    DashboardTab.PROFILES -> Icons.Default.Settings
                                },
                                contentDescription = destination.title
                            )
                        },
                        label = { Text(destination.title) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when (currentTab) {
            DashboardTab.HOME -> HomePage(
                modifier = Modifier.padding(innerPadding),
                snapshot = snapshot,
                rootAvailable = rootAvailable,
                vpnActive = vpnActive,
                proxyRunning = proxyRunning,
                proxyStarting = proxyStarting,
                waitingForVpn = waitingForVpn,
                retrying = retrying,
                frameworkDetected = frameworkDetected,
                runningTargets = runningTargets.size,
                apiVersion = apiVersion,
                onToggleProxy = {
                    if (proxyRunning || waitingForVpn || retrying) {
                        context.startService(IntentFactory.stop(context))
                    } else {
                        ContextCompat.startForegroundService(context, IntentFactory.start(context))
                    }
                    refresh()
                },
                onRefresh = { refresh() }
            )
            DashboardTab.PROXIES -> ProxyPage(
                modifier = Modifier.padding(innerPadding),
                snapshot = snapshot,
                onSelect = { name ->
                    scope.launch(Dispatchers.IO) {
                        val selected = ProxyConfigStore.selectProxy(context, name)
                        withContext(Dispatchers.Main) {
                            refresh()
                            snackbarHostState.showSnackbar(selected.message)
                        }
                    }
                }
            )
            DashboardTab.PROFILES -> ProfilesPage(
                modifier = Modifier.padding(innerPadding),
                snapshot = snapshot,
                onRefresh = { refresh() }
            )
        }
    }

    if (linkDialogVisible) {
        AlertDialog(
            onDismissRequest = { linkDialogVisible = false },
            title = { Text("导入链接") },
            text = {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text("订阅 URL 或节点分享链接") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = linkText.isNotBlank(),
                    onClick = {
                        linkDialogVisible = false
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { importLink(context, linkText) }
                            }
                            showImportResult(result)
                        }
                    }
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { linkDialogVisible = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun HomePage(
    modifier: Modifier,
    snapshot: ProxyConfigStore.ConfigSnapshot,
    rootAvailable: Boolean?,
    vpnActive: Boolean,
    proxyRunning: Boolean,
    proxyStarting: Boolean,
    waitingForVpn: Boolean,
    retrying: Boolean,
    frameworkDetected: Boolean,
    runningTargets: Int,
    apiVersion: Int,
    onToggleProxy: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (proxyRunning) Icons.Default.CheckCircle
                    else if (waitingForVpn || retrying) Icons.Default.Warning
                    else Icons.Default.Info,
                    contentDescription = null,
                    tint = when {
                        proxyRunning -> MaterialTheme.colorScheme.primary
                        waitingForVpn || retrying -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            proxyRunning -> "代理运行中"
                            waitingForVpn -> "等待其他 VPN 退出"
                            retrying -> "等待 root 权限就绪"
                            else -> "代理未运行"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (waitingForVpn) "自动运行已启用，VPN 退出后会自动恢复"
                        else if (retrying) "自动运行已启用，root 就绪后会自动重试"
                        else if (snapshot.nodes.isEmpty()) "先在配置页导入订阅或配置文件"
                        else "${snapshot.nodes.size} 个节点，${snapshot.groups.size} 个代理组",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onToggleProxy,
                enabled = !proxyStarting && (proxyRunning || waitingForVpn || retrying || (snapshot.nodes.isNotEmpty() && rootAvailable != false && !vpnActive)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(if (proxyRunning || waitingForVpn || retrying) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.size(8.dp))
                Text(if (proxyStarting) "正在启动" else if (proxyRunning || waitingForVpn || retrying) "停止代理" else "启动代理")
            }
        }

        SectionCard {
            Text("运行条件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusLine(
                "root 权限",
                when {
                    rootAvailable == true -> "已获得"
                    retrying -> "等待授权就绪"
                    else -> "未获得"
                },
                rootAvailable == true
            )
            StatusLine(
                "其他 VPN",
                when {
                    waitingForVpn -> "已检测到，代理等待中"
                    vpnActive -> "已检测到"
                    else -> "未检测到"
                },
                !vpnActive && !waitingForVpn
            )
            StatusLine("配置", if (snapshot.nodes.isNotEmpty()) "已导入" else "未导入", snapshot.nodes.isNotEmpty())
        }

        SectionCard {
            Text("LSPosed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusLine("目标进程回调", if (runningTargets > 0) "$runningTargets 个进程" else "未加载", runningTargets > 0)
            StatusLine("框架", if (frameworkDetected) "API $apiVersion" else "未检测到", frameworkDetected)
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.size(8.dp))
                Text("刷新状态")
            }
        }

        SectionCard {
            Text("开源与免责声明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ListItem(
                headlineContent = { Text("GitHub 开源项目") },
                supportingContent = { Text("YOUTH8908/RootFlow") },
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_github), contentDescription = "GitHub")
                },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "打开链接") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/YOUTH8908/RootFlow")
                            )
                        )
                    }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("免责声明") },
                supportingContent = { Text("查看项目使用说明与责任范围") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "打开链接") },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/YOUTH8908/RootFlow/blob/main/DISCLAIMER.md")
                            )
                        )
                    }
            )
        }
    }
}

@Composable
private fun ProxyPage(
    modifier: Modifier,
    snapshot: ProxyConfigStore.ConfigSnapshot,
    onSelect: (String?) -> Unit
) {
    if (snapshot.nodes.isEmpty() && snapshot.groups.isEmpty()) {
        EmptyPage(modifier, "暂无代理节点", "先到配置页导入订阅、文件或节点链接")
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text("Google Proxy", style = MaterialTheme.typography.titleMedium)
                        Text(
                            snapshot.selectedProxy?.let { "当前节点：$it" } ?: "当前使用自动测速",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "选择后重新启动代理生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ListItem(
                    headlineContent = { Text("自动测速") },
                    supportingContent = { Text("由 url-test 自动选择最快节点") },
                    leadingContent = { RadioButton(snapshot.selectedProxy == null, onClick = { onSelect(null) }) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (snapshot.nodes.isNotEmpty()) {
            item { Text("节点", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(snapshot.nodes, key = { it.name }) { node ->
                ListItem(
                    headlineContent = { Text(node.name) },
                    supportingContent = { Text(listOf(node.type, node.server).filter(String::isNotBlank).joinToString(" · ")) },
                    leadingContent = {
                        RadioButton(snapshot.selectedProxy == node.name, onClick = { onSelect(node.name) })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider()
            }
        }
        if (snapshot.groups.isNotEmpty()) {
            item { Text("代理组", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(snapshot.groups.filter { it.name != "Google Proxy" }, key = { it.name }) { group ->
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = { Text("${group.type} · ${group.members.size} 个成员") },
                    leadingContent = { Icon(Icons.Default.Router, null) }
                )
            }
        }
    }
}

@Composable
private fun ProfilesPage(
    modifier: Modifier,
    snapshot: ProxyConfigStore.ConfigSnapshot,
    onRefresh: () -> Unit
) {
    if (snapshot.nodes.isEmpty() && snapshot.groups.isEmpty()) {
        EmptyPage(modifier, "暂无配置", "点击右上角加号，从二维码图片、文件或链接导入")
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text("当前配置", style = MaterialTheme.typography.titleMedium)
                    Text(snapshot.source.ifBlank { "本地配置" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            StatusLine("节点", "${snapshot.nodes.size} 个", true)
            StatusLine("代理组", "${snapshot.groups.size} 个", true)
            snapshot.selectedProxy?.let { StatusLine("已选择", it, true) }
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.size(8.dp))
            Text("刷新配置")
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            value,
            color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyPage(modifier: Modifier, title: String, message: String) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Info, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private suspend fun importLink(context: Context, text: String): ProxyConfigStore.ImportSummary {
    val value = text.trim()
    val scheme = ProxyConfigStore.schemeOf(value)
    return if (scheme in setOf("http", "https", "clash", "clashmeta", "ss", "ssr", "vmess", "vless", "trojan", "hysteria", "hysteria2", "hy2", "tuic", "socks", "socks5", "socks5h", "anytls", "mieru", "mierus")) {
        ProxyConfigStore.importUrl(context, value)
    } else {
        ProxyConfigStore.importText(context, value)
    }
}

private suspend fun importQrImage(context: Context, uri: Uri): ProxyConfigStore.ImportSummary {
    val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        ?: throw IllegalArgumentException("无法读取二维码图片")
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val text = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    return importLink(context, text)
}

private object IntentFactory {
    fun start(context: Context) = android.content.Intent(context, ProxyCoreService::class.java)
        .setAction(ProxyCoreService.ACTION_START)

    fun stop(context: Context) = android.content.Intent(context, ProxyCoreService::class.java)
        .setAction(ProxyCoreService.ACTION_STOP)
}
