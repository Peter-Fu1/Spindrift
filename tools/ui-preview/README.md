# Windows 浏览器 UI Preview

此入口只用于编辑 Android 界面；不需要 Android Studio、SDK、Gradle、Node 或 Python。不要将它与诊断 APK 的 Preview 构建混淆。

## VS Code + Live Server

1. 用 VS Code 打开 Android 仓库根目录（主开发目录或独立 UI Worktree 都可以），安装/启用 Live Server 扩展。
2. 在文件列表右键 `tools/ui-preview/index.html`，选择 **Open with Live Server**。不要直接双击文件，也不要从正式 `web/index.html` 开始。
3. 浏览器使用本机地址，例如 `http://127.0.0.1:5500/tools/ui-preview/index.html`，点击 **开启本地预览**。
4. 在上方切换场景和视口。默认场景展开虚拟账号 A 的三游戏卡片，B 只有原神，C 有三游戏。
5. 修改 `web/` 中的 `index.html`、`styles.css`、`app.js`、`checkin-ui.js`、`sms-ui.js` 等正式 UI 文件并保存。Live Server 刷新外层入口；入口重新读取最新正式 HTML，iframe 重新加载正式 CSS/JS。若浏览器没有自动刷新，按 Ctrl+R 或点“重置当前场景”。

可收藏显式启用的地址：

```text
http://127.0.0.1:5500/tools/ui-preview/index.html?preview=1&scenario=default&viewport=393x780
```

端口及仓库前缀随 Live Server 配置变化；必须让服务能访问仓库根目录。若 Live Server root 设置成 `tools/ui-preview`，改回仓库根目录。只有 `localhost`、`127.0.0.1`、`[::1]` 的 HTTP(S) 被接受；局域网 IP、远程地址、`file://`、APK 和原生桥环境均拒绝启动。手机尺寸指 iframe 的 CSS 像素；浏览器窗口小于选定宽度时实际宽度受限，请放大桌面窗口。通过 DevTools 可进一步检查文字缩放和 CSS。

## 场景与交互

- `default`：三个虚拟账号；默认展开 A，三游戏资源、任务与不同签到状态。
- `genshin-only`：只有一个原神角色，便于检查缺少其他游戏时的布局。
- `errors`：5003 / 1034 / 10041、过期登录、网络错误和上次成功缓存。
- `empty`：未登录空白界面。
- `long-text`：长账号名、昵称、区服及连续英文数字。
- `checkin`：直接显示签到明细，切换角色可看已签/未签/需验证；奖励默认折叠。
- `login`：打开正式登录弹窗，扫码/短信页签可以查看，真实操作禁用。

菜单、账号展开/收起、账号选择/移除、排序、筛选、本地估算、资源刷新、签到与补签确认均只改变内存中的虚拟状态。资源刷新立即显示模拟完成状态；错误场景保持错误，不模拟真正解除风控。补签演示费用/余额也是虚拟值，不查询或消耗米游币。刷新页面或切换场景重置全部数据；没有跨刷新持久化。

## 隔离与正式 APK

入口动态读取正式 `index.html`，在内存里仅把 `platform-adapter.js` 替换为预览适配器，并先加载虚拟数据。其余 HTML/CSS/JS 和渲染逻辑直接复用正式文件，不维护第二套主页面。只加载已知正式脚本；Live Server 注入的重载脚本不进入 iframe，外层入口负责重载。

预览适配器不调用 Native Bridge、后端 HTTP、Cookie、Token、设备 ID 或 FP；敏感 RPC 默认拒绝。虚拟头像/奖励图使用 data URL。iframe 的 CSP 禁止所有 fetch/XHR/WebSocket/Beacon 连接（包括本机 `/api/`）、外部图片、子框架与 Worker；外层仅允许本机静态读取及 Live Server 本机重载连接。侧边栏的 localStorage 也替换为当前 iframe 内存，避免读写同一来源其他页面的偏好。

正式 `index.html` 和 `platform-adapter.js` 没有预览开关；在正式页添加 `?preview=1` 仍然要求原生桥。Gradle 使用原有显式资源白名单，`tools/ui-preview/` 不进入任何 APK，固定 UA、正式 FP、账号存储和 GitHub Actions 构建流程不变。

## 测试（开发者可选）

无需测试工具也能预览。若要运行回归，需要 Node；真实浏览器测试另外需要 Playwright 和本机 Chrome：

```powershell
node --test tests/web/*.test.mjs
# 如 Playwright 不在默认模块路径，设置 PLAYWRIGHT_MODULE；如使用已安装 Chrome，设置 CHROME_PATH。
node tools/check_ui_preview.cjs
```

浏览器测试结果/截图输出到忽略目录 `work/ui-preview-tests/`。它使用真实 Chrome 和本地静态 HTTP 服务；数据均为虚拟，不能证明真实账号的登录、FP 或官方风控已修复。
