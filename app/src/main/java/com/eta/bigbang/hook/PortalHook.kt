package com.eta.bigbang.hook

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.eta.bigbang.BigBangConfig
import com.eta.bigbang.BlastOverlay
import com.eta.bigbang.Config
import com.eta.bigbang.Settings
import com.eta.bigbang.Shatter
import com.eta.bigbang.input.RootTouchSource

/**
 * 传送门取词 + Root evdev 触发。
 *
 * 链路（参考 MMGG / HyperDragShare，按本机实测校正）：
 *  1. RootTouchSource 读 /dev/input/event7 原始触摸流，判定"按住"
 *  2. 按住成立 → 读 TextContentExtensionService 的静态字段 sContent
 *  3. 有文字 → 分词 → 弹层
 *
 * 为什么不能只等系统长按：
 * 本机 long_press_home_key=launch_xiaoai_screen_identification，
 * 长按被分给小爱识屏，传送门不会因普通长按取词。
 */
class PortalHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "BigBang"
        private const val PORTAL_PKG = "com.miui.contentextension"
        private const val SERVICE_CLASS =
            "com.miui.contentextension.services.TextContentExtensionService"

        @Volatile private var serviceClass: Class<*>? = null
        private var appContext: Context? = null
        private var touchSource: RootTouchSource? = null
        private var cfg: BigBangConfig? = null
        private var lastFireAt = 0L
        private var lastText = ""
        private const val DIAG = true
        @Volatile private var lastDiag = ""
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("$TAG module loaded (zygote)")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg != PORTAL_PKG) return

        val c = Settings.loadForHook()
        cfg = c
        XposedBridge.log("$TAG enter $pkg enabled=${c.enableHook}")
        if (!c.enableHook) return

        val clazz = runCatching {
            XposedHelpers.findClass(SERVICE_CLASS, lpparam.classLoader)
        }.getOrNull()

        if (clazz == null) {
            XposedBridge.log("$TAG $SERVICE_CLASS NOT found")
            return
        }
        serviceClass = clazz
        XposedBridge.log("$TAG found $SERVICE_CLASS")

        // 抓 context
        runCatching {
            XposedHelpers.findAndHookMethod(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val svc = param.thisObject as? Context ?: return
                    appContext = svc.applicationContext
                    XposedBridge.log("$TAG portal service created")
                    ensureTouchSource()
                }
            })
        }

        // 取词触发点：读 sContent
        for (name in listOf("onStartCommand", "onStartPick")) {
            clazz.declaredMethods.filter { it.name == name }.forEach { m ->
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            XposedBridge.log("$TAG ${m.name} fired")
                            tryFire()
                        }
                    })
                }
            }
        }
    }

    /** 启动 root 触摸监听（只启一次） */
    private fun ensureTouchSource() {
        if (touchSource != null) return
        val ctx = appContext ?: return
        val c = cfg ?: return

        val src = RootTouchSource(
            context = ctx,
            onHold = { _, _ ->
                XposedBridge.log("$TAG hold -> tryFire")
                tryFire()
            },
            onLog = { XposedBridge.log(it) },
        )
        src.holdMs = c.holdMs
        src.start()
        touchSource = src
        XposedBridge.log("$TAG touch source started holdMs=${c.holdMs}")
    }

    /** 读 sContent 并触发弹层 */
    private fun tryFire() {
        val clazz = serviceClass ?: return
        val c = cfg ?: return

        val text = readContent(clazz)
        if (text.isNullOrBlank()) {
            if (DIAG) XposedBridge.log("$TAG tryFire: no content")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastFireAt < 800L && text == lastText) return
        lastFireAt = now
        lastText = text

        val words = Shatter.split(text, c.maxBlocks)
        XposedBridge.log("$TAG tokenize -> ${words.size} words: ${words.take(6)}")
        if (words.isEmpty()) return

        val ctx = appContext ?: return
        if (c.enableVibrate) vibrate(ctx)
        BlastOverlay.show(ctx, words, c)
    }

    private fun readContent(clazz: Class<*>): String? = runCatching {
        val textMode = XposedHelpers.getStaticObjectField(clazz, "sIsTextMode")
        val content = XposedHelpers.getStaticObjectField(clazz, "sContent") as? String
        if (DIAG) {
            val snap = "textMode=$textMode content=${content?.let { "len=${it.length}" } ?: "null"}"
            if (snap != lastDiag) {
                lastDiag = snap
                XposedBridge.log("$TAG diag $snap")
            }
        }
        if (true != textMode) return null
        content
    }.onFailure {
        if (DIAG) XposedBridge.log("$TAG readContent error: ${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

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
