# 呆呆面板 Android 启动器

这是一个简单的 Android 启动器应用，用于在 Android 设备上启动呆呆面板。

## 架构

```
Android App（启动入口）
    ↓
Termux（终端模拟器）
    ↓
proot-distro（Linux 环境）
    ↓
呆呆面板
```

## 工作流程

1. App 检查 Termux 是否已安装
2. 如果没有，提示用户安装
3. 复制初始化脚本到 Termux
4. 启动 Termux 并执行初始化脚本
5. 初始化脚本会：
   - 安装 Python、Node.js
   - 下载面板二进制
   - 启动面板服务
6. 打开浏览器访问面板

## 构建

1. 使用 Android Studio 打开项目
2. 同步 Gradle
3. 构建 APK

## 使用

1. 安装 APK
2. 打开应用
3. 如果提示安装 Termux，点击安装
4. 点击"启动面板"
5. 等待初始化完成
6. 浏览器会自动打开面板

## 注意事项

- 首次启动需要下载 Termux 和面板，需要网络
- 面板运行在 Termux 中，关闭 Termux 会停止面板
- 面板数据保存在 Termux 的 home 目录中

## 相关链接

- [呆呆面板](https://github.com/tall-1997/daidai-panel-app)
- [Termux](https://termux.dev)
