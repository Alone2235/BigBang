package com.eta.bigbang

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * 分词器。
 *
 * 目标：不引入几 MB 的第三方词典，用系统自带的 ICU BreakIterator 做「够用」的词边界切分，
 * 同时把中文按字/词混切的粒度处理得比纯字符切分更合理。
 *
 * 策略：
 * 1. 先按行、句末标点切成段，保留段内文本。
 * 2. 段内用 BreakIterator 取词边界；中文环境下 ICU 一般按词切，若切得过细（单字）
 *    会做相邻合并的兜底。
 * 3. 英文/数字按词切，标点独立成块（方便直接复制，不带上标点）。
 */
object Tokenizer {

    /** 结果：词块列表 */
    fun split(text: String, maxBlocks: Int = 200): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val clean = text.replace('\u00A0', ' ')

        for (paragraph in clean.split('\n')) {
            if (paragraph.isBlank()) continue
            collectParagraph(paragraph, out)
            if (out.size >= maxBlocks) break
        }
        return if (out.size > maxBlocks) out.subList(0, maxBlocks).toList() else out
    }

    private fun collectParagraph(paragraph: String, out: MutableList<String>) {
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

        while (end != BreakIterator.DONE) {
            val token = paragraph.substring(start, end)
            when {
                token.isBlank() -> flushPending()

                // 纯汉字且长度 1：ICU 把它当单字，先攒着，相邻单字合并成块
                isSingleChinese(token) -> pendingChinese.append(token)

                // 汉字词（2 字及以上）：直接作为一块
                isChineseWord(token) -> {
                    flushPending()
                    out.add(token)
                }

                // 拉丁字母/数字/其他文字
                token.any { it.isLetterOrDigit() } -> {
                    flushPending()
                    out.add(token)
                }

                // 纯标点：丢弃，不占块
                else -> flushPending()
            }
            start = end
            end = bi.next()
        }
        flushPending()
    }

    private fun isSingleChinese(s: String): Boolean =
        s.length == 1 && s[0].code in 0x4E00..0x9FFF

    private fun isChineseWord(s: String): Boolean =
        s.length >= 2 && s.all { it.code in 0x4E00..0x9FFF }
}
