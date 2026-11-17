package com.eta.bigbang.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eta.bigbang.BigBangConfig
import com.eta.bigbang.Config
import com.eta.bigbang.RecommendedApps
import com.eta.bigbang.SearchEngine
import com.eta.bigbang.SearchEngines
import com.eta.bigbang.Settings
import com.eta.bigbang.ui.component.AccentCard
import com.eta.bigbang.ui.component.CardStyle
import com.eta.bigbang.ui.component.SurfaceCard
import com.eta.bigbang.ui.component.pressScale
import com.eta.bigbang.ui.theme.AppBackground
import com.eta.bigbang.ui.theme.HyperBlue
import com.eta.bigbang.ui.theme.Radius
import com.eta.bigbang.ui.theme.Space
import com.eta.bigbang.ui.theme.SuccessGreen
import com.eta.bigbang.ui.theme.TextAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val isSystem: Boolean)

@Composable
fun LargeShatterScreen() {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(Settings.loadFromUi(ctx)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }

    fun commit(next: BigBangConfig) {
        cfg = next
        Settings.saveFromUi(ctx, next)
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
    }

    val browsers = remember(apps) { apps.filter { isBrowser(ctx, it.pkg) } }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = Space.page, end = Space.page,
                    top = Space.xs, bottom = Space.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.gap),
            ) {
                item {
                    Text(
                        "大爆炸",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = Space.sm, bottom = Space.xxs),
                    )
                }

                item { StatusCard(cfg) { commit(cfg.copy(enableHook = it)) } }

                item { HealthCard() }

                item {
                    ScopeCard(
                        cfg = cfg,
                        onChange = { commit(it) },
                        onOpenPicker = { showPicker = true },
                        onCopyRecommended = {
                            commit(
                                cfg.copy(
                                    scopeMode = Config.MODE_WHITELIST,
                                    whitelist = RecommendedApps.PACKAGES.toSet(),
                                )
                            )
                            scope.launch {
                                snackbar.showSnackbar("已带入 ${RecommendedApps.PACKAGES.size} 个推荐应用")
                            }
                        },
                    )
                }

                item { EngineCard(cfg, onChange = { commit(it) }) }
                item { BrowserCard(cfg, browsers, onChange = { commit(it) }) }
                item { AdvancedCard(cfg, onChange = { commit(it) }) }
                item { FooterCard() }
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps, loading = loading, selected = cfg.whitelist,
            query = "", onQueryChange = {},
            onDismiss = { showPicker = false },
            onConfirm = { set ->
                commit(cfg.copy(whitelist = set))
                showPicker = false
                scope.launch { snackbar.showSnackbar("已选 ${set.size} 个，重启目标应用后生效") }
            },
        )
    }
}

/* ---------------- 主状态卡 ---------------- */

@Composable
private fun StatusCard(cfg: BigBangConfig, onToggle: (Boolean) -> Unit) {
    AccentCard(accent = HyperBlue, radius = Radius.large) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlastOrb(cfg.enableHook)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    "长按炸词",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedContent(
                    targetState = cfg.enableHook,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "status",
                ) { enabled ->
                    Text(
                        if (enabled) "已启用" else "已停用",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(Space.xxs))
                Text(
                    "${cfg.maxBlocks} 块上限 · ${SearchEngines.byId(cfg.searchEngineId).name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = cfg.enableHook, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun BlastOrb(enabled: Boolean) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                if (enabled) SuccessGreen.copy(alpha = 0.14f)
                else CardStyle.subtle
            )
            .border(
                1.dp,
                if (enabled) SuccessGreen.copy(alpha = 0.30f) else CardStyle.stroke,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (enabled) "爆" else "停",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    SurfaceCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            SectionTitle("生效范围")
            Spacer(Modifier.height(Space.sm))

            ModeRow(
                selected = cfg.scopeMode == Config.MODE_WHITELIST,
                title = "白名单",
                desc = if (cfg.whitelist.isEmpty()) "未选择时全部放行"
                else "已选 ${cfg.whitelist.size} 个应用",
            ) { onChange(cfg.copy(scopeMode = Config.MODE_WHITELIST)) }

            ModeRow(
                selected = cfg.scopeMode == Config.MODE_GLOBAL,
                title = "全局",
                desc = "所有应用生效，自动跳过系统界面",
            ) { onChange(cfg.copy(scopeMode = Config.MODE_GLOBAL)) }

            ModeRow(
                selected = cfg.scopeMode == Config.MODE_RECOMMENDED,
                title = "作者推荐",
                desc = "精选 ${RecommendedApps.PACKAGES.size} 个阅读 / 聊天 / 资讯应用",
            ) { onChange(cfg.copy(scopeMode = Config.MODE_RECOMMENDED)) }

            Spacer(Modifier.height(Space.sm))
            when (cfg.scopeMode) {
                Config.MODE_WHITELIST ->
                    ActionButton("选择应用", onOpenPicker)
                Config.MODE_RECOMMENDED ->
                    ActionButton("复制成我的白名单", onCopyRecommended)
                else -> Unit
            }
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) HyperBlue.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(220),
        label = "modeBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.small))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected)
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = TextAlpha.secondary),
            )
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = HyperBlue)
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) HyperBlue else CardStyle.subtle)
            .border(
                1.dp,
                if (selected) HyperBlue else CardStyle.stroke,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.pill),
    ) { Text(text) }
}

