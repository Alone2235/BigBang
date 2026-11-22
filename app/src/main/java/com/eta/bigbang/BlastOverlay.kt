package com.eta.bigbang

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 爆词弹层。
 *
 * 交互：
 *  - 点词块 = 选中/取消（可多选，选中后高亮）
 *  - 长按词块 = 直接复制该词
 *  - 底部操作条：搜索 / 分享 / 复制 / 关闭
 *    · 搜索用配置里的搜索引擎，浏览器可选
 *    · 分享走系统分享面板
 *    · 有选中时操作选中内容，无选中时操作全部
 */
object BlastOverlay {

    private var panel: View? = null
    private var windowManager: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    private const val SHARD_STAGGER_MS = 18L
    private const val SHARD_ANIM_MS = 240L

    // 配色（与 App 内设计系统一致）
    private const val C_BG = 0xF2161A20.toInt()
    private const val C_CHIP = 0xFF2A3138.toInt()
    private const val C_CHIP_ON = 0xFF4A8CFF.toInt()
    private const val C_TEXT = 0xFFFFFFFF.toInt()
    private const val C_TEXT_DIM = 0x8AFFFFFF.toInt()
    private const val C_DIVIDER = 0x33FFFFFF

    /** 当前选中的词块索引 */
    private val selected = LinkedHashSet<Int>()
    private var chipViews = ArrayList<TextView>()
    private var wordsRef = ArrayList<String>()
    private var actionBar: LinearLayout? = null

    fun show(ctx: Context, words: List<String>, cfg: BigBangConfig) {
        main.post {
            runCatching { blast(ctx, words, cfg) }
                .onFailure { android.util.Log.w("BigBang", "blast failed: ${it.message}") }
        }
    }

    private fun blast(ctx: Context, words: List<String>, cfg: BigBangConfig) {
        dismiss()
        selected.clear()
        chipViews = ArrayList()
        wordsRef = ArrayList(words)

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val d = ctx.resources.displayMetrics.density

        // ---- 顶部标题条 ----
        val title = TextView(ctx).apply {
            text = "大爆炸 · ${words.size} 块"
            setTextColor(C_TEXT)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
        }

        val divider = View(ctx).apply {
            setBackgroundColor(C_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }

        // ---- 词块网格 ----
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        }

        words.chunked(4).take(30).forEachIndexed { rowIdx, rowWords ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowWords.forEachIndexed { colIdx, w ->
                val index = rowIdx * 4 + colIdx
                val chip = buildChip(ctx, w, index, cfg, d)
                chipViews.add(chip)
                row.addView(chip)
            }
            repeat(4 - rowWords.size) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
            }
            grid.addView(row)
        }

        val scroller = ScrollView(ctx).apply {
            addView(grid)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        // ---- 底部操作条 ----
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            gravity = Gravity.CENTER
        }
        actionBar = bar
        buildActions(ctx, bar, cfg, d)

