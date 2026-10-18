# FongMi / Quest 开发记录

基线：官方 `FongMi/TV` 的 `fongmi` 分支，提交 `b49b7027639edd42c7209651f9f0dab7150ae7ba`，源码版本 5.6.3。

## 当前交付范围

1. `quest-player/`：独立、可单独构建的 VR 影院伴侣项目。通过 FongMi 已有的外部播放器协议进入，详见 [使用说明](quest-player/README.md)。
2. 根项目新增 `quest` 变体：复用手机版选片界面，加入“VR 影院”按钮和沉浸式 Activity，接管原 `PlaybackService` 的同一个播放器。完整一体化 APK 仍依赖未公开在此源码树中的播放器 AAR，尚未完成编译或真机验证。

## 一体化实现

- 包名 `com.fongmi.android.tv.quest`，与官方安装包分开安装。
- Quest 专用依赖 Meta Spatial SDK 0.14.0，minSdk/targetSdk 34；其他变体不依赖此 SDK。
- 通过 Quest source set 复用 `src/mobile/java` 与 `src/mobile/res`。
- 暗色 VR 空间中放置 6×3.375 米、默认距离 6 米的视频屏幕，以及独立控制面板。尺寸和距离可调整并持久化。
- 使用 `PlayerView` 的 TextureView 输出到 Android 空间面板，保留播放器支持的标准字幕；没有新增解码器。
- 切换时阻止普通后台暂停和画中画抢占，返回后由原界面重新绑定播放器。影院不释放服务持有的播放器。
- 独立焦点策略处理系统菜单与 Activity 暂停的重复回调，保留用户主动暂停的状态。
- 暂停使用上游更新通道：官方没有 Quest 变体更新文件。
- 未配置正式签名时允许 debug 变体配置和构建。release 如需安装与分发，须配置自己的正式签名。

`src/quest/AndroidManifest.xml` 复用的普通 Activity 声明来自手机版；未来升级上游时应同步该部分。

## 完整版本构建条件

遵循根 README 的 JDK、SDK、Python 条件，将与此提交匹配的全部 `lib-*.aar` 放入 `app/libs/`。上游采用定制 Media3，不能直接用 Google 官方 Media3 替换：源码使用双字幕、libass、mpv、音画偏移、弹幕等非标准接口。

```sh
./gradlew :app:assembleQuestDebug
```

只启用 ARM64 APK 输出；正式签名配置齐全时可用 `:app:assembleQuestRelease`。

## 官方 APK 分析结论

检查日期：2026-10-18。官方最新发布包来源：

`https://github.com/FongMi/Release/releases/download/5.7.7/mobile-arm64_v8a.apk`

与当前公开源码 5.6.3 不是同一版本。APK 内含三个 DEX 和 ARM64 原生库，包括 `libffmpeg.so`、`libmedia3ass.so`、`libmedia3effect.so` 等。没有播放器 AAR 或包含完整播放器类的 JAR；assets 下的两个 JAR 属于 BD-J。

`apkanalyzer` 确认部分类型保留原名（例如 `androidx.media3.ui.PlayerView`），而源码需要的 `androidx.media3.common.Player`、`androidx.media3.exoplayer.libass.LibassPlaybackSession` 原名不在 DEX 中。名称缺失本身不能判断功能缺失，可能是混淆、优化或版本差异。没有取得配套映射和原始资源/依赖元数据，不能把 APK 的 DEX/so 直接当作可复现构建的 AAR。

因此没有用反编译结果伪造依赖。独立影院以官方公开的外部播放协议和公开 Media3 完成播放；一体化分支保留，等待精确配套依赖。

## 本次验证

- 独立项目 `:app:assembleDebug` 成功，产物为 ARM64 测试 APK。
- `:app:testDebugUnitTest`：8 项测试通过，覆盖请求头、进度参数、异常保存参数与菜单焦点恢复。
- `:app:lintDebug` 通过（仍有依赖版本、目标 API、ChromeOS 架构等提示；项目明确面向 Quest）。
- APK Signature Scheme v2 验证通过；APK 对齐检查通过；安装清单确认 minSdk/targetSdk 为 34，native ABI 仅 arm64-v8a。
- 根项目构建在 `:chaquo:installDebugPythonRequirements` 因当前环境缺少 Python 3.10 停止；尚未到完整应用编译。除该环境条件外，上述定制播放器 AAR 也没有恢复。
- `adb devices` 没有连接设备，未执行 Quest 3 安装、画面、手柄或 FongMi 跨应用联动的真机测试。
