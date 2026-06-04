#!/bin/bash
# 呆呆面板一键安装脚本
# 在 Termux 中执行此脚本

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║${NC}                                                  ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}        ${GREEN}呆呆面板 - 一键安装脚本${NC}                   ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}                                                  ${BLUE}║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════╝${NC}"
echo ""

# 配置
DAIDAI_DIR="/opt/daidai-panel"
DAIDAI_DATA="$DAIDAI_DIR/Dumb-Panel"
DAIDAI_BIN="$DAIDAI_DIR/daidai-server"
DAIDAI_WEB="$DAIDAI_DIR/web"
PANEL_PORT=5700
GITHUB_REPO="tall-1997/daidai-panel-app"

# 步骤1: 安装依赖
echo -e "${YELLOW}[1/5]${NC} 安装系统依赖..."
pkg update -y 2>&1 | tail -1
pkg install -y proot python nodejs git curl wget 2>&1 | tail -1

# 检查依赖
echo -e "${GREEN}[→]${NC} 检查依赖..."
for cmd in python node git curl; do
    if command -v $cmd > /dev/null 2>&1; then
        echo -e "${GREEN}[✓]${NC} $cmd 已安装"
    else
        echo -e "${RED}[✗]${NC} $cmd 未安装"
    fi
done

echo -e "${GREEN}[✓]${NC} 依赖安装完成"

# 步骤2: 创建目录
echo -e "${YELLOW}[2/5]${NC} 创建目录结构..."
mkdir -p "$DAIDAI_DIR" "$DAIDAI_DATA" "$DAIDAI_DATA/scripts" "$DAIDAI_DATA/logs" "$DAIDAI_DATA/backups" "$DAIDAI_DATA/deps"
echo -e "${GREEN}[✓]${NC} 目录创建完成"

# 步骤3: 下载面板
echo -e "${YELLOW}[3/5]${NC} 下载呆呆面板..."

