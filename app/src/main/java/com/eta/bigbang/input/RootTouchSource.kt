package com.eta.bigbang.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Root evdev 触摸监听。
 *
 * 参考 MMGG / HyperDragShare 的 GPL 实现，按本机实测精简：
 *  - 触摸设备：/dev/input/event7（synaptics_tcm_touch, PROP=2）
 *  - 通过 su 起 shell，cat 原始 event 流，Java 侧用 EvdevTouchParser 解析
 *  - 判定：按住超过阈值时长且位移小 → 触发取词
 *
 * 只做一件事：判断"用户是否按住了一段文字"，不做拖拽。
 */
class RootTouchSource(
    private val context: Context,
    private val onHold: (x: Float, y: Float) -> Unit,
    private val onLog: (String) -> Unit = { XposedBridge.log(it) },
) {

    companion object {
        private const val TAG = "BigBang/Touch"
        /** 按住判定时长（毫秒） */
        const val DEFAULT_HOLD_MS = 450L
        /** 允许的最大位移（原始坐标单位） */
        private const val MAX_MOVE_RAW = 60f
    }

    private val main = Handler(Looper.getMainLooper())
    private var process: Process? = null
    @Volatile private var running = false
    @Volatile private var rawMaxX = 1
    @Volatile private var rawMaxY = 1

    var holdMs: Long = DEFAULT_HOLD_MS

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "BigBang-TouchReader").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
    }

    private fun loop() {
        try {
            val dev = findTouchDevice()
            if (dev == null) {
                onLog("$TAG no touch device found")
                return
            }
            rawMaxX = dev.second.coerceAtLeast(1)
            rawMaxY = dev.third.coerceAtLeast(1)
            onLog("$TAG device=${dev.first} maxX=$rawMaxX maxY=$rawMaxY")

            val cmd = "exec 4<${dev.first} || exit \$?\ncat <&4 4<&-"
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            process = p

            // 手势状态
            var downAt = 0L
            var downX = 0f
            var downY = 0f
            var curX = 0f
            var curY = 0f
            var tracking = false
            var fired = false

            val parser = EvdevTouchParser { action, rawX, rawY, _, _, _ ->
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        tracking = true
                        fired = false
                        downAt = SystemClock.uptimeMillis()
                        downX = rawX
                        downY = rawY
                        curX = rawX
                        curY = rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (tracking) {
                            curX = rawX
                            curY = rawY
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracking = false
                    }
                }

                // 判定按住
                if (tracking && !fired) {
                    val held = SystemClock.uptimeMillis() - downAt
                    val dx = Math.abs(curX - downX)
                    val dy = Math.abs(curY - downY)
                    if (held >= holdMs && dx < MAX_MOVE_RAW && dy < MAX_MOVE_RAW) {
                        fired = true
                        val fx = curX
                        val fy = curY
                        onLog("$TAG hold detected raw=($fx,$fy) held=${held}ms")
                        main.post { onHold(fx, fy) }
                    }
                }
            }

            // 读 24 字节/事件的原始流（timeval 16 + type2 + code2 + value4）
            val buf = ByteArray(24 * 64)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

            while (running) {
                val read = p.inputStream.read(buf)
                if (read <= 0) break
                var off = 0
                while (off + 24 <= read) {
                    bb.position(off)
                    bb.long          // sec
                    bb.long          // usec
                    val type = bb.short.toInt()
                    val code = bb.short.toInt()
                    val value = bb.int
                    parser.consume(type, code, value)
                    off += 24
                }
            }
            onLog("$TAG reader exited")
        } catch (t: Throwable) {
            onLog("$TAG loop error: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 找触摸设备：返回 (路径, maxX, maxY) */
    private fun findTouchDevice(): Triple<String, Int, Int>? {
        val devices = runCatching {
            File("/proc/bus/input/devices").readText()
        }.getOrNull() ?: return null

        for (block in devices.split(Regex("\n\\s*\n"))) {
            val isDirect = block.contains("PROP=2")
            val hasAbs = block.contains("ABS=")
            if (!isDirect || !hasAbs) continue

            val handlerLine = block.lineSequence().firstOrNull { it.startsWith("H: Handlers=") }
                ?: continue
            val m = Regex("\\b(event\\d+)\\b").find(handlerLine) ?: continue
            val path = "/dev/input/${m.groupValues[1]}"

            val ranges = runCatching {
                val p = ProcessBuilder("su", "-c", "getevent -lp $path").start()
                p.waitFor(3, TimeUnit.SECONDS)
                p.inputStream.bufferedReader().readText()
            }.getOrNull()

            val maxX = ranges?.let {
                Regex("ABS_MT_POSITION_X\\s*:.*?max\\s+(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: 0
            val maxY = ranges?.let {
                Regex("ABS_MT_POSITION_Y\\s*:.*?max\\s+(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: 0

            onLog("$TAG candidate $path maxX=$maxX maxY=$maxY")
            return Triple(path, maxX, maxY)
        }
        return null
    }
}
