package com.eta.bigbang

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/** 单项检测状态 */
data class CheckItem(
    val key: String,
    val title: String,
    val desc: String,
    val ok: Boolean,
    val unknown: Boolean = false,
    val detail: String = "",
)

/**
 * 环境与授权检测。
 *
 * 重要：模块 App 自身是普通应用，读不到 /data/adb/lspd/。
 * 因此所有涉及 LSPosed 的探测都必须经 su 执行；
 * su 不可用时标记为"无法探测"，而不是报成"未安装"。
 */
object HealthCheck {

    /** 用 su 执行命令，返回 stdout；失败返回 null */
    private fun su(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        val done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            p.destroy()
            return null
        }
        if (p.exitValue() == 0) out else null
    }.getOrNull()

    /** 无 su 时，能否直接读（少数环境下可达） */
    private fun canReadDirect(path: String): Boolean =
        runCatching { File(path).canRead() }.getOrDefault(false)

    fun hasRoot(): Boolean {
        // 直接看 su 可执行文件
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su",
        )
        if (suPaths.any { File(it).exists() }) return true

        // 用 su 实际验证一次
        val idOut = su("id -u")?.trim()
        return idOut == "0"
    }

    /** LSPosed 守护进程与配置状态 */
    private fun lsposedState(root: Boolean): CheckItem {
        val dbExists = if (root) {
            su("[ -f /data/adb/lspd/config/modules_config.db ] && echo yes || echo no")
                ?.trim() == "yes"
        } else {
            canReadDirect("/data/adb/lspd/config/modules_config.db")
        }

        if (!dbExists) {
            if (!root) {
                return CheckItem(
                    "lsposed", "LSPosed 运行状态",
                    "需要 Root 才能检测 LSPosed 目录",
                    ok = false, unknown = true, detail = "无法探测",
                )
            }
            return CheckItem(
                "lsposed", "LSPosed 运行状态",
                "未找到 /data/adb/lspd，确认已安装 LSPosed",
                ok = false, detail = "配置不存在",
            )
        }

        val alive = if (root) {
            su("ps -A 2>/dev/null | grep -c '{main} lspd'")?.trim()?.toIntOrNull()?.let { it > 0 }
                ?: false
        } else false

        return CheckItem(
            "lsposed", "LSPosed 运行状态",
            if (alive) "守护进程正常" else "守护进程未在运行，模块不会被注入",
            ok = alive,
            detail = if (alive) "运行中" else "未运行",
        )
    }

    /** 模块是否被 LSPosed 登记并启用 */
    private fun moduleRegistered(root: Boolean, pkg: String): CheckItem {
        if (!root) {
            return CheckItem(
                "registered", "模块已登记",
                "需要 Root 才能读取 LSPosed 数据库",
                ok = false, unknown = true, detail = "无法探测",
            )
        }

        val hit = su(
            "ls /data/adb/lspd/config/modules_config.db >/dev/null 2>&1 && " +
                "cat /data/adb/lspd/config/modules_config.db | grep -c '$pkg'"
        )?.trim()?.toIntOrNull() ?: -1

        return when {
            hit > 0 -> CheckItem(
                "registered", "模块已登记",
                "LSPosed 已识别本模块",
                ok = true, detail = "已登记",
            )
            hit == 0 -> CheckItem(
                "registered", "模块已登记",
                "LSPosed 尚未识别，尝试在管理器中刷新或重启",
                ok = false, detail = "未登记",
            )
            else -> CheckItem(
                "registered", "模块已登记",
                "读取数据库失败",
                ok = false, unknown = true, detail = "读取失败",
            )
        }
    }

    fun overlayGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx)
        else true

    fun notificationGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    /** 汇总检测结果 */
    fun run(ctx: Context, pkg: String): List<CheckItem> {
        val root = hasRoot()
        val overlay = overlayGranted(ctx)
        val notify = notificationGranted(ctx)

        return listOf(
            CheckItem(
                "root", "Root 权限",
                "读写 LSPosed 配置与系统目录需要 Root",
                ok = root,
                detail = if (root) "已获取" else "未获取",
            ),
            lsposedState(root),
            moduleRegistered(root, pkg),
            CheckItem(
                "overlay", "悬浮窗权限",
                "缺少此项时长按只会震动，不弹分词层",
                ok = overlay,
                detail = if (overlay) "已授予" else "未授予",
            ),
            CheckItem(
                "notify", "通知权限",
                "用于提示模块状态",
                ok = notify,
                detail = if (notify) "已授予" else "未授予",
            ),
        )
    }
}
