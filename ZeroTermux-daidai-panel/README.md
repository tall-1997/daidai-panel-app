# ZeroTermux 呆呆面板版

**安装即用，无需任何操作！**

基于 ZeroTermux 修改，内置完整的呆呆面板环境。安装 APK 后，打开应用即可自动初始化并启动面板。

## 功能特点

- ✅ **安装即用** - 无需手动安装或配置
- ✅ **自动初始化** - 首次启动自动下载依赖和面板
- ✅ **自动启动** - 打开应用后自动启动面板服务
- ✅ **完整环境** - 内置 Python、Node.js 运行环境
- ✅ **快捷命令** - 支持 `daidai-start`、`daidai-stop` 等命令

## 下载安装

从 [GitHub Release](https://github.com/tall-1997/ZeroTermux-daidai-panel/releases) 下载最新版本的 APK 安装。

## 使用方法

1. **安装 APK**
2. **打开应用** - 等待初始化完成（首次约 5 分钟）
3. **浏览器访问** - `http://127.0.0.1:5700`
4. **创建账号** - 首次访问需要创建管理员账号

就这么简单！无需任何命令行操作。

## 访问地址

- 本地访问：http://127.0.0.1:5700
- 局域网访问：http://你的手机IP:5700

## 快捷命令

| 命令 | 说明 |
|------|------|
| `daidai-start` | 启动面板 |
| `daidai-stop` | 停止面板 |
| `daidai-status` | 查看状态 |

## 技术架构

```
ZeroTermux APK
├── Alpine Linux 环境
├── Python 运行时
├── Node.js 运行时
└── 呆呆面板
    ├── daidai-server (arm64)
    ├── web 前端资源
    └── 配置文件
```

## 构建说明

### 方式一：使用 GitHub Actions

1. Fork 本仓库
2. 创建新的 tag（如 v0.0.1）
3. GitHub Actions 会自动构建并发布 APK

### 方式二：本地构建

```bash
# 克隆仓库
git clone https://github.com/tall-1997/ZeroTermux-daidai-panel.git
cd ZeroTermux-daidai-panel

# 下载资源
bash download-assets.sh

# 构建 APK（需要 Android SDK）
bash build-full.sh
```

## 项目地址

- GitHub: https://github.com/tall-1997/ZeroTermux-daidai-panel
- 呆呆面板: https://github.com/tall-1997/daidai-panel-app
- ZeroTermux: https://github.com/hanxinhao000/ZeroTermux

## 许可证

基于 ZeroTermux 修改，遵循原项目许可证。

## 致谢

- [ZeroTermux](https://github.com/hanxinhao000/ZeroTermux) - 基础终端模拟器
- [呆呆面板](https://github.com/linzixuanzz/daidai-panel) - 面板程序
