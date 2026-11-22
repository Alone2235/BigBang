package com.eta.bigbang

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 爆词弹层。
 *
 * 视觉：白蓝浅色玻璃（HyperBlue 体系）。
 * 动效：真正的「爆炸」——
 *  1. 从长按点闪出一圈冲击波（自定义 View 绘制扩散圆环）
 *  2. 每个词块从中心点朝自己的位置飞出，带旋转、缩放、透明度变化
 *  3. 飞到位后回弹落定
 *  4. 关闭时反向收拢
 */
object BlastOverlay {

    private var panel: View? = null
    private var windowManager: WindowManager? = null
    private var shockwave: View? = null
    private val main = Handler(Looper.getMainLooper())

    // ---- 白蓝配色 ----
    private const val C_PANEL = 0xE6FFFFFF.toInt()
    private const val C_CHIP = 0xFFF2F4F8.toInt()
    private const val C_CHIP_ON = 0xFF4A8CFF.toInt()
    private const val C_TEXT = 0xFF1B1F27.toInt()
    private const val C_TEXT_DIM = 0xFF878D99.toInt()
    private const val C_DIVIDER = 0x14000000
    private const val C_BTN = 0xFFEDF2FB.toInt()
    private const val C_ACCENT = 0xFF4A8CFF.toInt()
    private const val C_STROKE = 0x0F1B1F27

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
        val dm = ctx.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // ---- 顶部 ----
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
        header.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
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

        // ---- 操作条 ----
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
        }

        val panelH = (460 * d).toInt()
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

        // 面板初始完全透明：先让冲击波扩散，面板随后浮现，避免一上来就盖住冲击波
        val centerX = screenW / 2f
        val centerY = screenH / 2f
        panelView.pivotX = centerX
        panelView.pivotY = centerY
        panelView.scaleX = 0.82f
        panelView.scaleY = 0.82f
        panelView.alpha = 0f

        try {
            wm.addView(panelView, params)
        } catch (t: Throwable) {
            android.util.Log.w("BigBang", "addView failed: ${t.javaClass.simpleName} ${t.message}")
            toast(ctx, "大爆炸：无法显示浮层（${t.javaClass.simpleName}）")
            return
        }
        panel = panelView
        windowManager = wm

        // ---- 时间轴 ----
        // t=0    冲击波开始扩散
        // t=120  面板淡入 + 放大（此时冲击波已扩散一段，不会被立刻盖住）
        // t=260  词块从中心飞散
        spawnShockwave(ctx, wm, centerX, centerY, d)

        panelView.animate()
            .alpha(1f)
            .scaleX(1f).scaleY(1f)
            .setStartDelay(120)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()

        // ---- 词块从中心飞散 ----
        panelView.post {
            val total = chipViews.size.coerceAtLeast(1)
            val rows = ((total + 3) / 4).coerceAtLeast(1)
            val radius = (460 * d)

            chipViews.forEachIndexed { i, v ->
                val row = i / 4
                val col = i % 4
                val nx = if (4 > 1) (col - 1.5f) / 1.5f else 0f
                val ny = if (rows > 1) (row - (rows - 1) / 2f) / ((rows - 1) / 2f + 0.5f) else 0f

                v.translationX = -nx * radius * 1.35f
                v.translationY = -ny * radius * 1.0f - 80f * d
                v.scaleX = 0.1f
                v.scaleY = 0.1f
                v.alpha = 0f
                v.rotation = nx * 55f

                v.animate()
                    .translationX(0f).translationY(0f)
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .rotation(0f)
                    .setStartDelay(260L + i * 16L)
                    .setDuration(700L)
                    .setInterpolator(OvershootInterpolator(1.9f))
                    .start()
            }
        }

        panelView.isClickable = true
        panelView.setOnClickListener { dismiss() }
    }

    /** 从长按点扩散的冲击波圆环（全屏窗口，保证不被遮挡） */
    private fun spawnShockwave(ctx: Context, wm: WindowManager, cx: Float, cy: Float, d: Float) {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val view = ShockwaveView(ctx, w, h, cx, cy)
        val lp = WindowManager.LayoutParams(
            w, h,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { wm.addView(view, lp) }.onFailure {
            android.util.Log.w("BigBang", "shockwave add failed: ${it.message}")
            return
        }
        shockwave = view

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { view.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runCatching { wm.removeView(view) }
                    if (shockwave === view) shockwave = null
                }
            })
            start()
        }
    }

    /** 冲击波：从长按点扩散的双层圆环 + 中心闪光 */
    private class ShockwaveView(
        ctx: Context,
        private val w: Int,
        private val h: Int,
        private val cx: Float,
        private val cy: Float,
    ) : View(ctx) {
        var progress: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF4A8CFF.toInt()
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF4A8CFF.toInt()
        }
        private val density = ctx.resources.displayMetrics.density
        private val maxR = (hypot(w.toFloat(), h.toFloat()) * 0.55f)

        override fun onDraw(canvas: Canvas) {
            val p = progress.coerceIn(0f, 1f)

            // 中心闪光：快速放大淡出
            if (p < 0.5f) {
                val k = p / 0.5f
                val flashR = (30f + 180f * k) * density
                glowPaint.alpha = ((1f - k) * 170).toInt()
                canvas.drawCircle(cx, cy, flashR, glowPaint)
            }

            // 主圆环
            val r1 = (20f * density) + maxR * p
            ringPaint.strokeWidth = (4f * (1f - p) + 1.5f) * density
            ringPaint.alpha = ((1f - p) * 220).toInt()
            canvas.drawCircle(cx, cy, r1, ringPaint)

            // 副圆环（延后、更淡）
            if (p > 0.12f) {
                val p2 = (p - 0.12f) / 0.88f
                val r2 = (12f * density) + maxR * 0.78f * p2
                ringPaint.alpha = ((1f - p2) * 120).toInt()
                ringPaint.strokeWidth = (2.5f * (1f - p2) + 1f) * density
                canvas.drawCircle(cx, cy, r2, ringPaint)
            }
        }
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
                // 选中时轻微弹一下
                animate().scaleX(0.88f).scaleY(0.88f).setDuration(60)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(OvershootInterpolator(2.5f)).start()
                    }.start()
            }

            setOnLongClickListener {
                copy(ctx, word)
                toast(ctx, "已复制「$word」")
                animate().scaleX(1.12f).scaleY(1.12f).setDuration(90)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                    }.start()
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
        val sw = shockwave
        shockwave = null
        runCatching { sw?.let { wm?.removeView(it) } }
        windowManager = null

        // 收拢动画：缩放 + 淡出
        p.animate()
            .alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction { runCatching { wm?.removeView(p) } }
            .start()
    }
}
