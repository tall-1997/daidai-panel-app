#!/bin/bash
# 下载呆呆面板资源并打包到 assets

set -e

ASSETS_DIR="app/src/main/assets/daidai"
GITHUB_REPO="tall-1997/daidai-panel-app"
VERSION="v0.0.2"

echo "下载呆呆面板资源..."

# 创建目录
mkdir -p "$ASSETS_DIR"

# 下载面板二进制
echo "下载面板程序 (arm64)..."
curl -L -o "$ASSETS_DIR/daidai-server-arm64" \
  "https://github.com/$GITHUB_REPO/releases/download/$VERSION/daidai-server-linux-arm64" 2>/dev/null || \
curl -L -o "$ASSETS_DIR/daidai-server-arm64" \
  "https://github.com/$GITHUB_REPO/releases/download/v0.0.2/daidai-server-linux-arm64" 2>/dev/null
chmod +x "$ASSETS_DIR/daidai-server-arm64"

# 下载前端资源
echo "下载前端资源..."
curl -L -o "/tmp/web.tar.gz" \
  "https://github.com/$GITHUB_REPO/releases/download/$VERSION/web.tar.gz" 2>/dev/null || \
curl -L -o "/tmp/web.tar.gz" \
  "https://github.com/$GITHUB_REPO/releases/download/v0.0.2/web.tar.gz" 2>/dev/null

# 解压前端资源
mkdir -p "$ASSETS_DIR/web"
tar -xzf "/tmp/web.tar.gz" -C "$ASSETS_DIR/web" 2>/dev/null || true
rm -f "/tmp/web.tar.gz"

# 创建配置文件模板
cat > "$ASSETS_DIR/config.yaml" << 'EOF'
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
cat > "$ASSETS_DIR/init.sh" << 'INITEOF'
#!/data/data/com.termux/files/usr/bin/bash
# 呆呆面板自动初始化脚本

DAIDAI_DIR="/opt/daidai-panel"
DAIDAI_DATA="$DAIDAI_DIR/Dumb-Panel"
DAIDAI_BIN="$DAIDAI_DIR/daidai-server"
DAIDAI_WEB="$DAIDAI_DIR/web"
DAIDAI_LOG="$DAIDAI_DIR/daidai.log"
PANEL_PORT=5700

# 检查是否已初始化
if [ -f "$DAIDAI_BIN" ] && pgrep -f "daidai-server" > /dev/null 2>&1; then
    return 0 2>/dev/null || exit 0
fi

# 安装依赖
pkg update -y > /dev/null 2>&1
pkg install -y python nodejs > /dev/null 2>&1

# 创建目录
mkdir -p "$DAIDAI_DIR" "$DAIDAI_DATA" "$DAIDAI_DATA/scripts" "$DAIDAI_DATA/logs" "$DAIDAI_DATA/backups"

# 复制资源文件
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/daidai-server-arm64" ]; then
    cp "$SCRIPT_DIR/daidai-server-arm64" "$DAIDAI_BIN"
    chmod +x "$DAIDAI_BIN"
fi

if [ -d "$SCRIPT_DIR/web" ]; then
    cp -r "$SCRIPT_DIR/web" "$DAIDAI_WEB"
fi

if [ -f "$SCRIPT_DIR/config.yaml" ]; then
    cp "$SCRIPT_DIR/config.yaml" "$DAIDAI_DIR/config.yaml"
fi

# 启动面板
if [ -f "$DAIDAI_BIN" ]; then
    cd "$DAIDAI_DIR"
    nohup "$DAIDAI_BIN" > "$DAIDAI_LOG" 2>&1 &
    sleep 2
    if pgrep -f "daidai-server" > /dev/null 2>&1; then
        echo "=========================================="
        echo "  呆呆面板启动成功！"
        echo "  访问地址: http://127.0.0.1:$PANEL_PORT"
        echo "=========================================="
    fi
fi
INITEOF
chmod +x "$ASSETS_DIR/init.sh"

# 创建启动脚本
cat > "$ASSETS_DIR/start.sh" << 'STARTEOF'
#!/data/data/com.termux/files/usr/bin/bash
DAIDAI_DIR="/opt/daidai-panel"
DAIDAI_BIN="$DAIDAI_DIR/daidai-server"
DAIDAI_LOG="$DAIDAI_DIR/daidai.log"

if pgrep -f "daidai-server" > /dev/null 2>&1; then
    echo "面板已在运行"
    echo "访问: http://127.0.0.1:5700"
    exit 0
fi

if [ ! -f "$DAIDAI_BIN" ]; then
    echo "面板未安装"
    exit 1
fi

cd "$DAIDAI_DIR"
nohup "$DAIDAI_BIN" > "$DAIDAI_LOG" 2>&1 &
sleep 2

if pgrep -f "daidai-server" > /dev/null 2>&1; then
    echo "面板启动成功"
    echo "访问: http://127.0.0.1:5700"
else
    echo "启动失败"
fi
STARTEOF
chmod +x "$ASSETS_DIR/start.sh"

# 创建停止脚本
cat > "$ASSETS_DIR/stop.sh" << 'STOPEOF'
#!/data/data/com.termux/files/usr/bin/bash
pkill -f "daidai-server" 2>/dev/null && echo "面板已停止" || echo "面板未在运行"
STOPEOF
chmod +x "$ASSETS_DIR/stop.sh"

# 统计文件大小
echo ""
echo "=========================================="
echo "  资源下载完成"
echo "=========================================="
echo "面板程序: $(ls -lh $ASSETS_DIR/daidai-server-arm64 | awk '{print $5}')"
echo "前端资源: $(du -sh $ASSETS_DIR/web | awk '{print $1}')"
echo "=========================================="
