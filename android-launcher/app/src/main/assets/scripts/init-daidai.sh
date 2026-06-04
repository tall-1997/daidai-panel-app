#!/data/data/com.termux/files/usr/bin/bash
# 呆呆面板初始化脚本
# 此脚本在 Termux 中执行，安装并启动面板

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 配置
DAIDAI_DIR="$HOME/daidai-panel"
DAIDAI_DATA="$DAIDAI_DIR/Dumb-Panel"
DAIDAI_BIN="$DAIDAI_DIR/daidai-server"
DAIDAI_WEB="$DAIDAI_DIR/web"
PANEL_PORT=5700
GITHUB_REPO="tall-1997/daidai-panel-app"

# 检查是否已安装
check_installed() {
    if [ -f "$DAIDAI_BIN" ]; then
        return 0
    fi
    return 1
}

# 安装依赖
install_dependencies() {
    log_info "安装依赖..."
    
    # 更新包管理器
    pkg update -y
    
    # 安装必要的包
    pkg install -y python nodejs git curl wget
    
    log_info "依赖安装完成"
}

# 下载面板
download_panel() {
    log_info "下载面板..."
    
    # 创建目录
    mkdir -p "$DAIDAI_DIR"
    mkdir -p "$DAIDAI_DATA"
    
    # 获取最新版本
    LATEST_VERSION=$(curl -s "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
    
    if [ -z "$LATEST_VERSION" ]; then
        log_error "获取版本失败"
        exit 1
    fi
    
    log_info "最新版本: $LATEST_VERSION"
    
    # 下载面板二进制（Android arm64）
    DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/daidai-server-linux-arm64"
    
    log_info "下载面板二进制..."
    curl -L -o "$DAIDAI_BIN" "$DOWNLOAD_URL"
    chmod +x "$DAIDAI_BIN"
    
    # 下载前端资源
    log_info "下载前端资源..."
    WEB_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/web.tar.gz"
    curl -L -o "/tmp/web.tar.gz" "$WEB_URL"
    tar -xzf "/tmp/web.tar.gz" -C "$DAIDAI_DIR"
    rm -f "/tmp/web.tar.gz"
    
    log_info "面板下载完成"
}

# 生成配置文件
generate_config() {
    log_info "生成配置文件..."
    
    cat > "$DAIDAI_DIR/config.yaml" << EOF
server:
  port: $PANEL_PORT
  mode: release

data:
  dir: $DAIDAI_DATA
  db: $DAIDAI_DATA/daidai.db

web:
  dir: $DAIDAI_WEB
EOF
    
    log_info "配置文件生成完成"
}

# 启动面板
start_panel() {
    log_info "启动面板..."
    
    # 检查是否已运行
    if pgrep -f "daidai-server" > /dev/null; then
        log_warn "面板已在运行"
        return 0
    fi
    
    # 启动面板
    cd "$DAIDAI_DIR"
    nohup "$DAIDAI_BIN" > "$DAIDAI_DIR/daidai.log" 2>&1 &
    
    # 等待启动
    sleep 2
    
    # 检查是否启动成功
    if pgrep -f "daidai-server" > /dev/null; then
        log_info "面板启动成功"
        log_info "访问地址: http://127.0.0.1:$PANEL_PORT"
    else
        log_error "面板启动失败"
        cat "$DAIDAI_DIR/daidai.log"
        exit 1
    fi
}

# 主流程
main() {
    log_info "=========================================="
    log_info "呆呆面板初始化脚本"
    log_info "=========================================="
    
    # 检查是否已安装
    if check_installed; then
        log_info "面板已安装，直接启动"
        start_panel
        exit 0
    fi
    
    # 安装依赖
    install_dependencies
    
    # 下载面板
    download_panel
    
    # 生成配置
    generate_config
    
    # 启动面板
    start_panel
    
    log_info "=========================================="
    log_info "初始化完成！"
    log_info "访问地址: http://127.0.0.1:$PANEL_PORT"
    log_info "=========================================="
}

# 执行主流程
main
