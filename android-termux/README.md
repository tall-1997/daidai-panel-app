# 呆呆面板 Android 启动入口

## 架构
- Android App 仅作为启动入口
- 内置 Termux 组件
- 使用 proot-distro 安装 Ubuntu/Debian
- 在 Linux 环境中直接运行面板

## 工作流程
1. App 启动时检查 Termux 环境
2. 如果没有 Termux，下载并安装
3. 在 Termux 中安装 proot-distro
4. 使用 proot-distro 安装 Ubuntu
5. 在 Ubuntu 中安装 Python/Node.js
6. 下载并运行面板二进制

## 优势
- 无需 root 权限
- 完整的 Linux 环境
- 支持所有面板功能
- 用户体验接近原生 App
