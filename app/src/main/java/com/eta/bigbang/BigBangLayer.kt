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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 爆词弹层。
 *
 * 用 WindowManager 直接在目标应用窗口之上叠加一个不可聚焦的浮层，
 * 不依赖被 hook 应用自己的 Activity，也不触发后台启动 Activity 限制。
 */
object BigBangLayer {

    private var root: View? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())

    /** 展示词块 */
    fun show(ctx: Context, words: List<String>, cfg: BigBangConfig) {
        main.post {
            runCatching { showInner(ctx, words, cfg) }
                .onFailure { android.util.Log.w("BigBang", "show failed: ${it.message}") }
        }
    }

    private fun showInner(ctx: Context, words: List<String>, cfg: BigBangConfig) {
        dismiss()

        // 悬浮窗权限：没有就不弹，避免直接抛异常
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(ctx)
        ) {
            android.util.Log.w("BigBang", "缺少悬浮窗权限，跳过弹层")
            return
        }

        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = ctx.resources.displayMetrics.density

        // 顶部一条：动作提示条
        val header = TextView(ctx).apply {
            text = "大爆炸 · ${words.size} 块"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (10 * density).toInt(),
                (16 * density).toInt(), (10 * density).toInt())
        }

        // 词块流式布局：用纵向 LinearLayout 装若干横向行，简单可靠
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (6 * density).toInt(),
                (10 * density).toInt(), (6 * density).toInt())
        }

        val perRow = 4
        words.chunked(perRow).take(24).forEach { rowWords ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowWords.forEach { w ->
                row.addView(makeChip(ctx, w, cfg, density))
            }
            grid.addView(row)
        }

        val scroll = ScrollView(ctx).apply {
            addView(grid)
            isVerticalScrollBarEnabled = false
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(Color.parseColor("#EE1B1F24"))
            }
            addView(header)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
            })
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (320 * density).toInt()
            ))
        }

        // 点空白处关闭
        container.isClickable = true
        container.setOnClickListener { dismiss() }

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
            y = (48 * density).toInt()
        }

        windowManager.addView(container, params)
        root = container
        wm = windowManager
        lp = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeChip(
        ctx: Context,
        word: String,
        cfg: BigBangConfig,
        density: Float,
    ): View {
        val tv = TextView(ctx).apply {
            text = word
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((14 * density).toInt(), (9 * density).toInt(),
                (14 * density).toInt(), (9 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#FF2E6BFF"))
            }
            setOnClickListener { v ->
                if (cfg.enableSearch) search(ctx, word, cfg)
                if (cfg.enableCopy) copy(ctx, word)
                toast(ctx, "「$word」")
                v.animate().alpha(0.6f).setDuration(90).withEndAction {
                    v.animate().alpha(1f).setDuration(120).start()
                }.start()
                main.postDelayed({ dismiss() }, 220)
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())

        // 长按可选块后走「只复制、不搜索」
        tv.setOnLongClickListener {
            copy(ctx, word)
            toast(ctx, "已复制「$word」")
            main.postDelayed({ dismiss() }, 150)
            true
        }
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
        runCatching { ctx.startActivity(intent) }
            .onFailure {
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.also { i -> runCatching { ctx.startActivity(i) } }
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

    /** 关掉当前弹层 */
    fun dismiss() {
        val r = root ?: return
        val w = wm
        runCatching { w?.removeView(r) }
        root = null
        wm = null
        lp = null
    }
}
