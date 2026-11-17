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
    val detail: String = "",
)

/**
 * 环境与授权检测。
 *
 * 全部为真实探测，不做乐观假设：读不到就是未通过。
 */
object HealthCheck {

    /** Root 是否可用（读 su 可执行 + 能否读到受保护目录） */
    fun hasRoot(): Boolean = runCatching {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su",
        )
        if (suPaths.any { File(it).exists() }) return@runCatching true
        // 退化判断：能读 /data/adb 说明已有高权
        File("/data/adb").canRead()
    }.getOrDefault(false)

    /** LSPosed 是否在运行：守护进程是否存活 + 配置文件是否存在 */
    fun lsposedState(): Pair<Boolean, String> {
        val dbExists = File("/data/adb/lspd/config/modules_config.db").exists() ||
            File("/data/adb/lspd/config/modules_config.xml").exists()
        if (!dbExists) return false to "未检测到 LSPosed 配置"

        // 找 lspd 进程
        val alive = runCatching {
            File("/proc").listFiles()
                ?.filter { it.isDirectory && it.name.all { c -> c.isDigit() } }
                ?.any { pid ->
                    runCatching {
                        File(pid, "cmdline").readText().contains("lspd")
                    }.getOrDefault(false)
                } ?: false
        }.getOrDefault(false)

        return alive to if (alive) "lspd 运行中" else "lspd 未运行"
    }

    /** 模块本身在 LSPosed 中是否被识别与启用 */
    fun moduleRegistered(pkg: String): Triple<Boolean, Boolean, String> {
        val db = File("/data/adb/lspd/config/modules_config.db")
        if (!db.exists()) return Triple(false, false, "无数据库")

        // 不引入 sqlite 依赖，用只读方式扫库文件里的包名。
        // 精确判断交给 LSPosed 管理器，这里只做"是否出现过"的粗判。
        val found = runCatching {
            db.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                val needle = pkg.toByteArray()
                var matched = false
                var carry = ByteArray(0)
                var read: Int
                while (ins.read(buf).also { read = it } > 0) {
                    val chunk = carry + buf.copyOf(read)
                    if (chunk.indexOf(needle) >= 0) { matched = true; break }
                    carry = chunk.takeLast(needle.size)
                }
                matched
            }
        }.getOrDefault(false)

        return Triple(found, found, if (found) "已登记" else "未登记")
    }

    /** 悬浮窗权限 */
    fun overlayGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx)
        else true

    /** 通知权限（Android 13+） */
    fun notificationGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    /** 汇总检测结果 */
    fun run(ctx: Context, pkg: String): List<CheckItem> {
        val root = hasRoot()
        val (lspdAlive, lspdDetail) = lsposedState()
        val (regFound, _, regDetail) = moduleRegistered(pkg)

        return listOf(
            CheckItem(
                key = "root",
                title = "Root 权限",
                desc = "模块需要 root 才能读写 LSPosed 配置与系统目录",
                ok = root,
                detail = if (root) "已获取" else "未获取",
            ),
            CheckItem(
                key = "lsposed",
                title = "LSPosed 运行状态",
                desc = "守护进程需在运行，模块才会被注入",
                ok = lspdAlive,
                detail = lspdDetail,
            ),
            CheckItem(
                key = "registered",
                title = "模块已登记",
                desc = "LSPosed 是否识别到本模块",
                ok = regFound,
                detail = regDetail,
            ),
            CheckItem(
                key = "overlay",
                title = "悬浮窗权限",
                desc = "缺少此项时长按只会震动，不弹分词层",
                ok = overlayGranted(ctx),
                detail = if (overlayGranted(ctx)) "已授予" else "未授予",
            ),
            CheckItem(
                key = "notify",
                title = "通知权限",
                desc = "用于提示模块状态",
                ok = notificationGranted(ctx),
                detail = if (notificationGranted(ctx)) "已授予" else "未授予",
            ),
        )
    }
}
