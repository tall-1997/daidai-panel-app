#!/data/data/com.termux/files/usr/bin/bash
# 呆呆面板初始化脚本
# 此脚本在 Termux 中执行，自动安装并启动面板

# 禁用交互式提示
export DEBIAN_FRONTEND=noninteractive
export TERM=xterm-256color

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 日志函数
log_info() {
    echo -e "${GREEN}[✓]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[!]${NC} $1"
}

log_error() {
    echo -e "${RED}[✗]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[→]${NC} $1"
}

# 配置
DAIDAI_DIR="$HOME/daidai-panel"
DAIDAI_DATA="$DAIDAI_DIR/Dumb-Panel"
DAIDAI_BIN="$DAIDAI_DIR/daidai-server"
DAIDAI_WEB="$DAIDAI_DIR/web"
DAIDAI_LOG="$DAIDAI_DIR/daidai.log"
PANEL_PORT=5700

# GitHub 仓库
GITHUB_REPO="tall-1997/daidai-panel-app"

# 检查是否已安装
check_installed() {
    if [ -f "$DAIDAI_BIN" ] && [ -d "$DAIDAI_WEB" ]; then
        return 0
    fi
    return 1
}

# 检查面板是否在运行
check_running() {
    if pgrep -f "daidai-server" > /dev/null 2>&1; then
        return 0
    fi
    return 1
}

# 显示欢迎信息
show_welcome() {
    clear
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}                                                          ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}           ${GREEN}呆呆面板 - 自动安装脚本${NC}                       ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                          ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}  ${YELLOW}轻量级定时任务管理面板${NC}                                  ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                          ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# 安装依赖
install_dependencies() {
    log_step "安装系统依赖..."
    
    # 更新包管理器（静默模式）
    log_info "更新包列表..."
    pkg update -y > /dev/null 2>&1 || {
        log_warn "更新失败，尝试继续..."
    }
    
    # 安装必要的包
    log_info "安装 Python、Node.js、Git..."
    pkg install -y python nodejs git curl wget jq > /dev/null 2>&1 || {
        log_error "依赖安装失败"
        log_warn "请检查网络连接后重试"
        exit 1
    }
    
    log_info "依赖安装完成"
}

# 下载面板
download_panel() {
    log_step "下载呆呆面板..."
    
    # 创建目录
    mkdir -p "$DAIDAI_DIR"
    mkdir -p "$DAIDAI_DATA"
    mkdir -p "$DAIDAI_DATA/scripts"
    mkdir -p "$DAIDAI_DATA/logs"
    mkdir -p "$DAIDAI_DATA/backups"
    
    # 获取最新版本
    log_info "获取最新版本..."
    LATEST_VERSION=$(curl -s "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | jq -r '.tag_name' 2>/dev/null)
    
    if [ -z "$LATEST_VERSION" ] || [ "$LATEST_VERSION" = "null" ]; then
        log_warn "获取版本失败，使用默认版本 v0.0.2"
        LATEST_VERSION="v0.0.2"
    fi
    
    log_info "版本: $LATEST_VERSION"
    
    # 下载面板二进制（Android arm64）
    log_info "下载面板程序..."
    DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/daidai-server-linux-arm64"
    
    curl -L -# -o "$DAIDAI_BIN" "$DOWNLOAD_URL" 2>&1 || {
        log_error "下载面板程序失败"
        log_warn "请检查网络连接后重试"
        exit 1
    }
    
    chmod +x "$DAIDAI_BIN"
    
    # 下载前端资源
    log_info "下载前端资源..."
    WEB_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/web.tar.gz"
    
    curl -L -# -o "/tmp/web.tar.gz" "$WEB_URL" 2>&1 || {
        log_error "下载前端资源失败"
        log_warn "请检查网络连接后重试"
        exit 1
    }
    
    # 解压前端资源
    log_info "解压前端资源..."
    tar -xzf "/tmp/web.tar.gz" -C "$DAIDAI_DIR" 2>/dev/null || {
        # 如果解压失败，尝试直接复制
        log_warn "解压失败，尝试其他方式..."
        mkdir -p "$DAIDAI_WEB"
    }
    
    rm -f "/tmp/web.tar.gz"
    
    log_info "面板下载完成"
}

# 生成配置文件
generate_config() {
    log_step "生成配置文件..."
    
    cat > "$DAIDAI_DIR/config.yaml" << EOF
# 呆呆面板配置文件
server:
  host: 0.0.0.0
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
    log_step "启动呆呆面板..."
    
    # 检查是否已运行
    if check_running; then
        log_info "面板已在运行"
        return 0
    fi
    
    # 启动面板
    cd "$DAIDAI_DIR"
    nohup "$DAIDAI_BIN" > "$DAIDAI_LOG" 2>&1 &
    
    # 等待启动
    log_info "等待面板启动..."
    sleep 3
    
    # 检查是否启动成功
    if check_running; then
        log_info "面板启动成功！"
        return 0
    else
        log_error "面板启动失败"
        if [ -f "$DAIDAI_LOG" ]; then
            echo "--- 日志 ---"
            cat "$DAIDAI_LOG"
            echo "---"
        fi
        return 1
    fi
}

# 显示完成信息
show_complete() {
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║${NC}                                                          ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}           ${GREEN}✓ 呆呆面板安装完成！${NC}                          ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}                                                          ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  ${YELLOW}访问地址: http://127.0.0.1:$PANEL_PORT${NC}                   ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}                                                          ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  ${YELLOW}首次访问请先创建管理员账号${NC}                             ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}                                                          ${GREEN}║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${BLUE}提示: 浏览器将自动打开面板页面${NC}"
    echo ""
}

# 主流程
main() {
    show_welcome
    
    # 检查是否已安装
    if check_installed; then
        log_info "检测到已安装的呆呆面板"
        
        # 检查是否在运行
        if check_running; then
            log_info "面板已在运行"
            show_complete
            exit 0
        fi
        
        # 启动面板
        start_panel
        show_complete
        exit 0
    fi
    
    # 首次安装
    log_info "首次安装呆呆面板..."
    echo ""
    
    # 安装依赖
    install_dependencies
    echo ""
    
    # 下载面板
    download_panel
    echo ""
    
    # 生成配置
    generate_config
    echo ""
    
    # 启动面板
    start_panel
    
    if [ $? -eq 0 ]; then
        show_complete
    else
        log_error "安装过程中出现错误，请查看日志"
        exit 1
    fi
}

# 执行主流程
main
