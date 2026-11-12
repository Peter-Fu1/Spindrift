# Spindrift

Spindrift是一个小型的米游信息聚合查看工具，支持显示中国区官服原神、崩坏：星穹铁道和绝区零账号的实时便笺关键数据，并且自带米游社每日自动签到功能。

应用主要分为`资源总览`和`签到明细`页面。在使用前，用户需通过上方导航栏按键先登录通行证账号。
在`资源总览`页面，用户可以查看目前已登录的通行证账号下已绑定的游戏角色的实时便笺数据；
可通过上方导航栏的筛选功能，筛选需要显示/隐藏的游戏角色，开启/关闭游戏角色的自动签到功能；
在`签到明细`页面，用户可以查看每个已登录的通行证账号下已绑定的游戏角色的每日签到明细数据，如`今日签到状态`、`本月签到明细`等；
同时可以手动刷新今日签到状态或对已勾选角色进行手动签到操作。

在资源总览主动联网刷新时，签到查询覆盖本次选中角色和全部开启自动签到的角色。开启自动签到的角色满足条件时会尝试签到。签到明细的状态刷新则只会查询，不会尝试签到。

## 安装与构建

Android Main 1.0.0（versionCode 20）已在本地正式签署和验证。Main 包名为 `io.github.peterfu.hoyoreminder`，最低支持 Android 9（API 28）。在 Android 上覆盖安装要求包名和签名相同，升级包的版本码须更高。Debug 与 Preview 使用独立应用 ID。版本记录见 [CHANGELOG.md](CHANGELOG.md)。

源码构建使用 JDK 17、Android SDK API 35、Build Tools 35.0.0 和仓库自带的 Gradle Wrapper。设置 `ANDROID_HOME`，或在本地 `local.properties` 中配置 SDK 路径。运行 Web 和 Python 校验另需 Node.js 22、Python 3.12 和 Pillow 13.0.0。

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python tools/verify_apk_ui.py app/build/outputs/apk/release/app-release-unsigned.apk
```

在 Windows 上使用 `gradlew.bat`。具备本地环境时优先本地构建。普通 Gradle Release 与 CI 的 Main 构建输出未签名包。正式 Main 使用本机受保护的签名流程。[Main 发布工具](tools/MAIN_RELEASE.md)

Android 模块位于 `app/`，正式 Web 界面位于 `web/`，Web 测试位于 `tests/web/`。[浏览器界面预览](tools/ui-preview/README.md)

## 数据与许可

应用只在本地保存账号与设置，登录凭据通过 Android Keystore 保护。登录、实时便笺、签到和验证会访问相应服务。接口及风控规则可能随服务更新而变化。反馈问题时请使用脱敏信息。

自有代码采用 [MIT](LICENSE)。
参考项目和第三方内容见`THRID_PARTY_NOTICE.md`。
