package com.eta.bigbang.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.graphics.Brush
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
                    onChange = commit,
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

            item { EngineCard(cfg, onChange = commit) }

            item { BrowserCard(cfg, browsers, onChange = commit) }

            item { DetailCard(cfg) }

            item { AdvancedCard(cfg, onChange = commit) }

            item { FooterCard(cfg) }
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

/* ---------------- 顶部主卡 ---------------- */

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
                BlastBadge(cfg.enableHook)
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
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${cfg.maxBlocks} 块上限 · ${SearchEngines.byId(cfg.searchEngineId).name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ReadyDot(cfg.enableHook)
            }
            // 开关放右上
            Switch(
                checked = cfg.enableHook,
                onCheckedChange = onToggle,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun BlastBadge(enabled: Boolean) {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(enabled) {
        pulse.snapTo(0.82f)
        pulse.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = enabled,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "badge",
        ) { on ->
            Text(
                if (on) "爆" else "停",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ReadyDot(enabled: Boolean) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1400), repeatMode = RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { this.alpha = if (enabled) alpha else 0.3f }
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    CircleShape,
                )
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (enabled) "运行中" else "已停止",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("生效范围", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(8.dp))
            when (cfg.scopeMode) {
                Config.MODE_WHITELIST -> FilledTonalButton(onClick = onOpenPicker) {
                    Text("选择应用")
                }
                Config.MODE_RECOMMENDED -> FilledTonalButton(onClick = onCopyRecommended) {
                    Text("复制成我的白名单")
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ModeRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else Color.Transparent,
        animationSpec = tween(220),
        label = "modeBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleChip(if (selected) "✓" else "", selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CircleChip(text: String, selected: Boolean) {
    Box(
        Modifier
            .size(32.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.ifEmpty { "·" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------------- 搜索引擎（下拉） ---------------- */

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

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("搜索引擎", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

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
                    shape = RoundedCornerShape(18.dp),
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
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = cfg.searchTemplate,
                    onValueChange = { onChange(cfg.copy(searchTemplate = it)) },
                    label = { Text("URL 模板（{query} 占位）") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EngineItem(engine: SearchEngine, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else Color.Transparent,
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
        leadingIcon = { CircleChip(engine.name.take(1), selected) },
        trailingIcon = {
            if (selected) Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
            )
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

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("浏览器", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))

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
                    shape = RoundedCornerShape(18.dp),
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
                        BrowserItem(
                            label = "跟随系统默认",
                            selected = cfg.browserPkg.isBlank(),
                        ) {
                            onChange(cfg.copy(browserPkg = ""))
                            expanded = false
                        }
                        browsers.forEach { b ->
                            BrowserItem(
                                label = b.label,
                                selected = b.pkg == cfg.browserPkg,
                            ) {
                                onChange(cfg.copy(browserPkg = b.pkg))
                                expanded = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else Color.Transparent,
        animationSpec = tween(220),
        label = "browserBg",
    )
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        trailingIcon = {
            if (selected) Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = onClick,
        modifier = Modifier.background(bg),
    )
}

/* ---------------- 当前配置 ---------------- */

@Composable
private fun DetailCard(cfg: BigBangConfig) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("当前配置", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                DetailItem("生效范围", scopeLabel(cfg), Modifier.weight(1f))
                DetailItem("词块上限", cfg.maxBlocks.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                DetailItem("搜索引擎", SearchEngines.byId(cfg.searchEngineId).name, Modifier.weight(1f))
                DetailItem("点词复制", if (cfg.enableCopy) "开" else "关", Modifier.weight(1f))
            }
        }
    }
}

private fun scopeLabel(cfg: BigBangConfig): String = when (cfg.scopeMode) {
    Config.MODE_GLOBAL -> "全局"
    Config.MODE_RECOMMENDED -> "作者推荐"
    else -> if (cfg.whitelist.isEmpty()) "白名单 · 空" else "白名单 · ${cfg.whitelist.size}"
}

@Composable
private fun DetailItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "detail",
        ) { v ->
            Text(
                v,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------------- 高级选项 ---------------- */

@Composable
private fun AdvancedCard(cfg: BigBangConfig, onChange: (BigBangConfig) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text("高级选项", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = shrinkVertically(spring(dampingRatio = 0.8f)) + fadeOut(),
            ) {
                Column(Modifier.padding(top = 6.dp)) {
                    SwitchRow(
                        "点词时打开搜索",
                        "关闭后点击词块只复制，不跳转浏览器",
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

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = cfg.maxBlocks.toString(),
                        onValueChange = { s ->
                            s.toIntOrNull()?.let {
                                if (it in 10..1000) onChange(cfg.copy(maxBlocks = it))
                            }
                        },
                        label = { Text("最大词块数 (10-1000)") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
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
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/* ---------------- 底部说明 ---------------- */

@Composable
private fun FooterCard(cfg: BigBangConfig) {
    val ctx = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("生效步骤", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. LSPosed 启用模块，作用域勾选目标应用\n" +
                    "2. 改完配置后强行停止目标应用再打开\n" +
                    "3. 长按任意文字即可炸词\n" +
                    "4. 首次使用需允许「显示在其他应用上层」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "提示：模块内的生效范围只做额外收窄。" +
                    "白名单留空时，LSPosed 作用域内的应用全部生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = {
                    val intent = Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                },
            ) {
                Text("打开悬浮窗权限设置")
            }
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
