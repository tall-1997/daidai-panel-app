#!/bin/bash
# ZeroTermux 呆呆面板版完整构建脚本
# 此脚本会克隆 ZeroTermux，集成呆呆面板，生成可安装的 APK

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
echo -e "${BLUE}║${NC}    ${GREEN}ZeroTermux 呆呆面板版 - 完整构建${NC}               ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}                                                  ${BLUE}║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════╝${NC}"
echo ""

# 配置
ZEROTERMUX_REPO="https://github.com/hanxinhao000/ZeroTermux.git"
DAIDAI_REPO="https://github.com/tall-1997/daidai-panel-app.git"
BUILD_DIR="/tmp/zerotermux-build"
OUTPUT_DIR="/workspace/ZeroTermux-daidai-panel/output"

# 清理
echo -e "${YELLOW}[1/6]${NC} 清理构建目录..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"

# 克隆 ZeroTermux
echo -e "${YELLOW}[2/6]${NC} 克隆 ZeroTermux..."
git clone --depth 1 "$ZEROTERMUX_REPO" "$BUILD_DIR/ZeroTermux" 2>&1 | tail -1
echo -e "${GREEN}[✓]${NC} ZeroTermux 克隆完成"

# 克隆呆呆面板
echo -e "${YELLOW}[3/6]${NC} 克隆呆呆面板..."
git clone --depth 1 "$DAIDAI_REPO" "$BUILD_DIR/daidai-panel" 2>&1 | tail -1
echo -e "${GREEN}[✓]${NC} 呆呆面板克隆完成"

# 集成呆呆面板资源
echo -e "${YELLOW}[4/6]${NC} 集成呆呆面板..."

# 创建 assets 目录
mkdir -p "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/web"

# 下载面板二进制
echo -e "${GREEN}[→]${NC} 下载面板程序..."
LATEST_VERSION=$(curl -s "https://api.github.com/repos/tall-1997/daidai-panel-app/releases/latest" | grep '"tag_name"' | sed -E 's/.*"([^"]+)".*/\1/')
if [ -z "$LATEST_VERSION" ] || [ "$LATEST_VERSION" = "null" ]; then
    LATEST_VERSION="v0.0.2"
fi

curl -L -o "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/daidai-server-arm64" \
  "https://github.com/tall-1997/daidai-panel-app/releases/download/$LATEST_VERSION/daidai-server-linux-arm64" 2>/dev/null || \
curl -L -o "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/daidai-server-arm64" \
  "https://github.com/tall-1997/daidai-panel-app/releases/download/v0.0.2/daidai-server-linux-arm64" 2>/dev/null
chmod +x "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/daidai-server-arm64"

# 下载前端资源
echo -e "${GREEN}[→]${NC} 下载前端资源..."
curl -L -o "/tmp/web.tar.gz" \
  "https://github.com/tall-1997/daidai-panel-app/releases/download/$LATEST_VERSION/web.tar.gz" 2>/dev/null || \
curl -L -o "/tmp/web.tar.gz" \
  "https://github.com/tall-1997/daidai-panel-app/releases/download/v0.0.2/web.tar.gz" 2>/dev/null
tar -xzf "/tmp/web.tar.gz" -C "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/web" 2>/dev/null || true
rm -f "/tmp/web.tar.gz"

# 创建配置文件
cat > "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/config.yaml" << 'EOF'
server:
  host: 0.0.0.0
  port: 5700
  mode: release
data:
  dir: /opt/daidai-panel/Dumb-Panel
  db: /opt/daidai-panel/Dumb-Panel/daidai.db
web:
  dir: /opt/daidai-panel/web
EOF

# 创建初始化脚本
cat > "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/init.sh" << 'INITEOF'
#!/data/data/com.termux/files/usr/bin/bash
# 呆呆面板自动初始化脚本

DAIDAI_DIR="/opt/daidai-panel"
DAIDAI_DATA="$DAIDAI_DIR/Dumb-Panel"
DAIDAI_BIN="$DAIDAI_DIR/daidai-server"
DAIDAI_WEB="$DAIDAI_DIR/web"
DAIDAI_LOG="$DAIDAI_DIR/daidai.log"
PANEL_PORT=5700

# 检查是否已运行
if pgrep -f "daidai-server" > /dev/null 2>&1; then
    return 0 2>/dev/null || exit 0
fi

# 安装依赖
pkg update -y > /dev/null 2>&1
pkg install -y python nodejs > /dev/null 2>&1

# 创建目录
mkdir -p "$DAIDAI_DIR" "$DAIDAI_DATA" "$DAIDAI_DATA/scripts" "$DAIDAI_DATA/logs" "$DAIDAI_DATA/backups"

# 复制资源文件
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$SCRIPT_DIR/daidai-server-arm64" "$DAIDAI_BIN" 2>/dev/null
chmod +x "$DAIDAI_BIN"
cp -r "$SCRIPT_DIR/web" "$DAIDAI_WEB" 2>/dev/null
cp "$SCRIPT_DIR/config.yaml" "$DAIDAI_DIR/config.yaml" 2>/dev/null

