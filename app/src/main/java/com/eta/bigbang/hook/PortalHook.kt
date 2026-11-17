package com.eta.bigbang.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
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

/**
 * 传送门（Taplus / ContentExtension）取词入口。
 *
 * 参考 MMGG / HyperDragShare 的 GPL 实现，按本机实测校正：
 *  - 目标包是 com.miui.contentextension，不是 com.miui.contentcatcher
 *  - 核心类：com.miui.contentextension.services.TextContentExtensionService
 *  - 取词：静态字段 sContent（文本模式由 sIsTextMode 判定），
 *         来源应用由 getInjectorPackage() 给出
 *  - 触发时机：hook service 的 onStartCommand / onStartPick
 *
 * 本机验证：MIUIContentExtension 4.1.9，上述字段/方法均存在。
 */
class PortalHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "BigBang"
        private const val PORTAL_PKG = "com.miui.contentextension"
        private const val SERVICE_CLASS =
            "com.miui.contentextension.services.TextContentExtensionService"

        private var appContext: Context? = null
        private var lastFireAt = 0L
        private var lastText = ""
        @Volatile private var serviceHooked = false
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("$TAG module loaded (zygote)")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == Config.MODULE_PKG) return
        if (pkg != PORTAL_PKG) return

        val cfg = Settings.loadForHook()
        XposedBridge.log("$TAG enter $pkg enabled=${cfg.enableHook}")
        if (!cfg.enableHook) return
        if (serviceHooked) return

        hookService(lpparam, cfg)
    }

    /** hook 传送门 TextContentExtensionService，读 sContent 取词 */
    private fun hookService(lpparam: XC_LoadPackage.LoadPackageParam, cfg: BigBangConfig) {
        val clazz = runCatching {
            XposedHelpers.findClass(SERVICE_CLASS, lpparam.classLoader)
        }.getOrNull()

        if (clazz == null) {
            XposedBridge.log("$TAG $SERVICE_CLASS NOT found")
            return
        }
        XposedBridge.log("$TAG found $SERVICE_CLASS")

        // 抓 service 的 context，供弹层用
        runCatching {
            XposedHelpers.findAndHookMethod(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val svc = param.thisObject as? Context ?: return
                    appContext = svc.applicationContext
                    XposedBridge.log("$TAG portal service created")
                }
            })
        }

        // 长按取词会走 onStartCommand / onStartPick，挂这些点读 sContent
        val triggers = listOf("onStartCommand", "onStartPick")
        var count = 0
        for (name in triggers) {
            clazz.declaredMethods.filter { it.name == name }.forEach { m ->
                val ok = runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            readAndFire(param.thisObject, cfg)
                        }
                    })
                }.isSuccess
                if (ok) {
                    count++
                    XposedBridge.log("$TAG hooked ${m.name}(${m.parameterTypes.size})")
                }
            }
        }

        serviceHooked = count > 0
        XposedBridge.log("$TAG service hooks=$count")

        // 兜底：即使触发点没命中，也周期性读 sContent。
        // 传送门在识别完成后才把文字写进静态字段，周期轮询最稳。
        startPolling(clazz, cfg)
    }

    /** 读静态字段 sContent */
    private fun readContent(serviceClass: Class<*>): String? = runCatching {
        val textMode = XposedHelpers.getStaticObjectField(serviceClass, "sIsTextMode")
        if (true != textMode) return null
        XposedHelpers.getStaticObjectField(serviceClass, "sContent") as? String
    }.getOrNull()

    private fun readAndFire(serviceObj: Any?, cfg: BigBangConfig) {
        val clazz = serviceObj?.javaClass ?: return
        val text = readContent(clazz) ?: return
        if (text.isBlank()) return
        fire(text, cfg)
    }

    /** 周期轮询静态字段：传送门把结果写进 sContent 的时刻不一定与 hook 点重合 */
    private fun startPolling(clazz: Class<*>, cfg: BigBangConfig) {
        val t = Thread {
            var last = ""
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val text = readContent(clazz)
                    if (!text.isNullOrBlank() && text != last) {
                        last = text
                        XposedBridge.log("$TAG poll got text len=${text.length}")
                        fire(text, cfg)
                    }
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    break
                } catch (t2: Throwable) {
                    Thread.sleep(1000)
                }
            }
        }
        t.isDaemon = true
        t.name = "BigBang-PortalPoll"
        t.start()
        XposedBridge.log("$TAG polling started")
    }

    private fun fire(text: String, cfg: BigBangConfig) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 800L && text == lastText) return
        lastFireAt = now
        lastText = text

        val words = Shatter.split(text, cfg.maxBlocks)
        XposedBridge.log("$TAG tokenize -> ${words.size} words: ${words.take(6)}")
        if (words.isEmpty()) return

        val ctx = appContext ?: return

        if (cfg.enableVibrate) vibrate(ctx)
        BlastOverlay.show(ctx, words, cfg)
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
