# 呆呆面板移动端 v0.0.1 更新日志

发布日期: 2026-06-02

## 版本信息

| 项目 | 版本 |
|------|------|
| 应用版本 | v0.0.1 |
| 面板版本 | 2.2.14-mobile |
| Go 版本 | 1.25 |
| 前端框架 | Vue 3 + Element Plus |
| Android minSdk | 28 (Android 9) |
| Android targetSdk | 34 |

---

## 更新内容

### Bug 修复

#### Android
- **修复启动面板失败问题**
  - 原问题: `PanelManager` 使用 JNI 方式加载 Go 库，但实际构建的是独立可执行文件
  - 解决方案: 重写 `PanelManager` 为子进程方式启动 Go 二进制
  - 使用单例模式确保 `MainActivity` 和 `PanelService` 共享同一实例
  - 添加 HTTP 端口检测来确认服务是否真正启动

- **修复 PowerManager 导入缺失**
  - 添加 `android.os.PowerManager` 导入
  - 修复电池优化检查功能

### 新增功能

#### Android
- **移动端专用 Go 入口点** (`server/cmd/mobile/main.go`)
  - 支持 `-data-dir` 参数指定数据目录
  - 支持 `-web-dir` 参数指定前端资源目录
  - 支持 `-port` 参数指定监听端口
  - 自动生成 `config.yaml` 配置文件

- **应用图标**
  - 添加所有密度的启动图标 (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi)
  - 支持圆形图标

- **Gradle 构建支持**
  - 添加 Gradle Wrapper
  - 配置 Android SDK 34

#### iOS
- **iOS 构建脚本** (`scripts/build-ios.sh`)
  - 使用 xcodegen 生成 Xcode 项目
  - 支持构建未签名 IPA
  - 需要 macOS 环境

- **Xcode 项目配置** (`ios/project.yml`)
  - 配置 Bundle ID: com.daidai.panel
  - 配置最低版本: iOS 16
  - 禁用代码签名（用于手动签名）

#### 构建系统
- **一键发布脚本** (`build-release.sh`)
  - 自动构建 Android APK
  - 自动构建 iOS IPA（macOS）
  - 生成发布包和版本说明

### 改进

- **版本号统一更新为 v0.0.1**
  - Android `build.gradle`
  - iOS `Info.plist`
  - `PanelManager.java`
  - `PanelManager.swift`

---

## 文件变更

### 新增文件
```
android/app/src/main/res/mipmap-hdpi/ic_launcher.png
android/app/src/main/res/mipmap-hdpi/ic_launcher_round.png
android/app/src/main/res/mipmap-mdpi/ic_launcher.png
android/app/src/main/res/mipmap-mdpi/ic_launcher_round.png
android/app/src/main/res/mipmap-xhdpi/ic_launcher.png
android/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png
android/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png
android/gradle/wrapper/gradle-wrapper.jar
android/gradle/wrapper/gradle-wrapper.properties
android/gradlew
build-release.sh
ios/project.yml
scripts/build-ios.sh
server/cmd/mobile/main.go
```

### 修改文件
```
android/app/build.gradle
android/app/src/main/java/com/daidai/panel/MainActivity.java
android/app/src/main/java/com/daidai/panel/PanelManager.java
android/app/src/main/java/com/daidai/panel/PanelService.java
android/gradle.properties
build-mobile.sh
ios/DaidaiPanel/Info.plist
ios/DaidaiPanel/PanelManager.swift
```

---

## 技术细节

### Android 架构变更

**旧架构 (JNI 方式)**
```java
// 尝试加载 JNI 库
System.loadLibrary("daidai");

// 声明 native 方法
private native void nativeStartServer(String dataDir, String webDir, int port);
```

**新架构 (子进程方式)**
```java
// 从 assets 复制二进制文件
String binaryPath = copyBinaryToExecutableLocation();

// 使用 ProcessBuilder 启动子进程
ProcessBuilder pb = new ProcessBuilder(
    binaryPath,
    "-data-dir", dataDir,
    "-web-dir", webDir,
    "-port", String.valueOf(port)
);
serverProcess = pb.start();

// 通过 HTTP 检测服务状态
HttpURLConnection conn = (HttpURLConnection) url.openConnection();
```

### Go 入口点

```go
// server/cmd/mobile/main.go
func main() {
    dataDir := flag.String("data-dir", "", "数据目录路径")
    webDir := flag.String("web-dir", "", "前端资源目录路径")
    port := flag.Int("port", 5701, "监听端口")
    flag.Parse()
    
    // 生成配置文件
    generateConfig(configPath, *dataDir, *webDir, *port)
    
    // 加载配置并启动服务
    cfg, err := config.Load(configPath)
    // ...
}
```

---

## 已知问题

1. **iOS 版本需要 macOS 环境构建**
   - 当前无法在 Linux 环境构建 iOS IPA
   - 需要在 macOS 上使用 Xcode 构建

2. **仅支持 arm64 架构**
   - 暂不支持 armeabi-v7a (32 位 ARM)
   - 大多数现代 Android 设备都支持 arm64

3. **调试版本**
   - 当前发布的是 debug 版本
   - 未启用代码混淆和资源压缩

---

## 下一步计划

1. **支持 armeabi-v7a 架构**
   - 构建 32 位 ARM 版本
   - 支持更多旧设备

2. **Release 版本构建**
   - 启用 ProGuard 代码混淆
   - 启用资源压缩
   - 配置签名密钥

3. **iOS 版本完善**
   - 集成 gomobile binding
   - 完善后台保活机制
   - TestFlight 分发

4. **功能增强**
   - 推送通知支持
   - 小组件支持
   - 深色模式适配

---

## 下载

| 平台 | 文件 | 大小 |
|------|------|------|
| Android | daidai-panel-v0.0.1-debug.apk | ~25 MB |

---

## 反馈

如有问题或建议，请提交 Issue: https://github.com/tall-1997/daidai-panel-app/issues
