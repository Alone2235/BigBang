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
import com.eta.bigbang.BlastOverlay
import com.eta.bigbang.Config
import com.eta.bigbang.Settings
import com.eta.bigbang.Shatter

/**
 * 模块入口。
 *
 * 文本选择的三层兜底（按可靠性排序）：
 * 1. Editor.startSelectionActionModeAsync —— 任何走系统文本选择的应用都会经过，
 *    回调里能拿到 Editor 与 mTextView，是通用主路径。
 * 2. 各家自绘控件（如微信 MMTextView）重写的 onTouchEvent —— 应用自己处理长按时走这里。
 * 3. TextView.performLongClick —— 最传统的路径。
 */
class IgnitionHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

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
        if (pkg == "android" || pkg == "com.android.systemui") return

        val cfg = Settings.loadForHook()
        val allowed = cfg.isAllowedHere(pkg)
        XposedBridge.log("$TAG enter pkg=$pkg proc=${lpparam.processName} mode=${cfg.scopeMode} enabled=${cfg.enableHook} allowed=$allowed")

        hookApplicationAttach(lpparam)

        if (!cfg.enableHook) return
        if (!allowed) return

        hookAll(lpparam, cfg)
    }

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

    private fun hookAll(lpparam: XC_LoadPackage.LoadPackageParam, cfg: BigBangConfig) {
        var total = 0

        // ---------- 路径 1：Editor 文本选择（通用主路径）----------
        val editorClass = runCatching {
            XposedHelpers.findClass("android.widget.Editor", null)
        }.getOrNull()

        if (editorClass != null) {
            editorClass.declaredMethods
                .filter { it.name == "startSelectionActionModeAsync" || it.name == "startSelectionActionMode" }
                .forEach { m ->
                    val ok = runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                XposedBridge.log("$TAG Editor.${m.name} fired")
                                fireFromEditor(param.thisObject, cfg)
                            }
                        })
                    }.isSuccess
                    if (ok) {
                        total++
                        XposedBridge.log("$TAG hooked Editor.${m.name}")
                    }
                }
        }

        // ---------- 路径 2：自绘控件 onTouchEvent（微信等）----------
        // 只挂真的声明了 onTouchEvent 的 View 子类里"和文本相关"的那批，
        // 避免给每个 View 都插桩造成卡顿。
        val textishClasses = listOf(
            "com.tencent.mm.ui.widget.MMTextView",
            "com.tencent.mm.ui.widget.textview.MMTextView",
        )
        textishClasses.forEach { cn ->
            val c = runCatching { XposedHelpers.findClass(cn, lpparam.classLoader) }.getOrNull()
                ?: return@forEach
            val ok = runCatching {
                XposedHelpers.findAndHookMethod(
                    c, "onTouchEvent", android.view.MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val ev = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
                            // 只看抬手：此时系统已完成长按判定
                            if (ev.actionMasked != android.view.MotionEvent.ACTION_UP) return
                            val tv = param.thisObject as? TextView ?: return
                            val text = tv.text?.toString().orEmpty()
                            if (text.isBlank()) return
                            XposedBridge.log("$TAG MMTextView touch-up len=${text.length}")
                            fire(tv, text, cfg)
                        }
                    }
                )
            }.isSuccess
            if (ok) {
                total++
                XposedBridge.log("$TAG hooked $cn.onTouchEvent")
            }
        }

        // ---------- 路径 3：TextView.performLongClick（传统路径）----------
        val tvOk = runCatching {
            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "performLongClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val text = tv.text?.toString().orEmpty()
                        XposedBridge.log("$TAG TextView.performLongClick len=${text.length}")
                        fire(tv, text, cfg)
                    }
                }
            )
        }.isSuccess
        if (tvOk) total++

        XposedBridge.log("$TAG total hooked=$total on ${lpparam.packageName}")
    }

    /** 从 Editor 实例取 TextView 与选中文本 */
    private fun fireFromEditor(editorObj: Any?, cfg: BigBangConfig) {
        val editor = editorObj ?: return
        val tv = runCatching {
            XposedHelpers.getObjectField(editor, "mTextView")
        }.getOrNull() as? TextView ?: return

        val text = tv.text?.toString().orEmpty()
        if (text.isBlank()) return
        fire(tv, text, cfg)
    }

    private fun fire(view: View, text: String, cfg: BigBangConfig) {
        if (text.isBlank()) return
        val words = Shatter.split(text, cfg.maxBlocks)
        XposedBridge.log("$TAG tokenize -> ${words.size} words: ${words.take(6)}")
        if (words.isEmpty()) return

        val ctx = appContext ?: runCatching { view.context }.getOrNull() ?: return
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
