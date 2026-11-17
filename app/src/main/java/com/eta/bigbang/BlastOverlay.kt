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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 爆词弹层。
 *
 * 命名取自「炸开」的意象：
 * - fuse   引线（顶部标题条）
 * - shard  碎片（单个词块）
 * - blast  炸开动作（整体入场）
 */
object BlastOverlay {

    private var panel: View? = null
    private var windowManager: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    /** 词块入场间隔 */
    private const val SHARD_STAGGER_MS = 22L
    private const val SHARD_ANIM_MS = 260L

    fun show(ctx: Context, words: List<String>, cfg: BigBangConfig) {
        main.post {
            runCatching { blast(ctx, words, cfg) }
                .onFailure { android.util.Log.w("BigBang", "blast failed: ${it.message}") }
        }
    }

    /**
     * 说明：悬浮窗权限不用 canDrawOverlays 预先判断。
     *
     * 之前用宿主 context 调 Settings.canDrawOverlays()，查的是微信/传送门有没有
     * 悬浮窗权限——它们当然没有，于是永远判定为"无权限"，即使模块自己已授权。
     * 现在改为直接 addView 并捕获真实异常，既不会误报，失败时也能拿到确切原因。
     */

    private fun blast(ctx: Context, words: List<String>, cfg: BigBangConfig) {
        dismiss()

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val d = ctx.resources.displayMetrics.density

        // ---- 引线：标题条 ----
        val fuse = TextView(ctx).apply {
            text = "大爆炸 · ${words.size} 块"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
        }

        val divider = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }

        // ---- 碎片容器 ----
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
        }

        val shards = ArrayList<View>()
        words.chunked(4).take(24).forEach { rowWords ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowWords.forEach { w ->
                val shard = buildShard(ctx, w, cfg, d)
                shards.add(shard)
                row.addView(shard)
            }
            // 补空占位，保持列对齐
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

        val panelView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 22f * d
                setColor(Color.parseColor("#F2161A20"))
            }
            elevation = 18f * d
            addView(fuse)
            addView(divider)
            addView(
                scroller,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (340 * d).toInt()
                )
            )
            // 入场：从下方弹起 + 缩放 + 透明渐显
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

        // 直接尝试添加，用真实结果判断权限，而不是靠 canDrawOverlays 猜。
        // 跨进程场景下 canDrawOverlays 的判断并不可靠。
        try {
            wm.addView(panelView, params)
        } catch (t: Throwable) {
            android.util.Log.w("BigBang", "addView failed: ${t.javaClass.simpleName} ${t.message}")
            runCatching {
                Toast.makeText(
                    ctx,
                    "大爆炸：无法显示浮层（${t.javaClass.simpleName}）",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        panel = panelView
        windowManager = wm

        // 面板入场动画
        panelView.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        // 词块错峰弹入
        shards.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 26f * d
            v.scaleX = 0.6f
            v.scaleY = 0.6f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(60L + i * SHARD_STAGGER_MS)
                .setDuration(SHARD_ANIM_MS)
                .setInterpolator(OvershootInterpolator(1.8f))
                .start()
        }

        // 点空白处收起
        panelView.isClickable = true
        panelView.setOnClickListener { dismiss() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildShard(
        ctx: Context,
        word: String,
        cfg: BigBangConfig,
        d: Float,
    ): View {
        val tv = TextView(ctx).apply {
            text = word
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 11f * d
                setColor(Color.parseColor("#FF2E6BFF"))
            }

            setOnClickListener { v ->
                // 按下回弹
                v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                    .withEndAction {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    }.start()

                if (cfg.enableSearch) search(ctx, word, cfg)
                if (cfg.enableCopy) copy(ctx, word)
                toast(ctx, if (cfg.enableCopy) "已复制「$word」" else "搜索「$word」")
                main.postDelayed({ dismiss() }, 200)
            }

            setOnLongClickListener {
                copy(ctx, word)
                toast(ctx, "已复制「$word」")
                main.postDelayed({ dismiss() }, 150)
                true
            }
        }

        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        tv.layoutParams = lp
        return tv
    }

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

    /** 收起弹层：播放退出动画后再移除 */
    fun dismiss() {
        val p = panel ?: return
        val wm = windowManager
        panel = null
        windowManager = null

        val d = p.resources.displayMetrics.density
        p.animate()
            .alpha(0f)
            .translationY(60f * d)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                runCatching { wm?.removeView(p) }
            }
            .start()
    }
}
