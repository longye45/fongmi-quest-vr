# FongMi Quest VR 一体化测试包

版本：v1.0.0-alpha.1，versionCode 1000。下载 `fongmi-quest-integrated-debug.apk`。

这是单 APK 一体化版本：同一应用内配置片源、选片、解析视频、进入 VR 影院，支持影院内上一集、下一集和自动连播，无需另装官方 FongMi 作为入口。保留私人影院、太空观景舱、真实贴图、可调灯光、阴影、画面光与星空顶。

与 0.3.0 影院原型同包名、同 debug 签名，可覆盖升级；影院偏好保留，片源需要在新应用中配置。安装：`adb install -r fongmi-quest-integrated-debug.apk`。

基于公开 FongMi 5.6.3 源码及固定提交的公开播放器分支，不是官方 5.7.7 的完全同步版本。已完成构建、单元测试、应用 Lint、签名与 ZIP 对齐检查，未连接 Quest 3 进行安装、播放、换集或渲染验收。

源码、限制、验证报告与验收清单见 main 分支。文件校验值见 SHA256SUMS.txt。旧版独立影院 APK 仍可从旧 Release 固定提交下载。
