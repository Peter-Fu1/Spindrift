# HoYoReminder Android 项目约束

## UA 兼容契约
中国区战绩/实时便笺的原生请求与官方 H5 WebView 的完整 UA 固定为：
Mozilla/5.0 (Linux; Android 15) Mobile miHoYoBBS/2.102.1

唯一来源为 Models.kt 的 Protocol.USER_AGENT，协议版本 Protocol.VERSION 固定 2.102.1。
不得按产品版本升级、替换系统浏览器 UA、增加 Chrome 段或产品后缀。
仅用户在任务中明确授权才可调整；不得顺带修改扫码、Passport、角色发现、主界面或验证码组件既有 UA。
Android 实际系统版本独立记录，不伪造成 UA 中的版本。

## 设备与凭据
FP、pending、seed、Cookie 归属米游社账号；候选 FP 不得用于正式便笺。
保留既有 Keystore、数据库和设备身份；禁止通过卸载清数据实现迁移。
诊断不含原始 FP、设备 ID、seed、属性明文、凭据或可重建身份的哈希。

## 用户UI文案与APK资源契约

- 正式Android UI源码位于 web/：index.html、app.js、checkin-ui.js、sms-ui.js、styles.css、platform-adapter.js等。docs/index.html、旧UI Worktree和Windows shared-ui不是本仓库APK的替代输入。
- 用户明确要求保留并纳入APK的手动UI文字修改，应先备份、核对并提交到本次构建的Git版本；不能默默留在工作树后用旧HEAD的HTML/JS构建，也不能从旧副本覆盖这些文字。未知来源的其他功能变更仍按当前任务授权范围处理。
- 云端构建只读取已推送的提交。构建前核对实际分支、待提交UI和已推送SHA；打包后必须执行tools/verify_apk_ui.py，确保APK内11项正式web资源与本次源码经仅CRLF/LF规范化后逐字节相同，并记录原始及规范化SHA256，并保留apk-ui-verification.json证据。发现旧文件、缺文件或开发预览资源时不得作为验证通过的产物交付。
- 新增正式web资源时同步维护Gradle webAssets白名单与verify_apk_ui.py的UI_FILES；不要把docs或tools/ui-preview整体加入APK。
- UI改字不授权修改UA、协议版本、deviceFP、Cookie、设备身份或账号数据库；上方既有冻结约束继续适用。

## 正式 Main 发布身份
- 正式包固定io.github.peterfu.hoyoreminder，首发0.1.16/code18；从0.1.17/code19开始安装名固定Spindrift；Preview保持原独立包名和证书。
- tools/main-release-signing-lock.json锁定正式公开证书。正式私钥在仓库外main-release-v1.p12；缺失必须停止，不得自动创建替代密钥或使用Preview/debug证书。已有身份拒绝重新init。
- 正式发布使用tools/release_main.py；检查包名、非debuggable、11项资源、源码SHA、SDK、对齐回执、V2/V3证书和高于released台账最大值的code。
- 两处独立加密备份（含主机外）与恢复签名演练通过才可ready/released。candidate不等于正式ready，DPAPI/旁置副本不替代独立灾备。
- 私钥、口令不入Git/CI/缓存/artifact。mainUpgradeTest仅隔离CI使用临时测试证书，不把高code测试APK发布为0.1.17，不触碰用户手机/真实账号。
