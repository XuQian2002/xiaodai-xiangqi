# 小呆象棋 3.0（纯净源码版）

中国象棋 Android 应用源码：单机人机 / 双人对战，本地 AI 计算，中文记谱、悔棋、提示、翻转、棋钟等。

## 这份源码包的特点
- **纯文本、零二进制**：只有 Kotlin / Java / XML / gradle 脚本 / LICENSE / 本说明，包里没有任何 .so、.onnx、.ttf、.m4a、.wav、.png、.keystore、apk 等二进制文件。
- **已去掉全部背景音乐**：七首内置曲目、音乐开关 / 曲目选择 / 音量调节菜单（「更多」按钮）及相关文案、代码均已移除。
- 界面字体改用系统 `serif`，图标改为纯 vector 绘制（`res/drawable/ic_launcher.xml`），不再依赖 png/ttf。
- 布局已是「标题居中 + 翻转按钮左上」的版本。

## 目录
```
app/src/main/java/daidaixiangqi/app/     业务代码（MainActivity、GameController、BoardView、引擎、语音等）
app/src/main/java/com/k2fsa/sherpa/onnx/ sherpa-onnx 的 Java 接口封装（纯 Java 源码）
app/src/main/res/                        布局与纯 XML 资源（颜色、样式、vector 图标）
app/src/main/AndroidManifest.xml
app/build.gradle.kts                     模块构建脚本（未配置签名）
```

## 构建环境
- JDK 17
- Android SDK Platform 34、Build-Tools 34.0.0
- Gradle 8.9（仓库未带 gradle-wrapper.jar，用本机 Gradle 8.9 执行 `gradle assembleDebug`，或用 Android Studio 直接打开工程）
- minSdk 24 / targetSdk 34

## 可选：想让功能完整，需要自行补回的二进制文件
不补也能编译、能开局、能下棋；只是下面这些「增强功能」会静默失效。

| 想恢复的功能 | 需要补充的文件 | 放置位置 |
|---|---|---|
| AI 引擎（人机对战用 Pikafish） | `libpikafish.so`、`pikafish.nnue` | `app/src/main/jniLibs/arm64-v8a/`、`app/src/main/assets/` |
| 离线语音播报 | `zh_CN-chaowen-medium.onnx`、`phone.fst`、`tokens.txt`、`lexicon.txt`、`*.onnx.json`，以及 `libsherpa-onnx-jni.so` 等原生库 | `app/src/main/assets/tts/`、`app/src/main/jniLibs/arm64-v8a/` |
| 落子 / 吃子音效 | `move_piece.wav`、`capture_piece.wav` | `app/src/main/res/raw/`（并在 `GameAudioManager.kt` 的 TODO 处接回 SoundPool） |
| 楷体字体 | `app_kai.ttf` | `app/src/main/res/font/`（再把 `serif` 换回 `@font/app_kai`） |

## 签名
本包不含 keystore。请自行创建签名文件，并在 `app/build.gradle.kts` 的 `signingConfigs` 与 `buildTypes.release` 中按注释位置补上。

## 许可
MIT License，详见 LICENSE。
