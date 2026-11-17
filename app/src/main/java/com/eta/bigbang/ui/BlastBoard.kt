package com.eta.bigbang.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eta.bigbang.BigBangConfig
import com.eta.bigbang.Config
import com.eta.bigbang.RecommendedApps
import com.eta.bigbang.SearchEngines
import com.eta.bigbang.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val isSystem: Boolean)

@Composable
fun BlastBoard() {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf(Settings.loadFromUi(ctx)) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showEnginePicker by remember { mutableStateOf(false) }
    var showBrowserPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun commit(next: BigBangConfig) {
        cfg = next
        Settings.saveFromUi(ctx, next)
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
    }

    val browsers = remember(apps) { apps.filter { isBrowser(ctx, it.pkg) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { HeaderCard(cfg) { commit(cfg.copy(enableHook = it)) } }

            item {
                ScopeCard(
                    cfg = cfg,
                    onChange = ::commit,
                    onOpenPicker = { showAppPicker = true },
                    onCopyRecommended = {
                        commit(
                            cfg.copy(
                                scopeMode = Config.MODE_WHITELIST,
                                whitelist = RecommendedApps.PACKAGES.toSet(),
                            )
                        )
                        scope.launch {
                            snackbar.showSnackbar("已复制 ${RecommendedApps.PACKAGES.size} 个应用到白名单")
                        }
                    },
                )
            }

            item {
                SearchCard(
                    cfg = cfg,
                    apps = apps,
                    onChange = ::commit,
                    onPickEngine = { showEnginePicker = true },
                    onPickBrowser = { showBrowserPicker = true },
                )
            }

            item { BehaviorCard(cfg, ::commit) }

            item { FooterCard() }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps, loading = loading, selected = cfg.whitelist,
            query = searchQuery, onQueryChange = { searchQuery = it },
            onDismiss = { showAppPicker = false },
            onConfirm = { set ->
                commit(cfg.copy(whitelist = set))
                showAppPicker = false
                scope.launch { snackbar.showSnackbar("已选 ${set.size} 个，重启目标应用后生效") }
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
                commit(cfg.copy(searchEngineId = id, searchTemplate = tpl))
                showEnginePicker = false
            },
        )
    }

    if (showBrowserPicker) {
        ChoiceDialog(
            title = "选择浏览器",
            options = listOf("" to "跟随系统默认") + browsers.map { it.pkg to it.label },
            selected = cfg.browserPkg,
            onDismiss = { showBrowserPicker = false },
            onPick = { pkg ->
                commit(cfg.copy(browserPkg = pkg))
                showBrowserPicker = false
            },
        )
    }
}

/* ---------------- 顶部渐变状态卡 ---------------- */

@Composable
private fun HeaderCard(cfg: BigBangConfig, onToggle: (Boolean) -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }

    Card(
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 64f
        },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    )
                )
            ).padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(
                        if (cfg.enableHook) Color(0xFF25C06B) else Color(0xFF8A8F98)
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (cfg.enableHook) "开" else "关",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "长按炸词",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AnimatedContent(
                        targetState = cfg.enableHook,
                        transitionSpec = {
                            (slideInVertically(spring(dampingRatio = 0.8f)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(spring(dampingRatio = 0.8f)) { -it / 2 } + fadeOut(tween(140)))
                        },
                        label = "status",
                    ) { enabled ->
                        Text(
                            if (enabled) "已启用" else "已停用",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "分词成块后可搜索 / 复制",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cfg.enableHook, onCheckedChange = onToggle)
            }
        }
    }
}

/* ---------------- 生效范围 ---------------- */

