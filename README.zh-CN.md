<p align="center">
  <img src=".github/icon.png" width="128" height="128" alt="PWN.Health 图标">
</p>

<h1 align="center">PWN.Health</h1>

<p align="center">
  一款完全在手机上运行的 Android 私密健康与健身记录应用：<br>
  不需要 Google Play 服务，不需要账号，也没有网络权限。
</p>

<p align="center"><a href="README.md">English</a> · <a href="README.ru.md">Русский</a> · <b>简体中文</b></p>

<p align="center">
  <a href="https://github.com/pwn-all/PWN.Health/actions/workflows/build.yml"><img src="https://github.com/pwn-all/PWN.Health/actions/workflows/build.yml/badge.svg" alt="构建"></a>
  <a href="https://github.com/pwn-all/PWN.Health/releases/latest"><img src="https://img.shields.io/github/v/release/pwn-all/PWN.Health" alt="最新版本"></a>
  <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 13+">
  <a href="#许可证"><img src="https://img.shields.io/badge/licence-GPL--3.0--or--later-blue" alt="许可证：GPL-3.0-or-later"></a>
</p>

<p align="center">
  <a href="#功能">功能</a> ·
  <a href="#隐私">隐私</a> ·
  <a href="#下载">下载</a> ·
  <a href="#从源代码构建">构建</a> ·
  <a href="#文档">文档</a> ·
  <a href="#许可证">许可证</a>
</p>

## 关于

PWN.Health 可以计步、记录步行路线，记下饮食、身体、睡眠、心情和用药的日志，带你完成锻炼，还能读取化验结果——这一切都在手机上完成。它基于 Android 平台自身的 API 构建，所以在没有 Google Play 服务的手机上也能运行；它也没有声明 `INTERNET` 权限：除非你自己导出、备份或同步到 Health Connect，你记录的任何内容都不会离开这台设备。

它是采用 GPL 许可证的自由软件，免费，没有广告，也没有数据分析。

## 功能

- **步数与活动**——用硬件计步器计步，手机重启也不会算错；运动闸门会拒绝在汽车或公交车上“感觉到”的“步数”；还能借助气压计记录爬了多少层楼。
- **步行与路线**——不依赖 Google 定位服务的 GPS 轨迹，绘制在带有地形、河流、森林和城镇的离线地图上；可导出为 GPX、GeoJSON 或 CSV。
- **饮食**——餐食日志、离线识别照片中的菜品和食物、读取包装上的营养成分表，并对每天吃了什么给出反馈。
- **身体与健康日志**——体重、心率、血压、睡眠、饮水、心情、月经周期、在身体图上标出的疼痛、病史，以及循证的建议。
- **药物**——带服药提醒的用药疗程、服药依从性图表、就诊预约和常规体检。
- **锻炼**——模板、标明所练肌肉的动作目录、逐组进行的实时训练，带组间休息计时、超级组和 RIR / RPE。
- **化验结果**——手动输入，或从照片和 PDF 中读取，附带参考区间、图表，以及给医生的 PDF 报告。
- **症状聊天**——回答关于症状的问题，答案来自规则和一个在设备上运行的 ONNX 模型。
- **导入**——从 Hevy、Strong、FitNotes 和 Lyfta 导入训练历史，从 Apple Health、Fitbit / Google Takeout、Samsung Health、Garmin、Withings、Oura、Strava、Nike Run Club、Flo、MyFitnessPal、Cronometer 和 Google Fit 导入健康数据导出文件。
- **Health Connect**——可选的双向同步，设计上保证不会重复计算。
- **蓝牙**——标准的蓝牙 LE 健康设备、小米和 COLMI 设备、Concept2 PM5，以及运行 PWN.Health 手表应用的 Bangle.js 2 手表。
- **其他**——桌面小组件、提醒、等级与奖章，以及 16 种语言，每一种都完整翻译。

