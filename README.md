# 大爆炸 (BigBang)

LSPosed 模块：长按文本 → 分词成块 → 点词搜索 / 长按复制。

灵感来自锤子 Smartisan OS 的「大爆炸」，把整段文字炸成可点的词块，省去在长段落里反复拖动光标挑词的麻烦。

## 功能

- **分词成块**：用系统 ICU `BreakIterator` 离线切分，中文按词、英文按词与数字，零词典体积
- **点词搜索**：点词块直接用你选的搜索引擎打开
- **长按复制**：长按词块只复制，不跳转
- **生效范围三档**
  - 白名单：只在你勾选的应用里生效
  - 全局：所有应用生效，自动跳过系统界面 / 电话 / 设置等敏感页
  - 作者推荐：内置阅读 / 聊天 / 资讯类精选列表，可一键复制成自己的白名单
- **搜索引擎可换**：Bing / 百度 / Google / 搜狗 / DuckDuckGo / Yandex / 知乎 / B站，或自定义 `{query}` 模板
- **浏览器可换**：从已安装的、能响应 https 的应用里选，用 `setPackage()` 精确打开；不选则跟随系统默认

## 使用

1. 安装 APK，在 LSPosed 中启用本模块
2. 勾选作用域应用（与模块内「生效范围」保持一致）
3. 重启目标应用
4. 在目标应用里长按文本，底部弹出词块层

悬浮窗权限：首次使用请在系统设置中允许本模块「显示在其他应用上层」。

## 构建

推送到 `main` 触发 GitHub Actions，APK 自动挂到 Release：

```
git push origin main
```

本地构建：`gradle :app:assembleDebug`

## 技术说明

- 编译期依赖经典 Xposed API 82（`compileOnly`），运行时由 LSPosed 注入
- Hook 点：`android.widget.Editor` 的选择动作模式入口；部分 ROM 若无此方法，回退挂 `TextView.performLongClick`
- 弹层通过 `WindowManager` + `TYPE_APPLICATION_OVERLAY` 叠加，不依赖被 hook 应用窗口，也不触发后台启动 Activity 限制
- 配置用 `XSharedPreferences` 从 UI 侧传至 hook 侧

## 环境

- Android 8.0+ (minSdk 26)，已测 Android 16 / HyperOS 3
- LSPosed（需要 root）

## 许可

个人使用，未附许可协议。