@Composable
private fun ScopeCard(
    cfg: BigBangConfig,
    onChange: (BigBangConfig) -> Unit,
    onOpenPicker: () -> Unit,
    onCopyRecommended: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("生效范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = cfg.scopeMode == Config.MODE_WHITELIST,
                    onClick = { onChange(cfg.copy(scopeMode = Config.MODE_WHITELIST)) },
                    label = { Text("白名单") },
                    leadingIcon = if (cfg.scopeMode == Config.MODE_WHITELIST) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                    } else null,
                )
                FilterChip(
                    selected = cfg.scopeMode == Config.MODE_GLOBAL,
                    onClick = { onChange(cfg.copy(scopeMode = Config.MODE_GLOBAL)) },
                    label = { Text("全局") },
                )
                FilterChip(
                    selected = cfg.scopeMode == Config.MODE_RECOMMENDED,
                    onClick = { onChange(cfg.copy(scopeMode = Config.MODE_RECOMMENDED)) },
                    label = { Text("推荐") },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                when (cfg.scopeMode) {
                    Config.MODE_GLOBAL -> "所有应用生效，自动跳过系统界面与敏感页"
                    Config.MODE_RECOMMENDED -> "作者精选 ${RecommendedApps.PACKAGES.size} 个阅读 / 聊天 / 资讯应用"
                    else -> "仅在勾选的应用中生效，已选 ${cfg.whitelist.size} 个"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            when (cfg.scopeMode) {
                Config.MODE_WHITELIST ->
                    OutlinedButton(onClick = onOpenPicker) { Text("选择应用") }
                Config.MODE_RECOMMENDED ->
                    OutlinedButton(onClick = onCopyRecommended) { Text("复制成我的白名单") }
                else -> Unit
            }
        }
    }
}

/* ---------------- 搜索 ---------------- */

@Composable
private fun SearchCard(
    cfg: BigBangConfig,
    apps: List<AppEntry>,
    onChange: (BigBangConfig) -> Unit,
    onPickEngine: () -> Unit,
    onPickBrowser: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("搜索与浏览器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

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
                    onCheckedChange = { onChange(cfg.copy(enableSearch = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPickEngine) { Text("搜索引擎") }

            if (cfg.searchEngineId == "custom") {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = cfg.searchTemplate,
                    onValueChange = { onChange(cfg.copy(searchTemplate = it)) },
                    label = { Text("URL 模板（{query} 占位）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

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
                OutlinedButton(onClick = onPickBrowser) { Text("选择") }
            }
        }
    }
}

/* ---------------- 行为 ---------------- */

@Composable
private fun BehaviorCard(cfg: BigBangConfig, onChange: (BigBangConfig) -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("行为", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))

            ToggleRow("点词同时复制", "点击词块时把该词写入剪贴板", cfg.enableCopy) {
                onChange(cfg.copy(enableCopy = it))
            }
            ToggleRow("触发时振动", "长按炸词时轻微振动反馈", cfg.enableVibrate) {
                onChange(cfg.copy(enableVibrate = it))
            }

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = cfg.maxBlocks.toString(),
                onValueChange = { s ->
                    s.toIntOrNull()?.let { if (it in 10..1000) onChange(cfg.copy(maxBlocks = it)) }
                },
                label = { Text("最大词块数 (10-1000)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
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

@Composable
private fun FooterCard() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("提示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "改动即时保存。在 LSPosed 中勾选作用域后，需重启目标应用才会生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------------- 数据 ---------------- */

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val self = ctx.packageName
    return runCatching {
        pm.getInstalledPackages(PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .asSequence()
            .filter { it.packageName != self }
            .map { pi ->
                val ai = pi.applicationInfo
                val sys = (ai?.flags ?: 0) and
                    (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val label = if (ai != null) {
                    runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pi.packageName)
                } else pi.packageName
                AppEntry(pi.packageName, label, sys)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}

private fun isBrowser(ctx: Context, pkg: String): Boolean = runCatching {
    val pm = ctx.packageManager
    pm.queryIntentActivities(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
        PackageManager.MATCH_ALL,
    ).any { it.activityInfo.packageName == pkg }
}.getOrDefault(false)
