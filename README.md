# 呆呆面板移动端 APP

将呆呆面板（daidai-panel）打包为 Android 和 iOS 原生 APP，实现"安装即用"的体验。

## 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                        用户设备                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────────┐                    ┌───────────────┐    │
│  │   Android APP │                    │    iOS APP    │    │
│  │   (APK)       │                    │    (IPA)      │    │
│  └───────┬───────┘                    └───────┬───────┘    │
│          │                                    │            │
│  ┌───────▼───────┐                    ┌───────▼───────┐    │
│  │  Go Binary    │                    │ Go Framework  │    │
│  │  (arm64/armv7)│                    │ (.xcframework)│    │
│  └───────┬───────┘                    └───────┬───────┘    │
│          │                                    │            │
│  ┌───────▼───────┐                    ┌───────▼───────┐    │
│  │   WebView     │                    │   WKWebView   │    │
│  │  127.0.0.1:5701│                   │ 127.0.0.1:5701│    │
│  └───────────────┘                    └───────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## 功能特性

- **Android**: 支持 arm64-v8a 和 armeabi-v7a 架构，最低 Android 9 (API 28)
- **iOS**: 支持 arm64 架构，最低 iOS 16
- **后台保活**: Android ForegroundService + iOS Background Modes
- **开机自启**: Android 支持开机自动启动面板服务
- **数据同步**: 支持 JSON 格式导入导出，支持 WebDAV/iCloud 同步

## 项目结构

```
daidai-panel-app/
├── server/mobile/                    # Go 移动端 API
│   ├── mobile.go                     # 服务器管理
│   ├── binding.go                    # gomobile 绑定
│   └── sync.go                       # 数据同步
│
├── android/                          # Android 工程
│   ├── app/src/main/
│   │   ├── java/com/daidai/panel/
│   │   │   ├── MainActivity.java     # 主 Activity
│   │   │   ├── PanelService.java     # 前台服务
│   │   │   ├── PanelManager.java     # 面板管理器
│   │   │   └── BootReceiver.java     # 开机自启
│   │   ├── res/                      # 资源文件
│   │   └── AndroidManifest.xml       # 清单文件
│   └── build.gradle                  # 构建配置
│
├── ios/                              # iOS 工程
│   ├── DaidaiPanel/
│   │   ├── AppDelegate.swift         # 应用代理
│   │   ├── ViewController.swift      # 主视图控制器
│   │   ├── PanelManager.swift        # 面板管理器
│   │   ├── PanelWebView.swift        # 自定义 WebView
│   │   └── Info.plist                # 应用配置
│   └── Package.swift                 # Swift 包管理
│
├── scripts/                          # 构建脚本
│   ├── build-android.sh              # Android 编译
│   └── build-frontend.sh             # 前端构建
│
├── build-mobile.sh                   # 完整构建脚本
├── MOBILE_IMPLEMENTATION_PLAN.md     # 实施方案文档
└── MOBILE_PROJECT_SUMMARY.md         # 项目完成总结
```

## 快速开始

### 前置要求

**通用**
- Go 1.25+
- Node.js 20+
- npm 或 yarn

**Android**
- Android Studio
- Android SDK 34
- Android NDK（可选）

**iOS**
- Xcode 15+
- CocoaPods 或 Tuist（可选）

### 构建

```bash
# 完整构建
./build-mobile.sh all

# 单独构建
./build-mobile.sh android   # 仅 Android
./build-mobile.sh ios       # 仅 iOS
./build-mobile.sh frontend  # 仅前端
```

### 部署

**Android**
```bash
# 安装 APK
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

**iOS**
```bash
# 在 Xcode 中打开
open ios/DaidaiPanel.xcodeproj
```

## 数据同步

### 导出数据
```javascript
POST /api/sync/export
{
  "format": "json",
  "compress": true
}
```

### 导入数据
```javascript
POST /api/sync/import
{
  "data": "...",
  "format": "json"
}
```

## 文档

- [实施方案](MOBILE_IMPLEMENTATION_PLAN.md) - 详细的技术方案和架构设计
- [项目总结](MOBILE_PROJECT_SUMMARY.md) - 完整的功能说明和使用指南

## 依赖项目

- [呆呆面板](https://github.com/linzixuanzz/daidai-panel) - 原始面板项目

## 许可证

MIT License
