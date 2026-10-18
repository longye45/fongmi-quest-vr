# FongMi Quest VR 影院

这是配合官方 FongMi 使用的独立影院原型，包名 `com.fongmi.quest.cinema`，不是已完成的 FongMi 一体化发行版。面向 Quest 3 / Quest 3S、Android 14 与 Horizon OS v69 及以上。没有连接头显进行真机验收。

## 使用

1. 在 Quest 开启开发者模式，通过 Meta Quest Developer Hub 或 `adb install -r fongmi-quest-cinema-debug.apk` 安装测试包。
2. 在官方 FongMi 中正常选片并开始播放，通过播放设置中的 **播放器 → 其它** 打开外部播放器选择入口，选择 **FongMi VR 影院**。全屏时也可直接点击当前播放器名称（如 Exo）。具体菜单名称可能随 FongMi 版本变化。
3. 影院使用固定于虚拟空间的大屏，手柄射线点击下方控制面板。支持播放/暂停、前后 10 秒、屏宽 3–10 米、距离 3–12 米，以及恢复默认位置。屏幕尺寸和距离会记住。
4. 按“返回选片”退出。可长按 Quest 右手柄 Meta 键，使用系统重新居中功能调整正前方。
5. 也可直接启动本应用，输入可播放的 HTTP/HTTPS 地址，或通过系统文件选择器打开本地视频。

通过 ADB 单独验证一个自行提供的直链：

```sh
adb shell am start -n com.fongmi.quest.cinema/.EntryActivity \
  -a android.intent.action.VIEW -d 'https://example.com/movie.mp4' -t 'video/mp4'
```

## 已实现的协议

- `ACTION_VIEW` + `video/*`，接收 HTTP、HTTPS、content、file URI。
- 接收 FongMi 的 `headers` 字符串数组（名称、值交替排列）、`title`、`position` 毫秒数。
- `ACTION_SEND` + `text/plain`，接收视频直链及 `extra_headers` Bundle。
- 返回 `position` 毫秒数和 `end_by=user/playback_completion`。FongMi 的调用方式与 Horizon OS 对跨任务 Activity 结果的处理仍须真机验证，不能保证返回后自动续播/自动下一集。
- Media3 硬件解码优先；MP4、HLS、DASH，实际可播放的音视频编码取决于设备。固定 1920×1080 视频面板，原视频按比例显示。
- 失去 VR 焦点（系统菜单、切换应用）时暂停；恢复焦点仅恢复原本处于播放状态的视频。播放器随影院退出释放。

## 当前限制

此版本的选片、接口解析和内容配置由官方 FongMi 完成；影院只接收解析后的播放地址。它不继承 FongMi 定制的 FFmpeg/mpv、外挂字幕、弹幕、音轨选择或 DRM 授权。内嵌字幕由 Media3 支持范围决定。需要 FFmpeg 软解、特殊网络协议、临时本地代理、DRM 或无扩展名且无明确 MIME 的流，可能不能直接播放。

当前为暗色空间中的平面 2D 大屏。未实现座椅/房间模型、曲面、SBS/TB 立体电影、180°/360°、透视或手势追踪；没有宣称 4K/HDR/杜比视界播放与完整真机兼容性。

## 构建

本子项目独立于根目录 FongMi 构建，避免依赖未随源码发布的 `lib-*.aar`。它共享 `app/src/quest/` 的布局、屏幕参数与焦点策略。

环境：JDK 21、Gradle 9.7.1、Android SDK Platform 37.0，构建工具由 AGP 选择。将 `sdk.dir` 写入本目录的 `local.properties`（或设置 `ANDROID_HOME`），然后：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。测试包使用 Android debug 签名，适合侧载验证。

## Quest 3 验收

- 从应用列表启动、从 FongMi 外部播放器进入、从本地文件进入。
- MP4 与 m3u8/HLS 各一次，包含自定义 Referer/User-Agent 的合法测试源各一次。
- 进入后左右转头，确认大屏留在世界空间，手柄可点击全部按钮。
- 暂停、续播、快进退、不可 seek 的直播、尺寸与距离边界、退出后重新进入。
- 系统菜单开关、摘戴头显、切换应用、退出影院，检查暂停和恢复、无残留声音。
- 完成播放和中途返回各一次，检查 FongMi 的位置回传行为。

完整 FongMi 一体化变体见 [QUEST.md](../QUEST.md)。