完整的功能列表，包括每个部分的状态和所在的文件，见 [docs/DESIGN.md](docs/DESIGN.md#implementation-status) 中的状态表。

## 隐私

- **没有网络**。清单文件没有声明 `INTERNET` 权限，一旦出现这项权限，`NoNetworkPermissionTest` 就会让构建失败。应用自带的 ONNX Runtime 在构建时去掉了 Microsoft 的遥测（[tools/onnxruntime-android](tools/onnxruntime-android/README.md)）。
- **不需要 Google Play 服务**，不需要账号，没有广告，没有数据分析。
- **你的数据只属于你**。可选的数据库加密（SQLCipher）和应用锁；备份可以是一份用密码保护的文件，也可以是每天自动复制到你选定的文件夹。每种保护能防住什么、防不住什么，见 [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md)；应用的隐私政策见 [docs/privacy-policy.html](docs/privacy-policy.html)。

## 下载

已签名的 APK 发布在 [Releases](https://github.com/pwn-all/PWN.Health/releases) 页面：

| 文件 | 适用于 |
|---|---|
| `app-sideload-arm64-v8a-release.apk` | 64 位 ARM 手机——几乎所有运行 Android 13 的手机 |
| `app-sideload-armeabi-v7a-release.apk` | 32 位 ARM 手机 |

需要 Android 13 或更高版本；没有 x86 版本。每个版本都用同一把密钥签名，其证书的 SHA-256 指纹为

```
96:95:4A:82:2B:8A:96:C3:E2:4B:2D:81:17:90:E5:E9:87:6D:C5:C8:F0:8F:BD:87:8A:31:C7:3A:C8:BB:7D:63
```

（可用 `apksigner verify --print-certs <apk>` 查看）。用其他任何密钥签名的 APK 都不是我们发布的，也无法更新已经安装的应用。每个版本的变化见 [CHANGELOG.md](CHANGELOG.md)。

## 从源代码构建

你需要 JDK 21，以及装有 `platforms;android-37.1` 和 `build-tools;37.0.0` 的 Android SDK，并通过 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir` 指明其位置。其余的一切——Gradle、各个库、ONNX Runtime、模型和地图——要么已经包含在仓库里，要么通过 Gradle Wrapper 获取。

```sh
git clone https://github.com/pwn-all/PWN.Health.git
cd PWN.Health
./gradlew assembleSideloadDebug   # installable debug APKs, one per ABI
./gradlew check                   # Android Lint, detekt, ktlint and the unit tests
```

第一条命令构建可直接安装的调试版 APK，每个 ABI 一个；第二条运行 Android Lint、detekt、ktlint 和单元测试。发布版构建、依赖校验、签名以及版本的发布方式，见 [docs/BUILDING.md](docs/BUILDING.md)。

## 文档

| 文档（英文） | 内容 |
|---|---|
| [docs/DESIGN.md](docs/DESIGN.md) | 应用如何工作：状态表、计步、Health Connect、定位、存储、运动闸门、地图、食物识别 |
| [docs/BUILDING.md](docs/BUILDING.md) | 工具链、依赖校验、发布版构建、签名和版本发布 |
| [docs/MODELS.md](docs/MODELS.md) | 应用内置的神经网络，按字节数和 SHA-256 列出 |
| [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) | 应用锁、数据库加密和密码保护的备份各自能防范什么 |
| [CHANGELOG.md](CHANGELOG.md) | 每个版本改了什么，以及为什么改 |
| [docs/README.md](docs/README.md) | 仓库中的每一份文档，以及各自可信到什么程度 |

## 许可证

PWN.Health 是自由软件：本仓库中的代码依据 GNU 通用公共许可证（GNU General Public License）第 3 版或（由你选择）任何更新的版本授权——`GPL-3.0-or-later`。完整文本见 [LICENSE](LICENSE)。本应用免费提供，不附带任何担保。在应用中，“关于应用”页面写明了许可证并附有本仓库的链接，“许可证全文”则完整显示每一份许可证。

应用中除自身代码之外的内容，各自保留其原有的许可证：

| 组件 | 许可证 |
|---|---|
| ONNX Runtime（由 `tools/onnxruntime-android/build.sh` 从源代码构建） | MIT |
| SQLCipher | BSD-3-Clause |
| AndroidX、Kotlin、Jetpack Compose、Guava | Apache-2.0 |
| PaddleOCR PP-OCR 模型 | Apache-2.0 |
| 聊天阅读模型：Microsoft Multilingual-MiniLM、XLM-R 分词器 | MIT |
| 地图：Natural Earth、ETOPO 2022 | Public domain（公有领域） |
| 地图：GeoNames、ESA WorldCover | CC BY 4.0 |
| 食物成分表：USDA FoodData Central | CC0 |
| 化验单位：UCUM | UCUM licence |
| 诊所和医院：官方登记名录和开放数据 | 以 `Facilities.kt` 中所列为准 |
| 食物照片模型：PE-Core-S16 | Apache-2.0；训练所用的公开食物照片数据集各自适用其自身条款 |

本节为译文，一切以英文 README 的 [Licence](README.md#licence) 一节为准。
