package com.eta.bigbang

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 爆词弹层。
 *
 * 视觉：白蓝浅色（与 App 内 HyperBlue 体系一致）。
 *  - 面板：白色半透明玻璃，透出底下的内容
 *  - 强调色：#4A8CFF 澎湃蓝
 *  - 词块：浅灰底，选中变蓝
 */
object BlastOverlay {

    private var panel: View? = null
    private var windowManager: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    private const val SHARD_STAGGER_MS = 18L
    private const val SHARD_ANIM_MS = 240L

    // ---- 白蓝配色 ----
    private const val C_PANEL = 0xE6FFFFFF.toInt()   // 白色，约 90% 不透明，能透出底层内容
    private const val C_CHIP = 0xFFF2F4F8.toInt()    // 词块底（浅灰蓝）
    private const val C_CHIP_ON = 0xFF4A8CFF.toInt() // 选中（澎湃蓝）
    private const val C_TEXT = 0xFF1B1F27.toInt()    // 主文字
    private const val C_TEXT_DIM = 0xFF878D99.toInt()// 次要文字
    private const val C_DIVIDER = 0x14000000         // 分隔线
    private const val C_BTN = 0xFFEDF2FB.toInt()     // 次要按钮底
    private const val C_ACCENT = 0xFF4A8CFF.toInt()
    private const val C_STROKE = 0x0F1B1F27          // 卡片描边

    private val selected = LinkedHashSet<Int>()
    private var chipViews = ArrayList<TextView>()
    private var wordsRef = ArrayList<String>()
    private var actionBar: LinearLayout? = null
    private var countLabel: TextView? = null

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

        // ---- 顶部：标题 + 计数 ----
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * d).toInt(), (14 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
        }

        val title = TextView(ctx).apply {
            text = "大爆炸"
            setTextColor(C_TEXT)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.02f
        }

        val count = TextView(ctx).apply {
            text = "${words.size} 块"
            setTextColor(C_TEXT_DIM)
            textSize = 12f
        }
        countLabel = count

        header.addView(title)
        header.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        header.addView(count)

        val divider = View(ctx).apply {
            setBackgroundColor(C_DIVIDER)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }

        // ---- 词块网格 ----
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
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
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            gravity = Gravity.CENTER
        }
        actionBar = bar
        buildActions(ctx, bar, cfg, d)

        val panelView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 24f * d
                setColor(C_PANEL)
                setStroke((0.8f * d).toInt(), C_STROKE)
            }
            addView(header)
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
            y = (48 * d).toInt()
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
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        chipViews.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 22f * d
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
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            background = chipBg(d, false)

            setOnClickListener {
                if (index in selected) selected.remove(index) else selected.add(index)
                background = chipBg(d, index in selected)
                setTextColor(if (index in selected) Color.WHITE else C_TEXT)
                updateCount()
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
        cornerRadius = 12f * d
        setColor(if (on) C_CHIP_ON else C_CHIP)
        if (!on) setStroke((0.6f * d).toInt(), C_STROKE)
    }

    private fun updateCount() {
        val base = wordsRef.size
        countLabel?.text = if (selected.isEmpty()) "$base 块"
        else "已选 ${selected.size} / $base"
        countLabel?.setTextColor(if (selected.isEmpty()) C_TEXT_DIM else C_ACCENT)
    }

    /* ---------------- 操作条 ---------------- */

    private fun buildActions(ctx: Context, bar: LinearLayout, cfg: BigBangConfig, d: Float) {
        bar.removeAllViews()
        val hasSel = selected.isNotEmpty()

        bar.addView(actionBtn(ctx, "搜索", d, primary = true) {
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
        bar.addView(actionBtn(ctx, if (hasSel) "清空" else "全选", d) {
            if (hasSel) selected.clear()
            else wordsRef.indices.forEach { selected.add(it) }
            chipViews.forEachIndexed { i, v ->
                v.background = chipBg(d, i in selected)
                v.setTextColor(if (i in selected) Color.WHITE else C_TEXT)
            }
            updateCount()
            refreshActions(ctx, cfg, d)
        })
    }

    private fun refreshActions(ctx: Context, cfg: BigBangConfig, d: Float) {
        actionBar?.let { buildActions(ctx, it, cfg, d) }
    }

    private fun actionBtn(
        ctx: Context,
        label: String,
        d: Float,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(if (primary) Color.WHITE else C_TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * d).toInt(), (11 * d).toInt(), (12 * d).toInt(), (11 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14f * d
                setColor(if (primary) C_ACCENT else C_BTN)
            }
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            layoutParams = lp
        }
    }

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
            ctx.startActivity(
                Intent.createChooser(intent, "分享到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
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