# 启动面板
cd "$DAIDAI_DIR"
nohup "$DAIDAI_BIN" > "$DAIDAI_LOG" 2>&1 &
sleep 3

if pgrep -f "daidai-server" > /dev/null 2>&1; then
    echo ""
    echo "=========================================="
    echo "  呆呆面板启动成功！"
    echo "  访问地址: http://127.0.0.1:$PANEL_PORT"
    echo "=========================================="
    echo ""
fi
INITEOF
chmod +x "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/init.sh"

# 复制 DaidaiInitializer.java
cp /workspace/ZeroTermux-daidai-panel/app/src/main/java/com/termux/daidai/DaidaiInitializer.java \
   "$BUILD_DIR/ZeroTermux/app/src/main/java/com/termux/daidai/"

# 修改 TermuxActivity，在启动时自动初始化
echo -e "${GREEN}[→]${NC} 修改启动逻辑..."

# 创建自动启动脚本
cat > "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/autostart.sh" << 'AUTOEOF'
#!/data/data/com.termux/files/usr/bin/bash
# 呆呆面板自动启动脚本 - 在 .bashrc 中调用

# 检查是否已初始化
if [ ! -f /opt/daidai-panel/daidai-server ]; then
    # 首次启动，执行初始化
    if [ -f ~/daidai/init.sh ]; then
        source ~/daidai/init.sh
    fi
else
    # 已初始化，检查是否在运行
    if ! pgrep -f "daidai-server" > /dev/null 2>&1; then
        cd /opt/daidai-panel
        nohup ./daidai-server > daidai.log 2>&1 &
        sleep 2
        if pgrep -f "daidai-server" > /dev/null 2>&1; then
            echo ""
            echo "╔══════════════════════════════════════════╗"
            echo "║        呆呆面板已启动                      ║"
            echo "║  访问地址: http://127.0.0.1:5700           ║"
            echo "╚══════════════════════════════════════════╝"
            echo ""
        fi
    fi
fi
AUTOEOF
chmod +x "$BUILD_DIR/ZeroTermux/app/src/main/assets/daidai/autostart.sh"

# 修改 .bashrc 添加自动启动
BASHRC="$BUILD_DIR/ZeroTermux/app/src/main/assets/.bashrc"
if [ -f "$BASHRC" ]; then
    # 检查是否已添加
    if ! grep -q "daidai" "$BASHRC"; then
        cat >> "$BASHRC" << 'BASHEOF'

# 呆呆面板自动启动
if [ -f ~/daidai/autostart.sh ]; then
    source ~/daidai/autostart.sh
fi

# 呆呆面板快捷命令
alias daidai-start='bash ~/daidai/init.sh'
alias daidai-stop='pkill -f daidai-server'
alias daidai-status='pgrep -f daidai-server > /dev/null && echo "运行中: http://127.0.0.1:5700" || echo "未运行"'
BASHEOF
    fi
else
    # 创建 .bashrc
    cat > "$BASHRC" << 'BASHEOF'
# 呆呆面板自动启动
if [ -f ~/daidai/autostart.sh ]; then
    source ~/daidai/autostart.sh
fi

# 呆呆面板快捷命令
alias daidai-start='bash ~/daidai/init.sh'
alias daidai-stop='pkill -f daidai-server'
alias daidai-status='pgrep -f daidai-server > /dev/null && echo "运行中: http://127.0.0.1:5700" || echo "未运行"'
BASHEOF
fi

echo -e "${GREEN}[✓]${NC} 集成完成"

# 构建 APK
echo -e "${YELLOW}[5/6]${NC} 构建 APK..."
cd "$BUILD_DIR/ZeroTermux"

# 检查是否有 gradlew
if [ ! -f "gradlew" ]; then
    echo -e "${RED}[✗]${NC} 未找到 gradlew，请确保已安装 Android SDK"
    exit 1
fi

chmod +x gradlew
./gradlew assembleDebug 2>&1 | tail -5

echo -e "${GREEN}[✓]${NC} APK 构建完成"

# 复制 APK
echo -e "${YELLOW}[6/6]${NC} 复制 APK..."
APK_FILE="$BUILD_DIR/ZeroTermux/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_FILE" ]; then
    cp "$APK_FILE" "$OUTPUT_DIR/daidai-panel-zerotermux.apk"
    echo -e "${GREEN}[✓]${NC} APK 已复制到: $OUTPUT_DIR/daidai-panel-zerotermux.apk"
else
    echo -e "${RED}[✗]${NC} APK 文件不存在"
fi

# 完成
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║${NC}                                                  ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}        ${GREEN}✓ 构建完成！${NC}                             ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}                                                  ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}  ${YELLOW}APK 位置: $OUTPUT_DIR/daidai-panel-zerotermux.apk${NC}  ${GREEN}║${NC}"
echo -e "${GREEN}║${NC}                                                  ${GREEN}║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════╝${NC}"
echo ""
echo "使用方法："
echo "1. 安装 APK"
echo "2. 打开应用"
echo "3. 等待初始化完成"
echo "4. 浏览器访问 http://127.0.0.1:5700"
echo ""
