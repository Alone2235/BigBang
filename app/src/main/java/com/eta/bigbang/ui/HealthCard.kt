package com.eta.bigbang.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eta.bigbang.CheckItem
import com.eta.bigbang.HealthCheck
import com.eta.bigbang.ui.component.SurfaceCard
import com.eta.bigbang.ui.theme.DangerRed
import com.eta.bigbang.ui.theme.HyperBlue
import com.eta.bigbang.ui.theme.Radius
import com.eta.bigbang.ui.theme.Space
import com.eta.bigbang.ui.theme.SuccessGreen
import com.eta.bigbang.ui.theme.TextAlpha
import com.eta.bigbang.ui.theme.WarnOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 授权与环境检测面板。
 * 三种状态：通过 / 待处理 / 无法探测（缺 Root 时如实标注，不谎报未安装）。
 */
@Composable
fun HealthCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CheckItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(true) }

    fun refresh() {
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { HealthCheck.run(ctx, ctx.packageName) }
            items = r
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val failCount = items.count { !it.ok && !it.unknown }
    val unknownCount = items.count { it.unknown }
    val allOk = items.isNotEmpty() && items.all { it.ok }

    SurfaceCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(ok = allOk, unknown = unknownCount > 0, pending = loading)
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        "运行环境",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = TextAlpha.secondary),
                    )
                    Text(
                        when {
                            loading -> "检测中…"
                            allOk -> "全部就绪"
                            else -> buildString {
                                if (failCount > 0) append("$failCount 项待处理")
                                if (failCount > 0 && unknownCount > 0) append(" · ")
                                if (unknownCount > 0) append("$unknownCount 项无法探测")
                            }
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!loading) {
                    FilledTonalButton(
                        onClick = { refresh() },
                        shape = RoundedCornerShape(Radius.pill),
                    ) { Text("重新检测", style = MaterialTheme.typography.labelSmall) }
                }
            }

            Spacer(Modifier.height(Space.xs))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.small))
                    .clickable { expanded = !expanded }
                    .padding(vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEach { item ->
                        CheckRow(item) {
                            when (item.key) {
                                "overlay" -> ctx.startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${ctx.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                "notify" -> ctx.startActivity(
                                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                "registered", "lsposed" ->
                                    ctx.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                                        ?.let {
                                            ctx.startActivity(
                                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem, onFix: () -> Unit) {
    val dotColor = when {
        item.ok -> SuccessGreen
        item.unknown -> WarnOrange
        else -> DangerRed
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(dotColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    item.ok -> Icons.Rounded.Check
                    item.unknown -> Icons.Rounded.QuestionMark
                    else -> Icons.Rounded.Close
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.ok) FontWeight.Normal else FontWeight.Medium,
            )
            Text(
                if (item.ok) item.desc else "${item.detail} · ${item.desc}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = TextAlpha.secondary),
            )
        }
        AnimatedVisibility(visible = !item.ok && !item.unknown) {
            FilledTonalButton(
                onClick = onFix,
                shape = RoundedCornerShape(Radius.pill),
            ) { Text("去处理", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun StatusOrb(ok: Boolean, unknown: Boolean, pending: Boolean) {
    // 用静态色块代替无限循环动画：无限动画会让静止页面持续占用主线程，
    // 在没有内容变化时纯属浪费。
    val color = when {
        pending -> WarnOrange
        ok -> SuccessGreen
        unknown -> WarnOrange
        else -> DangerRed
    }
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.30f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when {
                ok -> Icons.Rounded.Check
                unknown -> Icons.Rounded.QuestionMark
                else -> Icons.Rounded.Close
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
    }
}
