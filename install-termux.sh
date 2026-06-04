#!/bin/bash
# 呆呆面板一键安装脚本
# 在 ZeroTermux 的 Alpine 环境中执行此脚本

set -e

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
echo -e "${BLUE}║${NC}  ${YELLOW}适用于 ZeroTermux Alpine 环境${NC}                   ${BLUE}║${NC}"
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
apk update > /dev/null 2>&1
apk add --no-cache python3 py3-pip nodejs npm git curl wget jq bash > /dev/null 2>&1
echo -e "${GREEN}[✓]${NC} 依赖安装完成"

# 步骤2: 创建目录
echo -e "${YELLOW}[2/5]${NC} 创建目录结构..."
mkdir -p "$DAIDAI_DIR" "$DAIDAI_DATA" "$DAIDAI_DATA/scripts" "$DAIDAI_DATA/logs" "$DAIDAI_DATA/backups" "$DAIDAI_DATA/deps"
echo -e "${GREEN}[✓]${NC} 目录创建完成"

# 步骤3: 下载面板
echo -e "${YELLOW}[3/5]${NC} 下载呆呆面板..."

# 获取最新版本
LATEST_VERSION=$(curl -s "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | jq -r '.tag_name' 2>/dev/null)
if [ -z "$LATEST_VERSION" ] || [ "$LATEST_VERSION" = "null" ]; then
    LATEST_VERSION="v0.0.2"
fi
echo -e "${GREEN}[✓]${NC} 版本: $LATEST_VERSION"

# 下载面板二进制
echo -e "${GREEN}[→]${NC} 下载面板程序..."
curl -L -o "$DAIDAI_BIN" "https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/daidai-server-linux-arm64" 2>/dev/null || \
curl -L -o "$DAIDAI_BIN" "https://github.com/$GITHUB_REPO/releases/download/v0.0.2/daidai-server-linux-arm64" 2>/dev/null
chmod +x "$DAIDAI_BIN"

# 下载前端资源
echo -e "${GREEN}[→]${NC} 下载前端资源..."
curl -L -o "/tmp/web.tar.gz" "https://github.com/$GITHUB_REPO/releases/download/$LATEST_VERSION/web.tar.gz" 2>/dev/null || \
curl -L -o "/tmp/web.tar.gz" "https://github.com/$GITHUB_REPO/releases/download/v0.0.2/web.tar.gz" 2>/dev/null
tar -xzf "/tmp/web.tar.gz" -C "$DAIDAI_DIR" 2>/dev/null || mkdir -p "$DAIDAI_WEB"
rm -f "/tmp/web.tar.gz"

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
YELLOW='\033[1;33m'
NC='\033[0m'
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║${NC}        ${YELLOW}呆呆面板${NC}                          ${GREEN}║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════╝${NC}"
if pgrep -f "daidai-server" > /dev/null 2>&1; then
    echo -e "${GREEN}[✓]${NC} 面板已在运行"
    echo -e "${GREEN}[✓]${NC} 访问: http://127.0.0.1:5700"
else
    cd /opt/daidai-panel
    nohup ./daidai-server > daidai.log 2>&1 &
    sleep 2
    if pgrep -f "daidai-server" > /dev/null 2>&1; then
        echo -e "${GREEN}[✓]${NC} 面板启动成功"
        echo -e "${GREEN}[✓]${NC} 访问: http://127.0.0.1:5700"
    else
        echo -e "${RED}[✗]${NC} 启动失败，查看日志: cat /opt/daidai-panel/daidai.log"
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
