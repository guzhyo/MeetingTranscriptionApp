# 会议录音实时转录应用

一款安卓端离线会议录音转录软件，支持本地语音识别，无需联网。

## 功能特性

- **离线识别**: 使用 Paraformer-small 模型，完全本地运行
- **后台录音**: 支持前台服务，锁屏后持续录音转录
- **实时转录**: 录音同时实时转换为文字
- **自动保存**: 转录内容自动保存到本地文件
- **轻量设计**: 安装包体积小，代码简洁

## 项目结构

```
MeetingTranscriptionApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/meetingtranscription/
│   │   │   ├── MainActivity.kt          # 主界面
│   │   │   ├── TranscriptionService.kt  # 录音转录服务
│   │   │   └── ParaformerRecognizer.kt  # 语音识别引擎
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml # 主界面布局
│   │   │   └── values/                  # 字符串、颜色、主题
│   │   └── AndroidManifest.xml          # 应用配置
│   └── build.gradle                     # 模块构建配置
├── build.gradle                         # 项目构建配置
└── settings.gradle                      # 项目设置
```

## 技术栈

- **语言**: Kotlin
- **架构**: 原生 Android
- **语音识别**: Paraformer-small (ONNX Runtime)
- **录音**: AudioRecord API
- **后台服务**: ForegroundService

## 依赖库

```gradle
// ONNX Runtime 移动端推理
implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.16.3'

// AndroidX
implementation 'androidx.core:core-ktx:1.12.0'
implementation 'com.google.android.material:material:1.11.0'
```

## 权限说明

应用需要以下权限：

- `RECORD_AUDIO` - 录音权限
- `FOREGROUND_SERVICE` - 前台服务权限
- `FOREGROUND_SERVICE_MICROPHONE` - 前台录音权限 (Android 14+)
- `POST_NOTIFICATIONS` - 通知权限 (Android 13+)

## 模型准备

1. 下载 Paraformer-small ONNX 模型
2. 将模型文件 `paraformer-small.onnx` 放入 `app/src/main/assets/` 目录

模型下载地址：[FunASR ModelScope](https://www.modelscope.cn/models/damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-pytorch/summary)

## 构建步骤

1. 使用 Android Studio 打开项目
2. 同步 Gradle 依赖
3. 连接设备或启动模拟器
4. 点击 Run 按钮构建安装

## 使用说明

1. 首次启动授予录音权限
2. 点击绿色圆形按钮开始录音转录
3. 应用进入后台后仍会继续录音
4. 点击红色按钮停止录音
5. 转录文件保存在应用私有目录

## 待完善功能

- [ ] 集成完整的 Paraformer 模型推理
- [ ] 说话人分离 (Diarization)
- [ ] 多语言支持
- [ ] 转录历史查看
- [ ] 导出功能 (TXT/PDF)

## 注意事项

1. 模型文件较大 (~100MB)，首次启动需要加载时间
2. 长时间录音会消耗较多电量
3. 建议在充电状态下使用后台录音功能

## 许可证

MIT License