/* ---------------- 搜索引擎 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineCard(cfg: BigBangConfig, onChange: (BigBangConfig) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val current = SearchEngines.byId(cfg.searchEngineId)

    val filtered = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) SearchEngines.ALL
        else SearchEngines.ALL.filter { it.name.contains(q, true) || it.id.contains(q, true) }
    }

    SurfaceCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            SectionTitle("搜索引擎")
            Spacer(Modifier.height(Space.sm))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("点词后使用") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.medium),
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .width(320.dp)
                        .heightIn(max = 420.dp)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索引擎") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.small),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.sm, vertical = Space.xs),
                    )
                    Column(Modifier.heightIn(max = 288.dp).verticalScroll(rememberScrollState())) {
                        filtered.forEach { opt ->
                            EngineItem(
                                engine = opt,
                                selected = opt.id == cfg.searchEngineId,
                                onClick = {
                                    val tpl = if (opt.id == "custom") cfg.searchTemplate else opt.template
                                    onChange(cfg.copy(searchEngineId = opt.id, searchTemplate = tpl))
                                    query = ""
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }

            if (cfg.searchEngineId == "custom") {
                Spacer(Modifier.height(Space.xs))
                OutlinedTextField(
                    value = cfg.searchTemplate,
                    onValueChange = { onChange(cfg.copy(searchTemplate = it)) },
                    label = { Text("URL 模板（{query} 占位）") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.medium),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EngineItem(engine: SearchEngine, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) HyperBlue.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(220),
        label = "engineBg",
    )
    DropdownMenuItem(
        text = {
            Text(
                engine.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        leadingIcon = {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (selected) HyperBlue else CardStyle.subtle),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    engine.name.take(1),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingIcon = {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = HyperBlue)
        },
        onClick = onClick,
        modifier = Modifier.background(bg),
    )
}

/* ---------------- 浏览器 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserCard(
    cfg: BigBangConfig,
    browsers: List<AppEntry>,
    onChange: (BigBangConfig) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = if (cfg.browserPkg.isBlank()) "跟随系统默认"
    else browsers.firstOrNull { it.pkg == cfg.browserPkg }?.label ?: cfg.browserPkg

    SurfaceCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            SectionTitle("浏览器")
            Spacer(Modifier.height(Space.sm))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("打开搜索结果的浏览器") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.medium),
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .width(320.dp)
                        .heightIn(max = 380.dp)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        DropdownMenuItem(
                            text = { Text("跟随系统默认", style = MaterialTheme.typography.titleSmall) },
                            trailingIcon = {
                                if (cfg.browserPkg.isBlank()) {
                                    Icon(Icons.Rounded.Check, null, tint = HyperBlue)
                                }
                            },
                            onClick = {
                                onChange(cfg.copy(browserPkg = ""))
                                expanded = false
                            },
                        )
                        browsers.forEach { b ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        b.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (b.pkg == cfg.browserPkg) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                    )
                                },
                                trailingIcon = {
                                    if (b.pkg == cfg.browserPkg) {
                                        Icon(Icons.Rounded.Check, null, tint = HyperBlue)
                                    }
                                },
                                onClick = {
                                    onChange(cfg.copy(browserPkg = b.pkg))
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 高级选项 ---------------- */

@Composable
private fun AdvancedCard(cfg: BigBangConfig, onChange: (BigBangConfig) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    SurfaceCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.small))
                    .clickable { expanded = !expanded }
                    .padding(vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Space.sm))
                SectionTitle("高级选项")
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(top = Space.xs)) {
                    SwitchRow(
                        "点词时打开搜索",
                        "关闭后点击词块只复制，不跳转",
                        cfg.enableSearch,
                    ) { onChange(cfg.copy(enableSearch = it)) }
                    SwitchRow(
                        "点词同时复制",
                        "点击词块时把该词写入剪贴板",
                        cfg.enableCopy,
                    ) { onChange(cfg.copy(enableCopy = it)) }
                    SwitchRow(
                        "触发时振动",
                        "长按炸词时轻微振动反馈",
                        cfg.enableVibrate,
                    ) { onChange(cfg.copy(enableVibrate = it)) }

                    Spacer(Modifier.height(Space.xs))
                    OutlinedTextField(
                        value = cfg.maxBlocks.toString(),
                        onValueChange = { s ->
                            s.toIntOrNull()?.let {
                                if (it in 10..1000) onChange(cfg.copy(maxBlocks = it))
                            }
                        },
                        label = { Text("最大词块数 (10-1000)") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.medium),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = TextAlpha.secondary),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/* ---------------- 底部说明 ---------------- */

@Composable
private fun FooterCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.medium),
        color = HyperBlue.copy(alpha = 0.06f),
    ) {
        Column(Modifier.padding(Space.card)) {
            Text("生效步骤", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Space.xs))
            Text(
                "1. LSPosed 启用模块，作用域勾选目标应用\n" +
                    "2. 改完配置后强行停止目标应用再打开\n" +
                    "3. 长按任意文字即可炸词\n" +
                    "4. 首次使用需允许「显示在其他应用上层」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = TextAlpha.secondary),
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                "提示：模块内的生效范围只做额外收窄。白名单留空时，LSPosed 作用域内的应用全部生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = TextAlpha.secondary),
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
