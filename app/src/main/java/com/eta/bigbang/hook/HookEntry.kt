package com.eta.bigbang.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.eta.bigbang.BigBangConfig
import com.eta.bigbang.BigBangLayer
import com.eta.bigbang.Config
import com.eta.bigbang.Settings
import com.eta.bigbang.Tokenizer

/**
 * 模块入口。
 *
 * 核心修正点：
 * 1. android.widget.Editor 是 framework 类，必须用 boot classloader 找，
 *    不能用 lpparam.classLoader（这是上一版完全失效的根本原因）。
 * 2. 不再用 processName == packageName 硬过滤，只排除明显无关的进程。
 * 3. 每一步都打日志，便于定位。
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "BigBang"
        private var appContext: Context? = null
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("$TAG module loaded (zygote)")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == Config.MODULE_PKG) return
        // 只跳过 system_server / android 这类没必要处理的
        if (pkg == "android" || pkg == "com.android.systemui") return

        val cfg = Settings.loadForHook()
        XposedBridge.log("$TAG enter pkg=$pkg proc=${lpparam.processName} mode=${cfg.scopeMode} enabled=${cfg.enableHook} active=${cfg.isActiveFor(pkg)}")

        // 抓宿主 Application，供弹层拿一个稳定的 context
        hookApplicationAttach(lpparam)

        if (!cfg.enableHook) return
        if (!cfg.isActiveFor(pkg)) return

        hookTextSelection(lpparam, cfg)
    }

    /** 拿宿主 Application context */
    private fun hookApplicationAttach(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                android.app.Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.args.getOrNull(0) as? Application ?: return
                        appContext = app.applicationContext
                    }
                }
            )
        }.onFailure { XposedBridge.log("$TAG appContext hook failed: ${it.message}") }
    }

    private fun hookTextSelection(lpparam: XC_LoadPackage.LoadPackageParam, cfg: BigBangConfig) {
        var hooked = 0

        // ---- 方式 A：Editor（framework 类，boot classloader）----
        val editorClass = runCatching {
            XposedHelpers.findClass("android.widget.Editor", null)
        }.getOrNull()

        if (editorClass != null) {
            XposedBridge.log("$TAG found Editor class via boot classloader")

            // 所有名字含 SelectionActionMode 的方法都挂，覆盖各家 ROM 差异
            editorClass.declaredMethods
                .filter { it.name.contains("SelectionActionMode") || it.name == "performLongClick" }
                .forEach { m ->
                    val ok = runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                XposedBridge.log("$TAG Editor.${m.name} triggered")
                                onEditorEvent(param.thisObject, cfg)
                            }
                        })
                    }.isSuccess
                    if (ok) {
                        hooked++
                        XposedBridge.log("$TAG hooked Editor.${m.name}(${m.parameterTypes.size} args)")
                    }
                }
        } else {
            XposedBridge.log("$TAG Editor class NOT found")
        }

        // ---- 方式 B：TextView 长按（兜底，覆盖绝大多数场景）----
        val tvOk = runCatching {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "performLongClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val text = tv.text?.toString().orEmpty()
                        XposedBridge.log("$TAG TextView.performLongClick len=${text.length}")
                        trigger(tv, text, cfg)
                    }
                }
            )
        }.isSuccess
        if (tvOk) {
            hooked++
            XposedBridge.log("$TAG hooked TextView.performLongClick")
        }

        XposedBridge.log("$TAG total hooked=$hooked on ${lpparam.packageName}")
    }

    private fun onEditorEvent(editorObj: Any?, cfg: BigBangConfig) {
        val editor = editorObj ?: return
        val tv = runCatching {
            XposedHelpers.getObjectField(editor, "mTextView")
        }.getOrNull() as? TextView ?: return

        val text = tv.text?.toString().orEmpty()
        if (text.isBlank()) return
        trigger(tv, text, cfg)
    }

    private fun trigger(view: View, text: String, cfg: BigBangConfig) {
        if (text.isBlank()) return
        val words = Tokenizer.split(text, cfg.maxBlocks)
        XposedBridge.log("$TAG tokenize -> ${words.size} words: ${words.take(8)}")
        if (words.isEmpty()) return

        val ctx = appContext ?: runCatching { view.context }.getOrNull() ?: return
        if (cfg.enableVibrate) vibrate(ctx)

        BigBangLayer.show(ctx, words, cfg)
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
