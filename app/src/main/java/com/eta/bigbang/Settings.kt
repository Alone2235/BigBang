package com.eta.bigbang

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences

/**
 * 配置存取。
 * UI 侧用普通 SharedPreferences 写；hook 进程用 XSharedPreferences 读。
 */
object Settings {

    fun defaults(): BigBangConfig = BigBangConfig(
        scopeMode = Config.MODE_WHITELIST,
        whitelist = emptySet(),
        blacklist = defaultBlacklist(),
        searchEngineId = "bing",
        searchTemplate = SearchEngines.byId("bing").template,
        browserPkg = "",
        enableSearch = true,
        enableCopy = true,
        enableVibrate = true,
        maxBlocks = 200,
        enableHook = true,
    )

    /** 全局模式下也要跳过的敏感界面 */
    private fun defaultBlacklist(): Set<String> = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.miui.securitycenter",
        "com.android.phone",
        "com.android.incallui",
        "com.miui.home",
    )

    /** UI 侧读取 */
    fun loadFromUi(ctx: Context): BigBangConfig =
        read(ctx.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)) ?: defaults()

    /** UI 侧写入 */
    fun saveFromUi(ctx: Context, cfg: BigBangConfig) {
        ctx.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(Config.KEY_SCOPE_MODE, cfg.scopeMode)
            .putStringSet(Config.KEY_WHITELIST, cfg.whitelist)
            .putStringSet(Config.KEY_BLACKLIST, cfg.blacklist)
            .putString(Config.KEY_SEARCH_ENGINE, cfg.searchEngineId)
            .putString(Config.KEY_SEARCH_TEMPLATE, cfg.searchTemplate)
            .putString(Config.KEY_BROWSER_PKG, cfg.browserPkg)
            .putBoolean(Config.KEY_ENABLE_SEARCH, cfg.enableSearch)
            .putBoolean(Config.KEY_ENABLE_COPY, cfg.enableCopy)
            .putBoolean(Config.KEY_ENABLE_VIBRATE, cfg.enableVibrate)
            .putInt(Config.KEY_MAX_BLOCKS, cfg.maxBlocks)
            .putBoolean(Config.KEY_ENABLE_HOOK, cfg.enableHook)
            .apply()
    }

    /** hook 侧读取，失败回退默认 */
    fun loadForHook(): BigBangConfig = try {
        val xp = XSharedPreferences(Config.MODULE_PKG, Config.PREFS_NAME)
        xp.reload()
        read(xp) ?: defaults()
    } catch (t: Throwable) {
        defaults()
    }

    private fun read(sp: SharedPreferences): BigBangConfig? {
        val d = defaults()
        return BigBangConfig(
            scopeMode = sp.getString(Config.KEY_SCOPE_MODE, d.scopeMode) ?: d.scopeMode,
            whitelist = sp.getStringSet(Config.KEY_WHITELIST, d.whitelist) ?: d.whitelist,
            blacklist = sp.getStringSet(Config.KEY_BLACKLIST, d.blacklist) ?: d.blacklist,
            searchEngineId = sp.getString(Config.KEY_SEARCH_ENGINE, d.searchEngineId) ?: d.searchEngineId,
            searchTemplate = sp.getString(Config.KEY_SEARCH_TEMPLATE, d.searchTemplate) ?: d.searchTemplate,
            browserPkg = sp.getString(Config.KEY_BROWSER_PKG, d.browserPkg) ?: d.browserPkg,
            enableSearch = sp.getBoolean(Config.KEY_ENABLE_SEARCH, d.enableSearch),
            enableCopy = sp.getBoolean(Config.KEY_ENABLE_COPY, d.enableCopy),
            enableVibrate = sp.getBoolean(Config.KEY_ENABLE_VIBRATE, d.enableVibrate),
            maxBlocks = sp.getInt(Config.KEY_MAX_BLOCKS, d.maxBlocks),
            enableHook = sp.getBoolean(Config.KEY_ENABLE_HOOK, d.enableHook),
        )
    }
}
