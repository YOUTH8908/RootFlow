package com.example.clashforlsposed.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.clashforlsposed.LsposedStatus
import com.example.clashforlsposed.R
import com.example.clashforlsposed.proxy.ProxyConfigStore
import com.example.clashforlsposed.proxy.ProxyCoreService
import com.example.clashforlsposed.proxy.ProxyRuntimeState
import com.example.clashforlsposed.proxy.NetworkStatus
import com.example.clashforlsposed.proxy.RootShell
import com.example.clashforlsposed.ui.theme.ClashForLSPosedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LspStatusScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var refreshToken by remember { mutableLongStateOf(0L) }
    var subscriptionUrl by rememberSaveable { mutableStateOf("") }
    val rootAvailable = remember(refreshToken) { ProxyRuntimeState.rootAvailable }
    val vpnActive = remember(refreshToken) { NetworkStatus.hasActiveVpn(context) }
    val frameworkDetected = remember(refreshToken) { LsposedStatus.isConnected() }
    val runningTargets = remember(refreshToken) { LsposedStatus.runningTargets() }
    val moduleScope = remember(refreshToken) { LsposedStatus.scope() }
    val isActive = runningTargets.isNotEmpty()
    val apiVersion = remember(refreshToken) { LsposedStatus.apiVersion() }
    val frameworkName = remember(refreshToken) { LsposedStatus.frameworkName() }
    val frameworkVersion = remember(refreshToken) { LsposedStatus.frameworkVersion() }
    val frameworkVersionCode = remember(refreshToken) { LsposedStatus.frameworkVersionCode() }
    val proxyRunning = remember(refreshToken) { ProxyRuntimeState.running }
    val proxyStarting = remember(refreshToken) { ProxyRuntimeState.starting }
    val waitingForVpn = remember(refreshToken) { ProxyRuntimeState.waitingForVpn }
    val retrying = remember(refreshToken) { ProxyRuntimeState.retrying }
    val hasConfig = remember(refreshToken) { ProxyConfigStore.hasConfig(context) }
    val proxyMessage = remember(refreshToken) { ProxyRuntimeState.message }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ProxyConfigStore.importUri(context, uri) }
            }
            snackbarHostState.showSnackbar(
                result.fold(
                    onSuccess = { it.successMessage() },
                    onFailure = { it.message ?: "配置导入失败" }
                )
            )
            refreshToken++
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ProxyRuntimeState.rootAvailable = RootShell.isAvailable()
            (context.applicationContext as? com.example.clashforlsposed.ClashApplication)
                ?.refreshFrameworkState()
        }
        refreshToken++
        while (true) {
            delay(1_500)
            withContext(Dispatchers.IO) {
                (context.applicationContext as? com.example.clashforlsposed.ClashApplication)
                    ?.refreshFrameworkState()
            }
            refreshToken++
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.lsp_status_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(isActive = isActive)

            GoogleProxyCard(
                rootAvailable = rootAvailable,
                vpnActive = vpnActive,
                proxyRunning = proxyRunning,
                proxyStarting = proxyStarting,
                waitingForVpn = waitingForVpn,
                retrying = retrying,
                hasConfig = hasConfig,
                proxyMessage = proxyMessage,
                subscriptionUrl = subscriptionUrl,
                onSubscriptionUrlChange = { subscriptionUrl = it },
                onImportFile = {
                    fileLauncher.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "*/*"))
                },
                onImportUrl = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { ProxyConfigStore.importUrl(context, subscriptionUrl) }
                        }
                        snackbarHostState.showSnackbar(
                            result.fold(
                                onSuccess = { it.successMessage() },
                                onFailure = { it.message ?: "订阅导入失败" }
                            )
                        )
                        refreshToken++
                    }
                },
                onToggle = {
                    if (proxyRunning || waitingForVpn || retrying) {
                        context.startService(
                            Intent(context, ProxyCoreService::class.java)
                                .setAction(ProxyCoreService.ACTION_STOP)
                        )
                    } else {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, ProxyCoreService::class.java)
                                .setAction(ProxyCoreService.ACTION_START)
                        )
                    }
                    refreshToken++
                }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.lsp_check_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CheckRow(
                        label = stringResource(R.string.lsp_check_module),
                        value = if (isActive) {
                            stringResource(R.string.lsp_targets_active, runningTargets.size)
                        } else stringResource(R.string.lsp_inactive),
                        positive = isActive
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    CheckRow(
                        label = stringResource(R.string.lsp_scope),
                        value = if (moduleScope.isEmpty()) {
                            stringResource(R.string.lsp_scope_empty)
                        } else {
                            stringResource(R.string.lsp_scope_count, moduleScope.size)
                        },
                        positive = moduleScope.isNotEmpty()
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    CheckRow(
                        label = stringResource(R.string.lsp_check_framework),
                        value = if (frameworkDetected && apiVersion > 0) {
                            stringResource(R.string.lsp_api_version, apiVersion)
                        } else if (frameworkDetected) stringResource(R.string.lsp_detected)
                        else stringResource(R.string.lsp_not_detected),
                        positive = frameworkDetected
                    )
                    if (frameworkDetected && frameworkName.isNotBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        CheckRow(
                            label = stringResource(R.string.lsp_framework_version),
                            value = stringResource(
                                R.string.lsp_framework_version_value,
                                frameworkName,
                                frameworkVersion,
                                frameworkVersionCode
                            ),
                            positive = true
                        )
                    }
                }
            }

            Text(
                text = when {
                    isActive -> stringResource(R.string.lsp_active_help)
                    frameworkDetected -> stringResource(R.string.lsp_no_targets_help)
                    else -> stringResource(R.string.lsp_inactive_help)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            (context.applicationContext as? com.example.clashforlsposed.ClashApplication)
                                ?.refreshFrameworkState()
                        }
                        refreshToken++
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.lsp_refresh))
            }

            Text(
                text = context.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GoogleProxyCard(
    rootAvailable: Boolean?,
    vpnActive: Boolean,
    proxyRunning: Boolean,
    proxyStarting: Boolean,
    waitingForVpn: Boolean,
    retrying: Boolean,
    hasConfig: Boolean,
    proxyMessage: String,
    subscriptionUrl: String,
    onSubscriptionUrlChange: (String) -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: () -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.google_proxy_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.google_proxy_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CheckRow(
                label = stringResource(R.string.google_proxy_root),
                value = when {
                    retrying -> stringResource(R.string.google_proxy_root_waiting)
                    rootAvailable == true -> stringResource(R.string.google_proxy_root_ready)
                    rootAvailable == false -> stringResource(R.string.google_proxy_root_missing)
                    else -> stringResource(R.string.google_proxy_checking)
                },
                positive = rootAvailable == true && !retrying
            )
            CheckRow(
                label = stringResource(R.string.google_proxy_config),
                value = if (hasConfig) stringResource(R.string.google_proxy_config_ready)
                else stringResource(R.string.google_proxy_config_missing),
                positive = hasConfig
            )
            CheckRow(
                label = stringResource(R.string.google_proxy_existing_vpn),
                value = if (waitingForVpn) stringResource(R.string.google_proxy_vpn_waiting)
                else if (vpnActive) stringResource(R.string.google_proxy_vpn_detected)
                else stringResource(R.string.google_proxy_vpn_clear),
                positive = !vpnActive && !waitingForVpn
            )
            OutlinedTextField(
                value = subscriptionUrl,
                onValueChange = onSubscriptionUrlChange,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                label = { Text(stringResource(R.string.google_proxy_subscription_label)) },
                placeholder = { Text("https://example.com/clash.yaml") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onImportFile, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.google_proxy_import_file))
                }
                Button(
                    onClick = onImportUrl,
                    enabled = subscriptionUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.google_proxy_import_url))
                }
            }
            Button(
                onClick = onToggle,
                enabled = !proxyStarting && (proxyRunning || waitingForVpn || retrying || (hasConfig && rootAvailable != false && !vpnActive)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (proxyStarting) stringResource(R.string.google_proxy_starting)
                    else if (proxyRunning || waitingForVpn || retrying) stringResource(R.string.google_proxy_stop)
                    else stringResource(R.string.google_proxy_start)
                )
            }
            if (proxyMessage.isNotBlank()) {
                Text(
                    text = proxyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusCard(isActive: Boolean) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(40.dp)
            )
            Column {
                Text(
                    text = if (isActive) stringResource(R.string.lsp_active)
                    else stringResource(R.string.lsp_inactive),
                    style = MaterialTheme.typography.headlineSmall,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isActive) stringResource(R.string.lsp_active_summary)
                    else stringResource(R.string.lsp_inactive_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, value: String, positive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            color = if (positive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LspStatusPreview() {
    ClashForLSPosedTheme {
        LspStatusScreen()
    }
}