# 获取最新版本
echo -e "${GREEN}[→]${NC} 获取最新版本..."
LATEST_VERSION=$(curl -s "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
if [ -z "$LATEST_VERSION" ] || [ "$LATEST_VERSION" = "null" ]; then
    LATEST_VERSION="v0.0.2"
    echo -e "${YELLOW}[!]${NC} 获取版本失败，使用默认版本: $LATEST_VERSION"
else
    echo -e "${GREEN}[✓]${NC} 最新版本: $LATEST_VERSION"
fi

# 下载面板二进制
echo -e "${GREEN}[→]${NC} 下载面板程序..."
DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/daidai-server-linux-arm64"
echo -e "${GREEN}[→]${NC} URL: $DOWNLOAD_URL"

curl -L --progress-bar -o "$DAIDAI_BIN" "$DOWNLOAD_URL" 2>&1 || {
    echo -e "${YELLOW}[!]${NC} 下载失败，尝试备用地址..."
    curl -L --progress-bar -o "$DAIDAI_BIN" "https://github.com/$GITHUB_REPO/releases/download/v0.0.2/daidai-server-linux-arm64" 2>&1 || {
        echo -e "${RED}[✗]${NC} 面板程序下载失败"
        exit 1
    }
}

# 检查文件
if [ -f "$DAIDAI_BIN" ]; then
    FILE_SIZE=$(stat -c%s "$DAIDAI_BIN" 2>/dev/null || stat -f%z "$DAIDAI_BIN" 2>/dev/null || echo "0")
    echo -e "${GREEN}[✓]${NC} 面板程序下载完成 (大小: $FILE_SIZE 字节)"
    chmod +x "$DAIDAI_BIN"
else
    echo -e "${RED}[✗]${NC} 面板程序下载失败"
    exit 1
fi

# 下载前端资源
echo -e "${GREEN}[→]${NC} 下载前端资源..."
WEB_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/web.tar.gz"
echo -e "${GREEN}[→]${NC} URL: $WEB_URL"

curl -L --progress-bar -o "/tmp/web.tar.gz" "$WEB_URL" 2>&1 || {
    echo -e "${YELLOW}[!]${NC} 下载失败，尝试备用地址..."
    curl -L --progress-bar -o "/tmp/web.tar.gz" "https://github.com/$GITHUB_REPO/releases/download/v0.0.2/web.tar.gz" 2>&1 || {
        echo -e "${RED}[✗]${NC} 前端资源下载失败"
        exit 1
    }
}

# 检查并解压
if [ -f "/tmp/web.tar.gz" ]; then
    FILE_SIZE=$(stat -c%s "/tmp/web.tar.gz" 2>/dev/null || stat -f%z "/tmp/web.tar.gz" 2>/dev/null || echo "0")
    echo -e "${GREEN}[✓]${NC} 前端资源下载完成 (大小: $FILE_SIZE 字节)"
    
    echo -e "${GREEN}[→]${NC} 解压前端资源..."
    tar -xzf "/tmp/web.tar.gz" -C "$DAIDAI_DIR" 2>&1 || {
        echo -e "${YELLOW}[!]${NC} 解压失败，创建空目录..."
        mkdir -p "$DAIDAI_WEB"
    }
    rm -f "/tmp/web.tar.gz"
else
    echo -e "${RED}[✗]${NC} 前端资源下载失败"
    mkdir -p "$DAIDAI_WEB"
fi

# 检查前端目录
if [ -d "$DAIDAI_WEB" ] && [ "$(ls -A $DAIDAI_WEB 2>/dev/null)" ]; then
    echo -e "${GREEN}[✓]${NC} 前端资源就绪"
else
    echo -e "${YELLOW}[!]${NC} 前端资源可能不完整"
fi

echo -e "${GREEN}[✓]${NC} 面板下载完成"

# 步骤4: 生成配置
echo -e "${YELLOW}[4/5]${NC} 生成配置文件..."
cat > "$DAIDAI_DIR/config.yaml" << EOF
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
echo -e "${GREEN}[✓]${NC} 配置文件生成完成"

# 步骤5: 创建快捷命令
echo -e "${YELLOW}[5/5]${NC} 创建快捷命令..."

# 启动脚本
cat > /start.sh << 'EOF'
#!/bin/bash
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

DAIDAI_DIR="/opt/daidai-panel"
DAIDAI_BIN="$DAIDAI_DIR/daidai-server"
DAIDAI_WEB="$DAIDAI_DIR/web"
DAIDAI_LOG="$DAIDAI_DIR/daidai.log"

echo ""
echo -e "${GREEN}╔══════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║${NC}        ${YELLOW}呆呆面板启动${NC}                       ${GREEN}║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════╝${NC}"

# 检查面板程序
if [ ! -f "$DAIDAI_BIN" ]; then
    echo -e "${RED}[✗]${NC} 面板程序不存在: $DAIDAI_BIN"
    echo -e "${RED}[✗]${NC} 请重新运行安装脚本"
    exit 1
fi

# 检查是否已运行
if pgrep -f "daidai-server" > /dev/null 2>&1; then
    echo -e "${GREEN}[✓]${NC} 面板已在运行"
    echo -e "${GREEN}[✓]${NC} 访问: http://127.0.0.1:5700"
    exit 0
fi

# 启动面板
echo -e "${GREEN}[→]${NC} 正在启动面板..."
cd "$DAIDAI_DIR"
nohup "$DAIDAI_BIN" > "$DAIDAI_LOG" 2>&1 &

# 等待启动
sleep 3

# 检查是否启动成功
if pgrep -f "daidai-server" > /dev/null 2>&1; then
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║${NC}        ${GREEN}✓ 面板启动成功！${NC}                  ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}                                          ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  ${YELLOW}访问地址: http://127.0.0.1:5700${NC}       ${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}                                          ${GREEN}║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════╝${NC}"
else
    echo ""
    echo -e "${RED}[✗]${NC} 面板启动失败"
    echo -e "${RED}[✗]${NC} 查看日志: cat $DAIDAI_LOG"
    if [ -f "$DAIDAI_LOG" ]; then
        echo ""
        echo "--- 日志内容 ---"
        cat "$DAIDAI_LOG"
        echo "---"
    fi
fi
echo ""
EOF
chmod +x /start.sh

# 停止脚本
cat > /stop.sh << 'EOF'
#!/bin/bash
pkill -f "daidai-server" 2>/dev/null && echo "面板已停止" || echo "面板未在运行"
EOF
chmod +x /stop.sh

# 状态脚本
cat > /status.sh << 'EOF'
#!/bin/bash
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'
if pgrep -f "daidai-server" > /dev/null 2>&1; then
    echo -e "状态: ${GREEN}运行中${NC} | 访问: http://127.0.0.1:5700"
else
    echo -e "状态: ${RED}未运行${NC} | 启动: sh /start.sh"
fi
EOF
chmod +x /status.sh

echo -e "${GREEN}[✓]${NC} 快捷命令创建完成"

# 完成
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║${NC}                                                  ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}        ${GREEN}✓ 呆呆面板安装完成！${NC}                     ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}                                                  ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}  ${YELLOW}启动命令: sh /start.sh${NC}                          ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}  ${YELLOW}停止命令: sh /stop.sh${NC}                           ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}  ${YELLOW}查看状态: sh /status.sh${NC}                         ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}                                                  ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}  ${YELLOW}访问地址: http://127.0.0.1:5700${NC}                  ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}                                                  ${GREEN}║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${BLUE}提示: 首次访问需要创建管理员账号${NC}"
echo ""

# 自动启动
sh /start.sh
