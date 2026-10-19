# 一体化 Quest 版本

## 范围与版本

`1.0.0-alpha.1` / versionCode `1000`，包名 `com.fongmi.quest.cinema`，ARM64，Android minSdk/targetSdk 34。完整选片与解析来自公开 FongMi 5.6.3 源码，不要求另一个应用。普通选片界面为 Quest 的 2D 窗口，影院为同应用内的沉浸式 VR Activity。

包名及 debug 签名沿用 0.3.0 影院原型，升级保留 `quest-cinema` 偏好。原官方 FongMi 安装的数据属于另一个包，不会自动迁移；用户须在新应用配置内容来源。

## 播放器依赖恢复

作者公开 Media3 分支 `release-1.11.0-fongmi`，固定提交：

`3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d`

`tools/bootstrap-media3.py` 使用 Git 稀疏检出下载所需源码、资源及已公开的原生库；省略上游大型测试媒体。`tools/media3/modules.json` 明确模块依赖，并将 RTMP 客户端统一为应用已有的公开 fork，避免两份相同类重复打包。`tools/media3/module.gradle` 使用应用相同的 AGP/JDK 编译源码与 AIDL，包含各模块资源、消费者混淆规则和原生库。

该公开分支停留在 2026-08-12，不包含 TV 在 2026-08-28 引入的 libass API。为完成真实构建，恢复对应的基础字幕播放接口，保留解析、剧集、历史、音轨、基础字幕及公开 mpv 引擎；弹幕使用该版本 PlayerView 已提供的控制接口。外部字体名称改为直接读取 OpenType/TTC `name` 表，不依赖缺失的 libass 字体工具。

不包含 libass；未构建 Exo 的 `ffmpegJNI` 软解桥接，相关扩展不可承诺。公开检出含 mpv、FFmpeg 动态库，实际解码与兼容性需设备验证。这里没有把反编译结果当作原始 AAR。此版本不能标成官方 5.7.7 的完全同步版本。

## 同应用换集

- `CinemaActivity` 绑定同进程 `PlaybackService`，直接显示原来的播放器，不复制视频地址启动外部应用。
- 上一集/下一集经 `dispatchPrev` / `dispatchNext` 调用仍在后台存活的 `VideoActivity` 的 `VodPlaybackController`，继续走原有剧集选择、片源解析、请求头与历史更新。
- 解析期间禁用重复换集；准备完成、错误或剧集边界会更新影院状态。没有剧集的直链也返回边界，避免按钮一直等待。
- 自然播完由原播放状态回调推进下一集；影院的自动连播偏好控制是否推进，默认开启。
- 选片界面进入 VR 后继续处理解析与进度计时，避免普通 `onStop` 的退出标记；隐藏的 2D PlayerView 不再重新绑定并抢走 VR 输出。
- 新剧集、引擎重建及焦点变化时重新绑定影院播放器，并保持焦点暂停策略。房间和屏幕实体不因普通换集销毁。
- 首页提供无需播放会话的影院预览，预览中禁用视频与换集操作。

## 构建

```sh
python tools/bootstrap-media3.py
./gradlew -PbuildPython=/path/to/python3.10 :app:assembleQuestDebug :app:testQuestDebugUnitTest :app:lintQuestDebug
```

根目录 `local.properties` 配置 Android SDK 路径，JDK 21 不限定供应商。构建独立原型仍进入 `quest-player/`，不与根工程混用输出。

## 真机验收清单

当前没有连接 Quest 3，以下为待执行清单，不是通过记录：

1. 直接安装单个 APK，启动首页，添加自己的合法配置；确认不要求官方 FongMi 安装。
2. 选择有至少三集的内容，进入影院；测试下一集、上一集、连续快速点击，以及带请求头/需解析的地址。
3. 在第一集和最后一集测试边界；单个直链没有剧集时应提示无可播放的上下集。
4. 自动连播开启自然播完，确认不退出房间；关闭后自然播完，确认留在本集；退出后重进确认偏好保存。
5. 换集解析期间打开系统菜单或摘戴头显，确认恢复后没有后台抢播、双重声音或失去视频画面。
6. 连续换集后返回选片，确认进度、标题与选中剧集；再进入影院检查播放显示。
7. 影院预览、私人影院、观景舱、星空顶、亮度、阴影和画面光；检查实际帧率与画面。
8. 覆盖安装 0.3.0 测试版，确认同一个应用图标和原影院设置；首次配置浏览内容正常。

## 后续更新

Quest 包使用自己的版本号与签名；官方 FongMi 自动更新通道保持关闭，避免安装回非 VR 官方包。后续通过本仓库发布新 APK 覆盖升级。同步上游时需要一起核对 TV 源码与公开 Media3 分支 API，不能只换官方 APK 或修改版本号。

安装包体积优化：DEX 使用 Android 支持的压缩存储；Quest 构建仅打包 ARM64 Python 运行时及依赖；场景 JPEG 保留原始 1K/2K 像素尺寸，改为质量 90、4:4:4 编码。模型几何、UV、PBR 参数与星光数量保持一致。影院控制面板下移并缩小，减少默认屏幕下方的遮挡。

Quest 清单移除车载应用元数据，对共用清单触发的车载语音 intent 检查作定点排除；其他 Lint 错误继续阻止构建。补齐简繁中文影院文案，并将退到系统桌面的兜底 Intent 改为明确解析的系统组件。

## 已执行验证（2026-10-19）

- `:app:assembleQuestDebug`、`:app:testQuestDebugUnitTest`、`:app:lintQuestDebug` 完整通过。
- 一体化项目 5 项字体兼容单测通过；历史独立原型的 14 项请求/几何/焦点/场景设置单测通过。没有把这些单测当成实际换集、解码或头显渲染的验证。
- 应用 Lint 零错误，仍有上游及依赖警告；车载检查的定点例外见上文。
- APK v2 签名、16 KB ZIP 对齐通过；证书与 0.3.0 测试版一致，minSdk/targetSdk 34，原生 ABI 仅 ARM64。
- 逐文件核对 APK 内所有 Quest 场景与许可文件，确认与源码一致；无 ARM32 Python 内容，DEX 使用压缩存储。
- 四个 GLB 经 Khronos 校验零错误、零警告；bootstrap 脚本成功校验并检出固定 Media3 提交。
- 原生库依赖检查未发现缺少的非系统动态库；这不等于在 Quest 上成功加载或解码。
- ADB 没有连接设备。未验证实际安装、覆盖升级、播放、换集、焦点切换、手柄交互或帧率。

机器可读记录：[verification/quest-integrated-1.0.0-alpha.1.json](verification/quest-integrated-1.0.0-alpha.1.json)。
