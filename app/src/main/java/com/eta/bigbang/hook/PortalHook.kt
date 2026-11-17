package com.eta.bigbang.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
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
 * 传送门（ContentCatcher）取词入口。
 *
 * 为什么走这条路：
 * 微信等应用用自绘 Canvas 渲染文字，不经过 TextView.performLongClick，
 * 也不经过 MMTextView.onTouchEvent。系统传送门是唯一能拿到任意页面文字的通道。
 *
 * 实测环境：ContentCatcher 4.1.7，/product/app/ContentCatcherOS3_1
 * 关键类：com.miui.catcherpatch.ViewContentFetcher
 * 关键方法：
 *   fetchViewContent(View, int) -> Bundle
 *   fetchNativeViewContent(View, boolean) -> Bundle
 *   fetchWechatDefaultContent(View, int) -> Bundle
 * Bundle 内文本键：BUNDLE_VIEW_CONTENT
 */
class PortalHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "BigBang"
        private const val PORTAL_PKG = "com.miui.contentcatcher"
        private const val FETCHER_CLASS = "com.miui.catcherpatch.ViewContentFetcher"
        private const val KEY_VIEW_CONTENT = "BUNDLE_VIEW_CONTENT"

        private var appContext: Context? = null
        private var lastFireAt = 0L
        private var lastText = ""
        private var hooked = false
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("$TAG module loaded (zygote)")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == Config.MODULE_PKG) return

        val cfg = Settings.loadForHook()
        XposedBridge.log("$TAG enter pkg=$pkg enabled=${cfg.enableHook} allowed=${cfg.isAllowedHere(pkg)}")

        hookApplicationAttach(lpparam)

        if (!cfg.enableHook) return
        if (!cfg.isAllowedHere(pkg)) return
        if (hooked) return

        when (pkg) {
            PORTAL_PKG -> hookPortal(lpparam, cfg)
            else -> hookFallback(lpparam, cfg)
        }
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

    /* ---------------- 主路径：传送门取词 ---------------- */

    private fun hookPortal(lpparam: XC_LoadPackage.LoadPackageParam, cfg: BigBangConfig) {
        val clazz = runCatching {
            XposedHelpers.findClass(FETCHER_CLASS, lpparam.classLoader)
        }.getOrNull()

        if (clazz == null) {
            XposedBridge.log("$TAG ViewContentFetcher NOT found in $PORTAL_PKG")
            return
        }
        XposedBridge.log("$TAG found $FETCHER_CLASS")

        // 覆盖所有可能返回取词结果的 fetch* 方法
        val targets = listOf(
            "fetchViewContent",
            "fetchNativeViewContent",
            "fetchTargetViewContent",
            "fetchDefaultContent",
            "fetchWechatDefaultContent",
        )

        var count = 0
        for (name in targets) {
            clazz.declaredMethods.filter { it.name == name }.forEach { m ->
                val ok = runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val result = param.result as? Bundle ?: return
                            val text = result.getCharSequence(KEY_VIEW_CONTENT)?.toString()
                                ?: return
                            if (text.isBlank()) return
                            XposedBridge.log("$TAG ${m.name} -> len=${text.length}")
                            fire(text, cfg)
                        }
                    })
                }.isSuccess
                if (ok) {
                    count++
                    XposedBridge.log("$TAG hooked $FETCHER_CLASS.${m.name}(${m.parameterTypes.size})")
                }
            }
        }
        hooked = count > 0
        XposedBridge.log("$TAG portal hooked=$count")
    }

    /* ---------------- 兜底：普通应用的标准控件 ---------------- */

    private fun hookFallback(lpparam: XC_LoadPackage.LoadPackageParam, cfg: BigBangConfig) {
        // 只有传送到取不到内容时，普通应用仍走标准控件
        val tvOk = runCatching {
            XposedHelpers.findAndHookMethod(
                android.widget.TextView::class.java,
                "performLongClick",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? android.widget.TextView ?: return
                        val text = tv.text?.toString().orEmpty()
                        if (text.isBlank()) return
                        fire(text, cfg)
                    }
                }
            )
        }.isSuccess
        if (tvOk) hooked = true
    }

    /* ---------------- 触发弹层 ---------------- */

    private fun fire(text: String, cfg: BigBangConfig) {
        if (text.isBlank()) return

        // 一次长按会命中多个 hook 点，去重
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 700L && text == lastText) return
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
