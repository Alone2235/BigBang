package com.eta.bigbang

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * 分词器。
 *
 * 不引入第三方词典，用系统 ICU BreakIterator 做词边界切分，并针对
 * 英文/域名/版本号里的分隔符做二次拆分，避免 "pan.quark.cn" 这种整串成块。
 */
object Shatter {

    /** 允许拆开的连接符（不含连字符，避免把 well-known 拆散） */
    private val SEPARATORS = charArrayOf('.', '/', '_', '@', '#', '&', '=', '?', ':', '|', '+')

    fun split(text: String, maxBlocks: Int = 200): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val clean = text.replace('\u00A0', ' ')

        for (paragraph in clean.split('\n')) {
            if (paragraph.isBlank()) continue
            collectParagraph(paragraph, out, maxBlocks)
            if (out.size >= maxBlocks) break
        }
        return if (out.size > maxBlocks) out.subList(0, maxBlocks).toList() else out
    }

    private fun collectParagraph(paragraph: String, out: MutableList<String>, maxBlocks: Int) {
        val bi = BreakIterator.getWordInstance(Locale.CHINA)
        bi.setText(paragraph)
        var start = bi.first()
        var end = bi.next()
        var pendingChinese = StringBuilder()

        fun flushPending() {
            if (pendingChinese.isNotEmpty()) {
                out.add(pendingChinese.toString())
                pendingChinese = StringBuilder()
            }
        }

        while (end != BreakIterator.DONE && out.size < maxBlocks) {
            val token = paragraph.substring(start, end)
            when {
                token.isBlank() -> flushPending()

                // 单个汉字：ICU 常切成单字，先攒着，相邻单字合并成块
                isSingleChinese(token) -> pendingChinese.append(token)

                // 汉字词
                isChineseWord(token) -> {
                    flushPending()
                    out.add(token)
                }

                // 含分隔符的串（域名 / 路径 / 版本号）：拆开再逐个加入
                token.any { it in SEPARATORS } -> {
                    flushPending()
                    splitBySeparators(token).forEach { if (out.size < maxBlocks) out.add(it) }
                }

                // 普通字母数字
                token.any { it.isLetterOrDigit() } -> {
                    flushPending()
                    out.add(token)
                }

                // 纯标点：丢弃
                else -> flushPending()
            }
            start = end
            end = bi.next()
        }
        flushPending()
    }

    /** 按分隔符切成多段，去掉空段 */
    private fun splitBySeparators(token: String): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in token) {
            if (ch in SEPARATORS) {
                if (sb.isNotEmpty()) {
                    parts.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }

    private fun isSingleChinese(s: String): Boolean =
        s.length == 1 && s[0].code in 0x4E00..0x9FFF

    private fun isChineseWord(s: String): Boolean =
        s.length >= 2 && s.all { it.code in 0x4E00..0x9FFF }
}
