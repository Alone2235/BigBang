package com.eta.bigbang

/** 配置键名与常量 */
object Config {
    const val PREFS_NAME = "bigbang_prefs"
    const val MODULE_PKG = "com.eta.bigbang"

    // 生效范围模式
    const val KEY_SCOPE_MODE = "scope_mode"          // global / whitelist / recommended
    const val KEY_WHITELIST = "whitelist_pkgs"       // Set<String>
    const val KEY_BLACKLIST = "blacklist_pkgs"       // Set<String>，全局模式下排除

    // 搜索引擎
    const val KEY_SEARCH_ENGINE = "search_engine"    // 预设 id
    const val KEY_SEARCH_TEMPLATE = "search_template" // 自定义模板，含 {query}

    // 浏览器
    const val KEY_BROWSER_PKG = "browser_pkg"        // 空 = 系统默认

    // 行为
    const val KEY_ENABLE_SEARCH = "enable_search"
    const val KEY_ENABLE_COPY = "enable_copy"
    const val KEY_ENABLE_VIBRATE = "enable_vibrate"
    const val KEY_MAX_BLOCKS = "max_blocks"          // 最多展示词块数
    const val KEY_ENABLE_HOOK = "enable_hook"

    // 生效范围模式取值
    const val MODE_GLOBAL = "global"
    const val MODE_WHITELIST = "whitelist"
    const val MODE_RECOMMENDED = "recommended"
}

/** 完整配置快照 */
data class BigBangConfig(
    val scopeMode: String,
    val whitelist: Set<String>,
    val blacklist: Set<String>,
    val searchEngineId: String,
    val searchTemplate: String,
    val browserPkg: String,
    val enableSearch: Boolean,
    val enableCopy: Boolean,
    val enableVibrate: Boolean,
    val maxBlocks: Int,
    val enableHook: Boolean,
) {
    /** 该包名在当前配置下是否生效 */
    fun isActiveFor(pkg: String): Boolean {
        if (!enableHook) return false
        return when (scopeMode) {
            Config.MODE_WHITELIST -> pkg in whitelist
            Config.MODE_RECOMMENDED -> pkg in RecommendedApps.PACKAGES
            else -> pkg !in blacklist
        }
    }

    /**
     * hook 侧判断：进程已被 LSPosed 注入即视为作用域允许，
     * 这里只做额外排除，避免"模块白名单与 LSPosed 作用域两处配置打架"。
     *
     * - 全局模式：走黑名单
     * - 白名单模式：只有设置了白名单才收窄，未设置则全部放行
     * - 推荐模式：未手动改白名单时全部放行
     */
    fun isAllowedHere(pkg: String): Boolean {
        if (pkg in blacklist) return false
        return when (scopeMode) {
            Config.MODE_WHITELIST -> whitelist.isEmpty() || pkg in whitelist
            Config.MODE_RECOMMENDED -> whitelist.isEmpty() || pkg in whitelist
            else -> true
        }
    }
}

/** 预设搜索引擎 */
data class SearchEngine(
    val id: String,
    val name: String,
    val template: String,
)

object SearchEngines {
    val ALL = listOf(
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q={query}"),
        SearchEngine("baidu", "百度", "https://www.baidu.com/s?wd={query}"),
        SearchEngine("google", "Google", "https://www.google.com/search?q={query}"),
        SearchEngine("sogou", "搜狗", "https://www.sogou.com/web?query={query}"),
        SearchEngine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q={query}"),
        SearchEngine("yandex", "Yandex", "https://yandex.com/search/?text={query}"),
        SearchEngine("zhihu", "知乎", "https://www.zhihu.com/search?type=content&q={query}"),
        SearchEngine("bilibili", "B站", "https://search.bilibili.com/all?keyword={query}"),
        SearchEngine("custom", "自定义", ""),
    )

    fun byId(id: String): SearchEngine = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** 用词替换模板得到最终 URL */
    fun buildUrl(template: String, word: String): String {
        val encoded = java.net.URLEncoder.encode(word, "UTF-8")
        return template.replace("{query}", encoded)
    }
}

/** 作者推荐配置：长文本阅读 / 聊天 / 资讯类应用 */
object RecommendedApps {
    val PACKAGES = listOf(
        // 阅读 / 资讯
        "com.ss.android.article.news",      // 今日头条
        "com.tencent.news",                 // 腾讯新闻
        "com.netease.newsreader.activity",  // 网易新闻
        "com.zhihu.android",                // 知乎
        "com.douban.frodo",                 // 豆瓣
        "com.ss.android.ugc.aweme",         // 抖音
        "tv.danmaku.bili",                  // B站
        "com.xingin.xhs",                   // 小红书
        // 聊天 / 社交
        "com.tencent.mm",                   // 微信
        "com.tencent.mobileqq",             // QQ
        "org.telegram.messenger",           // Telegram
        // 邮件 / 办公
        "com.android.email",
        "com.tencent.androidqqmail",        // QQ邮箱
        "com.microsoft.office.outlook",
        // 浏览器
        "com.UCMobile",
        "com.mmbox.xbrowser.pro",
        "com.vivo.browser",
    )
}
