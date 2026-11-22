package com.eta.bigbang.input

import android.content.Context
import android.graphics.Rect
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * Root 取词器。
 *
 * 不用无障碍、不用传送门，直接以 root 执行 uiautomator dump，
 * 解析当前界面的文字节点，按长按坐标找到对应的那一条文字。
 *
 * 实测：本机 root 下 dump 可直接读到微信聊天文字。
 */
object ScreenTextReader {

    private const val TAG = "BigBang/Text"
    private const val DUMP_PATH = "/data/local/tmp/.bb_ui.xml"

    /** 一次 dump 的原始内容 */
    private data class Node(val text: String, val bounds: Rect)

    /**
     * 按坐标取词：返回包含该点的最小文字节点内容。
     * 找不到精确命中时，回退到该点附近最近的非空文字。
     */
    fun readAt(x: Float, y: Float, log: (String) -> Unit = { XposedBridge.log(it) }): String? {
        val nodes = dumpNodes(log)
        if (nodes.isEmpty()) return null

        val px = x.toInt()
        val py = y.toInt()

        // 1. 精确包含该点，取面积最小的（最贴近的那条）
        val hit = nodes
            .filter { it.bounds.contains(px, py) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
        if (hit != null) {
            log("$TAG hit exact: ${hit.text.take(30)}")
            return hit.text
        }

        // 2. 回退：垂直方向最近、水平有重叠的节点
        val near = nodes
            .filter { it.bounds.left <= px && px <= it.bounds.right }
            .minByOrNull { Math.abs(it.bounds.centerY() - py) }
        if (near != null) {
            log("$TAG hit nearest: ${near.text.take(30)}")
            return near.text
        }

        // 3. 再回退：全屏最近
        val any = nodes.minByOrNull {
            val dx = it.bounds.centerX() - px
            val dy = it.bounds.centerY() - py
            (dx * dx + dy * dy).toLong()
        }
        if (any != null) log("$TAG hit fallback: ${any.text.take(30)}")
        return any?.text
    }

    /** 取全屏所有非空文字节点 */
    private fun dumpNodes(log: (String) -> Unit): List<Node> {
        val ok = runCatching {
            val p = ProcessBuilder("sh", "-c", "uiautomator dump $DUMP_PATH")
                .redirectErrorStream(true)
                .start()
            p.waitFor()
            p.exitValue() == 0
        }.getOrDefault(false)

        if (!ok) {
            log("$TAG dump failed")
            return emptyList()
        }

        val xml = runCatching { File(DUMP_PATH).readText() }.getOrNull() ?: return emptyList()
        runCatching { File(DUMP_PATH).delete() }

        return parseNodes(xml, log)
    }

    /** 解析 dump 出来的 XML */
    private fun parseNodes(xml: String, log: (String) -> Unit): List<Node> {
        val out = ArrayList<Node>()
        // 逐个 node 标签解析
        val nodeRe = Regex("<node\\b[^>]*>")
        val textRe = Regex("text=\"([^\"]*)\"")
        val boundsRe = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")

        for (m in nodeRe.findAll(xml)) {
            val tag = m.value
            val text = textRe.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
            if (text.isEmpty()) continue

            val b = boundsRe.find(tag) ?: continue
            val g = b.groupValues
            val rect = Rect(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt())
            if (rect.width() <= 0 || rect.height() <= 0) continue

            out.add(Node(text, rect))
        }
        log("$TAG parsed ${out.size} text nodes")
        return out
    }
}
