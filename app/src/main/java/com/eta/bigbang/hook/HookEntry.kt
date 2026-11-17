package com.eta.bigbang

import android.app.AndroidAppHelper
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。
 *
 * 挂在 android.widget.Editor 的选择流程上：
 * - 长按文字后系统会 startSelectionActionMode（旧版）/ 由 Editor 内部起 SelectionActionMode
 * - 这里在文本选择建立后取出 TextView 全文，交给 BigBangLayer 弹层
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "BigBang"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cfg = Settings.loadForHook()
        val pkg = lpparam.packageName ?: return
        if (!cfg.enableHook) return
        if (pkg == Config.MODULE_PKG) return
        if (!cfg.isActiveFor(pkg)) return

        // 跳过 framework 自身等非应用进程
        if (lpparam.processName != pkg) return

        try {
            hookEditor(lpparam, cfg)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG hookEditor failed on $pkg: ${t.message}")
        }
    }

    private fun hookEditor(lpparam: XC_LoadPackage.LoadPackageParam, cfg: BigBangConfig) {
        val editorClass = XposedHelpers.findClassIfExists(
            "android.widget.Editor", lpparam.classLoader
        ) ?: return

        val candidates = listOf(
            "startSelectionActionMode",   // 无参
            "startSelectionActionModeInternal",
            "invalidateTextDisplayList",
        )

        var hooked = false
        for (name in candidates) {
            val methods = editorClass.declaredMethods.filter { it.name == name }
            for (m in methods) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            onSelectionBuilt(param.thisObject, cfg)
                        }
                    })
                    hooked = true
                    XposedBridge.log("$TAG hooked Editor.$name")
                } catch (_: Throwable) {
                }
            }
        }

        if (!hooked) {
            // 兜底：直接挂 TextView.performLongClick
            val textViewClass = XposedHelpers.findClassIfExists(
                "android.widget.TextView", lpparam.classLoader
            )
            textViewClass?.declaredMethods
                ?.firstOrNull { it.name == "performLongClick" && it.parameterTypes.isEmpty() }
                ?.let { m ->
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val tv = param.thisObject as? TextView ?: return
                            val text = tv.text?.toString().orEmpty()
                            if (text.isNotBlank()) trigger(tv, text, cfg)
                        }
                    })
                    XposedBridge.log("$TAG hooked TextView.performLongClick (fallback)")
                }
        }
    }

    private fun onSelectionBuilt(editorObj: Any?, cfg: BigBangConfig) {
        val editor = editorObj ?: return
        val tv = XposedHelpers.getObjectField(editor, "mTextView") as? TextView ?: return
        val text = tv.text?.toString().orEmpty()
        if (text.isBlank()) return
        trigger(tv, text, cfg)
    }

    private fun trigger(tv: TextView, text: String, cfg: BigBangConfig) {
        val words = Tokenizer.split(text, cfg.maxBlocks)
        if (words.isEmpty()) return

        val ctx = runCatching { tv.context }.getOrNull() ?: return
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
