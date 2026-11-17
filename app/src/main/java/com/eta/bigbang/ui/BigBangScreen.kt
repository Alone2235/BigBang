package com.eta.bigbang.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eta.bigbang.BigBangConfig
import com.eta.bigbang.Config
import com.eta.bigbang.RecommendedApps
import com.eta.bigbang.SearchEngines
import com.eta.bigbang.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 已安装应用条目 */
data class AppEntry(val pkg: String, val label: String, val isSystem: Boolean)

@Composable
fun BigBangScreen() {
    val ctx = LocalContext.current

    var cfg by remember { mutableStateOf(Settings.loadFromUi(ctx)) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showEnginePicker by remember { mutableStateOf(false) }
    var showBrowserPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun persist(next: BigBangConfig) {
        cfg = next
        Settings.saveFromUi(ctx, next)
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
    }

    val browsers = remember(apps) { apps.filter { isBrowser(ctx, it.pkg) } }

    Scaffold { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("大爆炸", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "长按文本 → 分词成块 → 点词搜索 / 长按复制",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                StatusCard(cfg = cfg, onToggle = { persist(cfg.copy(enableHook = it)) })
            }

            item {
                SectionCard(title = "生效范围") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = cfg.scopeMode == Config.MODE_WHITELIST,
                            onClick = { persist(cfg.copy(scopeMode = Config.MODE_WHITELIST)) },
                            label = { Text("白名单") },
                        )
                        FilterChip(
                            selected = cfg.scopeMode == Config.MODE_GLOBAL,
                            onClick = { persist(cfg.copy(scopeMode = Config.MODE_GLOBAL)) },
                            label = { Text("全局") },
                        )
                        FilterChip(
                            selected = cfg.scopeMode == Config.MODE_RECOMMENDED,
                            onClick = { persist(cfg.copy(scopeMode = Config.MODE_RECOMMENDED)) },
                            label = { Text("作者推荐") },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (cfg.scopeMode) {
                            Config.MODE_GLOBAL -> "所有应用生效（自动跳过系统界面、电话、设置等敏感页）"
                            Config.MODE_RECOMMENDED -> "作者精选阅读 / 聊天 / 资讯类应用，共 ${RecommendedApps.PACKAGES.size} 个"
                            else -> "仅在勾选的应用中生效，当前已选 ${cfg.whitelist.size} 个"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (cfg.scopeMode == Config.MODE_WHITELIST) {
                        OutlinedButton(onClick = { showAppPicker = true }) { Text("选择应用") }
                    }
                    if (cfg.scopeMode == Config.MODE_RECOMMENDED) {
                        OutlinedButton(onClick = {
                            persist(
                                cfg.copy(
                                    scopeMode = Config.MODE_WHITELIST,
                                    whitelist = RecommendedApps.PACKAGES.toSet(),
                                )
                            )
                        }) { Text("复制成我的白名单") }
                    }
                }
            }

            item {
                SectionCard(title = "搜索") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("点词时打开搜索", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                SearchEngines.byId(cfg.searchEngineId).name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cfg.enableSearch,
                            onCheckedChange = { persist(cfg.copy(enableSearch = it)) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { showEnginePicker = true }) { Text("搜索引擎") }
                    if (cfg.searchEngineId == "custom") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cfg.searchTemplate,
                            onValueChange = { persist(cfg.copy(searchTemplate = it)) },
                            label = { Text("URL 模板（用 {query} 占位）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("使用指定浏览器", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (cfg.browserPkg.isBlank()) "跟随系统默认"
                                else apps.firstOrNull { it.pkg == cfg.browserPkg }?.label ?: cfg.browserPkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { showBrowserPicker = true }) { Text("选择") }
                    }
                }
            }

            item {
                SectionCard(title = "行为") {
                    ToggleRow(
                        title = "点词同时复制",
                        desc = "点击词块时把该词写入剪贴板",
                        checked = cfg.enableCopy,
                        onChange = { persist(cfg.copy(enableCopy = it)) },
                    )
                    ToggleRow(
                        title = "触发时振动",
                        desc = "长按炸词时轻微振动反馈",
                        checked = cfg.enableVibrate,
                        onChange = { persist(cfg.copy(enableVibrate = it)) },
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = cfg.maxBlocks.toString(),
                        onValueChange = { s ->
                            s.toIntOrNull()?.let { if (it in 10..1000) persist(cfg.copy(maxBlocks = it)) }
                        },
                        label = { Text("最大词块数 (10-1000)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Text(
                    "改动即时保存；在 LSPosed 中勾选作用域后，重启目标应用生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            loading = loading,
            selected = cfg.whitelist,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onDismiss = { showAppPicker = false },
            onConfirm = { set ->
                persist(cfg.copy(whitelist = set))
                showAppPicker = false
            },
        )
    }

    if (showEnginePicker) {
        ChoiceDialog(
            title = "搜索引擎",
            options = SearchEngines.ALL.map { it.id to it.name },
            selected = cfg.searchEngineId,
            onDismiss = { showEnginePicker = false },
            onPick = { id ->
                val tpl = if (id == "custom") cfg.searchTemplate else SearchEngines.byId(id).template
                persist(cfg.copy(searchEngineId = id, searchTemplate = tpl))
                showEnginePicker = false
            },
        )
    }

    if (showBrowserPicker) {
        val opts = listOf("" to "跟随系统默认") + browsers.map { it.pkg to it.label }
        ChoiceDialog(
            title = "选择浏览器",
            options = opts,
            selected = cfg.browserPkg,
            onDismiss = { showBrowserPicker = false },
            onPick = { pkg ->
                persist(cfg.copy(browserPkg = pkg))
                showBrowserPicker = false
            },
        )
    }
}

@Composable
private fun StatusCard(cfg: BigBangConfig, onToggle: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(
                    if (cfg.enableHook) Color(0xFF25C06B) else Color(0xFF8A8F98),
                    CircleShape,
                )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (cfg.enableHook) "模块已启用" else "模块已停用",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "记得在 LSPosed 中勾选作用域应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
            Switch(checked = cfg.enableHook, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 读取已安装应用 */
private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val self = ctx.packageName
    return runCatching {
        pm.getInstalledPackages(PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .asSequence()
            .filter { it.packageName != self }
            .map { pi ->
                val appInfo = pi.applicationInfo
                val sys = (appInfo?.flags ?: 0) and
                    (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val label = if (appInfo != null) {
                    runCatching { pm.getApplicationLabel(appInfo).toString() }
                        .getOrDefault(pi.packageName)
                } else pi.packageName
                AppEntry(
                    pkg = pi.packageName,
                    label = label,
                    isSystem = sys,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}

/** 是否可响应 https 的浏览器 */
private fun isBrowser(ctx: Context, pkg: String): Boolean = runCatching {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .any { it.activityInfo.packageName == pkg }
}.getOrDefault(false)
