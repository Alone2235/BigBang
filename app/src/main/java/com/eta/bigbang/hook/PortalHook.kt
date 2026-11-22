package com.eta.bigbang.hook

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.eta.bigbang.BigBangConfig
import com.eta.bigbang.BlastOverlay
import com.eta.bigbang.Config
import com.eta.bigbang.Settings
import com.eta.bigbang.Shatter
import com.eta.bigbang.input.RootTouchSource
import com.eta.bigbang.input.ScreenTextReader

/**
 * 大爆炸主逻辑。
 *
 * 链路：
 *  1. RootTouchSource 读 /dev/input/event7 原始触摸流，判定"长按"
 *  2. 长按成立 → ScreenTextReader 以 root 执行 uiautomator dump，
 *     按坐标找到对应的文字节点
 *  3. 分词 → 弹层
 *
 * 不用无障碍、不用传送门。
 */
class PortalHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "BigBang"
        /** 挂在系统框架进程，全局生效，不依赖任何第三方应用 */
        private const val SYSTEM_PKG = "android"

        private var appContext: Context? = null
        private var touchSource: RootTouchSource? = null
        private var cfg: BigBangConfig? = null
        private var lastFireAt = 0L
        @Volatile private var started = false
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("$TAG module loaded (zygote)")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == Config.MODULE_PKG) return
        if (pkg != SYSTEM_PKG) return

        val c = Settings.loadForHook()
        cfg = c
        XposedBridge.log("$TAG enter system enabled=${c.enableHook} holdMs=${c.holdMs}")
        if (!c.enableHook) return
        if (started) return

        start()
    }

    private fun start() {
        val c = cfg ?: return
        val ctx = appContext ?: runCatching {
            // 系统框架进程里拿 context
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            m.invoke(null) as? Context
        }.getOrNull()

        if (ctx == null) {
            XposedBridge.log("$TAG no context yet, defer")
            return
        }
        appContext = ctx

        val src = RootTouchSource(
            context = ctx,
            onHold = { x, y -> onHoldDetected(x, y) },
            onLog = { XposedBridge.log(it) },
        )
        src.holdMs = c.holdMs
        src.start()
        touchSource = src
        started = true
        XposedBridge.log("$TAG touch source started")
    }

    /** 长按成立：按坐标取词 */
    private fun onHoldDetected(x: Float, y: Float) {
        val c = cfg ?: return
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 500L) return
        lastFireAt = now

        XposedBridge.log("$TAG hold at ($x, $y)")

        Thread {
            val text = ScreenTextReader.readAt(x, y) { XposedBridge.log(it) }
            if (text.isNullOrBlank()) {
                XposedBridge.log("$TAG no text at ($x, $y)")
                return@Thread
            }

            val words = Shatter.split(text, c.maxBlocks)
            XposedBridge.log("$TAG tokenize -> ${words.size} words: ${words.take(6)}")
            if (words.isEmpty()) return@Thread

            val ctx = appContext ?: return@Thread
            if (c.enableVibrate) vibrate(ctx)
            BlastOverlay.show(ctx, words, c)
        }.start()
    }

    private fun vibrate(ctx: Context) {
        runCatching {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(30)
            }
        }
    }
}
