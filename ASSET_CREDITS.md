# 场景与素材来源

两套房间、座椅、窗框和屏幕边框由本项目的 `tools/scenes/build_scenes.py` 生成，为真实三维网格及 PBR 贴图；不属于实景扫描资产。单位为米，+Y 向上、+Z 为观影方向。生成后的 GLB 将贴图嵌入文件，安装后无需联网下载场景。

## 材质

以下 Poly Haven 材质采用 [CC0](https://polyhaven.com/license)，使用 1K 颜色、OpenGL 法线及 ARM（环境遮蔽、粗糙度、金属度）贴图，并对颜色作调整：

- [Velour Velvet](https://polyhaven.com/a/velour_velvet)：座椅与吸音墙面。
- [Dirty Carpet](https://polyhaven.com/a/dirty_carpet)：地毯。
- [Metal Plate 02](https://polyhaven.com/a/metal_plate_02)：金属甲板、框架与饰条。
- [Dark Wood](https://polyhaven.com/a/dark_wood)：木饰面。

## 地球影像

NASA Earth Observatory，Blue Marble。使用 [2048 像素全球影像](https://eoimages.gsfc.nasa.gov/images/imagerecords/57000/57730/land_ocean_ice_2048.png) 制作舷窗外的球体。[影像介绍及署名](https://science.nasa.gov/earth/earth-observatory/the-blue-marble-true-color-global-imagery-at-1km-resolution/)。NASA 影像使用遵循其[媒体使用指引](https://www.nasa.gov/nasa-brand-center/images-and-media/)，无 NASA 背书含义。

## 环境反射

`studio.env` 来自 [Meta Spatial SDK Samples](https://github.com/meta-quest/Meta-Spatial-SDK-Samples/tree/f233e2327b95f9871b75bdba867d6fdd726f07cc/StarterSample/app/src/main/assets)，MIT 许可。完整版权及许可文本随源码和 APK 放置于 `app/src/quest/assets/scenes/META_ENV_LICENSE.txt`。

下载地址及 SHA-256 记录于 [sources.json](tools/scenes/sources.json)。模型统计见 [manifest.json](app/src/quest/assets/scenes/manifest.json)。

## 重新生成

需要 Python 3、联网环境与 Blender 4.3+：

```sh
python tools/scenes/fetch_materials.py
blender -b --python tools/scenes/build_scenes.py -- --preview
```

模型格式校验（Node.js）：

```sh
npm install --prefix tools/scenes
node tools/scenes/validate.cjs
```

校验要求零错误、零警告，且地球必须保留颜色纹理、所有图片和缓冲区必须内嵌。验证器仍会报告未使用的导出属性和非二次幂图片等信息。

原始贴图下载到忽略的 `.cache/`；GLB、环境反射与统计输出至共享 assets；预览输出至忽略的 `tools/scenes/output/`。

`scene-previews/` 为 Blender 离线模型预览，使用占位屏幕和离线照明。不能视作 Quest 实机截图或帧率证明；实际 APK 使用 Spatial SDK 的动态聚光灯、阴影及屏幕取色照明。