        val panelView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 22f * d
                setColor(C_BG)
            }
            elevation = 18f * d
            addView(title)
            addView(divider)
            addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (300 * d).toInt()
            ))
            addView(View(ctx).apply {
                setBackgroundColor(C_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            addView(bar)
            alpha = 0f
            translationY = 90f * d
            scaleX = 0.92f
            scaleY = 0.92f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = (56 * d).toInt()
        }

        try {
            wm.addView(panelView, params)
        } catch (t: Throwable) {
            android.util.Log.w("BigBang", "addView failed: ${t.javaClass.simpleName} ${t.message}")
            toast(ctx, "大爆炸：无法显示浮层（${t.javaClass.simpleName}）")
            return
        }
        panel = panelView
        windowManager = wm

        panelView.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        chipViews.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f * d
            v.scaleX = 0.6f
            v.scaleY = 0.6f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(50L + i * SHARD_STAGGER_MS)
                .setDuration(SHARD_ANIM_MS)
                .setInterpolator(OvershootInterpolator(1.8f))
                .start()
        }

        panelView.isClickable = true
        panelView.setOnClickListener { dismiss() }
    }

    /* ---------------- 词块 ---------------- */

    @SuppressLint("ClickableViewAccessibility")
    private fun buildChip(
        ctx: Context,
        word: String,
        index: Int,
        cfg: BigBangConfig,
        d: Float,
    ): TextView {
        val tv = TextView(ctx).apply {
            text = word
            setTextColor(C_TEXT)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
            background = chipBg(d, false)

            setOnClickListener {
                if (index in selected) selected.remove(index) else selected.add(index)
                background = chipBg(d, index in selected)
                refreshActions(ctx, cfg, d)
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(60)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
            }

            setOnLongClickListener {
                copy(ctx, word)
                toast(ctx, "已复制「$word」")
                true
            }
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        tv.layoutParams = lp
        return tv
    }

    private fun chipBg(d: Float, on: Boolean) = GradientDrawable().apply {
        cornerRadius = 11f * d
        setColor(if (on) C_CHIP_ON else C_CHIP)
    }

    /* ---------------- 操作条 ---------------- */

    private fun buildActions(ctx: Context, bar: LinearLayout, cfg: BigBangConfig, d: Float) {
        bar.removeAllViews()
        val hasSel = selected.isNotEmpty()

        bar.addView(actionBtn(ctx, "搜索", d) {
            val q = currentText()
            if (q.isBlank()) return@actionBtn
            search(ctx, q, cfg)
            dismiss()
        })
        bar.addView(actionBtn(ctx, "分享", d) {
            val q = currentText()
            if (q.isBlank()) return@actionBtn
            share(ctx, q)
            dismiss()
        })
        bar.addView(actionBtn(ctx, "复制", d) {
            val q = currentText()
            if (q.isBlank()) return@actionBtn
            copy(ctx, q)
            toast(ctx, "已复制 ${q.length} 字")
            dismiss()
        })
        bar.addView(actionBtn(ctx, if (hasSel) "取消选择" else "全选", d) {
            if (hasSel) {
                selected.clear()
            } else {
                wordsRef.indices.forEach { selected.add(it) }
            }
            chipViews.forEachIndexed { i, v ->
                v.background = chipBg(d, i in selected)
            }
            refreshActions(ctx, cfg, d)
        })
        bar.addView(actionBtn(ctx, "关闭", d) { dismiss() })
    }

    private fun refreshActions(ctx: Context, cfg: BigBangConfig, d: Float) {
        actionBar?.let { buildActions(ctx, it, cfg, d) }
    }

    private fun actionBtn(ctx: Context, label: String, d: Float, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(C_TEXT)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0x1FFFFFFF)
            }
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins((3 * d).toInt(), 0, (3 * d).toInt(), 0)
            layoutParams = lp
        }
    }

    /** 有选中用选中内容，否则用全部 */
    private fun currentText(): String {
        if (selected.isEmpty()) return wordsRef.joinToString("")
        return selected.sorted().mapNotNull { wordsRef.getOrNull(it) }.joinToString("")
    }

    /* ---------------- 动作 ---------------- */

    private fun search(ctx: Context, word: String, cfg: BigBangConfig) {
        val engine = SearchEngines.byId(cfg.searchEngineId)
        val template = if (engine.id == "custom") {
            cfg.searchTemplate.ifBlank { SearchEngines.byId("bing").template }
        } else {
            engine.template
        }
        val url = SearchEngines.buildUrl(template, word)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (cfg.browserPkg.isNotBlank()) setPackage(cfg.browserPkg)
        }
        runCatching { ctx.startActivity(intent) }.onFailure {
            // 指定浏览器不可用时，让系统弹出选择器
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .also { i -> runCatching { ctx.startActivity(i) } }
        }
    }

    private fun share(ctx: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(intent, "分享到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun copy(ctx: Context, word: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bigbang", word))
        }
    }

    private fun toast(ctx: Context, msg: String) {
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun windowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    fun dismiss() {
        val p = panel ?: return
        val wm = windowManager
        panel = null
        windowManager = null
        val d = p.resources.displayMetrics.density
        p.animate()
            .alpha(0f).translationY(60f * d).scaleX(0.94f).scaleY(0.94f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { runCatching { wm?.removeView(p) } }
            .start()
    }
}
