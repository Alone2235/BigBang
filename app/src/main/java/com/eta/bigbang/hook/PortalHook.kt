package com.eta.bigbang.hook

import android.content.Context
import android.view.MotionEvent
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
import java.lang.reflect.Method

/**
 * 传送门取词 hook。
 *
 * 正确做法（参考 MMGG / HyperDragShare 的 GPL 实现）：
 * 系统长按文字 → 传送门识别完成 → 回调 onContentReceived
 * 我们在这里拦截，从 getPropertyMap() 拿文字，弹自己的分词层。
 *
 * 不需要 root 常驻监听，不耗电。root evdev 那套是 MMGG 用来接管
 * 拖拽手势的，取词用不上。
 *
 * 本机：MIUIContentExtension 4.1.9
 */
class PortalHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "BigBang"
        private const val PORTAL_PKG = "com.miui.contentextension"
        private const val SERVICE_CLASS =
            "com.miui.contentextension.services.TextContentExtensionService"
        private const val MOTION_KEY = "MotionEvent"
        private const val CONTROL_KEY = "observe_control_event"

        private val hookedCallbacks = HashSet<Class<*>>()
        private var appContext: Context? = null
        private var cfg: BigBangConfig? = null
        private var lastFireAt = 0L
        private var lastText = ""
        @Volatile private var installed = false
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
        if (installed) return

        val clazz = runCatching {
            XposedHelpers.findClass(SERVICE_CLASS, lpparam.classLoader)
        }.getOrNull()
        if (clazz == null) {
            XposedBridge.log("$TAG $SERVICE_CLASS NOT found")
            return
        }
        XposedBridge.log("$TAG found $SERVICE_CLASS")

        // service 创建后：拿 context，并找出它的 mCallback 挂上去
        runCatching {
            XposedHelpers.findAndHookMethod(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val svc = param.thisObject ?: return
                    appContext = (svc as? Context)?.applicationContext
                    XposedBridge.log("$TAG portal service created")
                    hookCallbackFromService(svc)
                }
            })
        }

        installed = true
        XposedBridge.log("$TAG installed")
    }

    /** 从 service 的 mCallback 字段拿到回调类，挂 onContentReceived */
    private fun hookCallbackFromService(service: Any) {
        val callback = runCatching {
            XposedHelpers.getObjectField(service, "mCallback")
        }.getOrNull()
        if (callback == null) {
            XposedBridge.log("$TAG mCallback not found")
            return
        }
        hookCallbackClass(callback.javaClass)
    }

    private fun hookCallbackClass(callbackClass: Class<*>) {
        if (!hookedCallbacks.add(callbackClass)) return

        val target: Method? = callbackClass.declaredMethods.firstOrNull {
            it.name == "onContentReceived" && it.parameterTypes.size == 1
        }
        if (target == null) {
            XposedBridge.log("$TAG onContentReceived not found in ${callbackClass.name}")
            hookedCallbacks.remove(callbackClass)
            return
        }

        runCatching {
            target.isAccessible = true
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleCallback(param)
                }
            })
            XposedBridge.log("$TAG hooked ${callbackClass.name}.onContentReceived")
        }.onFailure {
            hookedCallbacks.remove(callbackClass)
            XposedBridge.log("$TAG hook callback failed: ${it.message}")
        }
    }

    /** 回调里拿内容：getPropertyMap() → 文本 */
    private fun handleCallback(param: XC_MethodHook.MethodHookParam) {
        val c = cfg ?: return
        val arg = param.args.getOrNull(0) ?: return

        val props = runCatching {
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.callMethod(arg, "getPropertyMap") as? Map<String, Any>
        }.getOrNull()

        if (props == null) {
            XposedBridge.log("$TAG callback: no property map")
            return
        }

        // 纯 MotionEvent 的结果不是取词内容，跳过
        val motion = props[MOTION_KEY]
        if (motion is MotionEvent) {
            XposedBridge.log("$TAG callback: motion only, skip")
            return
        }

        XposedBridge.log("$TAG callback keys=${props.keys}")

        // 尝试多种可能的文本键
        val text = props.entries.firstOrNull { (k, v) ->
            v is String && v.isNotBlank() &&
                (k.contains("text", true) || k.contains("content", true))
        }?.value as? String

        if (text.isNullOrBlank()) {
            XposedBridge.log("$TAG callback: no text in props")
            return
        }

        fire(text, c)
    }

    private fun fire(text: String, c: BigBangConfig) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 600L && text == lastText) return
        lastFireAt = now
        lastText = text

        val words = Shatter.split(text, c.maxBlocks)
        XposedBridge.log("$TAG tokenize -> ${words.size} words: ${words.take(6)}")
        if (words.isEmpty()) return

        val ctx = appContext ?: return
        BlastOverlay.show(ctx, words, c)
    }
}
