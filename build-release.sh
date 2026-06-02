#!/bin/bash

# Release Build Script for Daidai Panel Mobile
# Builds Android APK and iOS IPA for release

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_msg() {
    echo -e "${GREEN}[RELEASE]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# Configuration
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION="0.0.1"
RELEASE_DIR="${PROJECT_ROOT}/dist/v${VERSION}"
SCRIPTS_DIR="${PROJECT_ROOT}/scripts"

# Check prerequisites
check_prerequisites() {
    print_step "Checking prerequisites..."
    
    local missing=()
    
    if ! command -v go &> /dev/null; then
        missing+=("go")
    fi
    
    if ! command -v node &> /dev/null; then
        missing+=("node")
    fi
    
    if ! command -v npm &> /dev/null; then
        missing+=("npm")
    fi
    
    if [ ${#missing[@]} -gt 0 ]; then
        print_error "Missing prerequisites: ${missing[*]}"
        exit 1
    fi
    
    print_msg "All prerequisites found"
}

# Clean release directory
clean_release() {
    print_step "Cleaning release directory..."
    
    rm -rf "${RELEASE_DIR}"
    mkdir -p "${RELEASE_DIR}/android"
    mkdir -p "${RELEASE_DIR}/ios"
    
    print_msg "Release directory ready: ${RELEASE_DIR}"
}

# Build frontend
build_frontend() {
    print_step "Building frontend..."
    
    "${SCRIPTS_DIR}/build-frontend.sh" all
    
    print_msg "Frontend built"
}

# Build Android
build_android() {
    print_step "Building Android APK..."
    
    # Build Go binary for Android
    cd "${PROJECT_ROOT}/server"
    
    print_msg "Building Go binary for arm64..."
    CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build \
        -o "${PROJECT_ROOT}/android/app/src/main/assets/bin/daidai-server-arm64" \
        -ldflags="-s -w" \
        ./cmd/mobile
    
    print_msg "Building Go binary for armv7..."
    CGO_ENABLED=0 GOOS=android GOARCH=arm GOARM=7 go build \
        -o "${PROJECT_ROOT}/android/app/src/main/assets/bin/daidai-server-armv7" \
        -ldflags="-s -w" \
        ./cmd/mobile
    
    # Build APK
    cd "${PROJECT_ROOT}/android"
    
    if [ ! -f "gradlew" ]; then
        print_warn "Gradle wrapper not found, creating..."
        gradle wrapper --gradle-version=8.4
    fi
    
    print_msg "Building debug APK..."
    ./gradlew assembleDebug
    
    # Copy APK to release directory
    local apk_path="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "${apk_path}" ]; then
        cp "${apk_path}" "${RELEASE_DIR}/android/daidai-panel-v${VERSION}-debug.apk"
        local size=$(du -sh "${RELEASE_DIR}/android/daidai-panel-v${VERSION}-debug.apk" | cut -f1)
        print_msg "Android APK: daidai-panel-v${VERSION}-debug.apk (${size})"
    else
        print_error "Android APK build failed"
        exit 1
    fi
}

# Build iOS (if on macOS)
build_ios() {
    if [[ "$(uname)" != "Darwin" ]]; then
        print_warn "Skipping iOS build (not on macOS)"
        return
    fi
    
    print_step "Building iOS IPA..."
    
    "${SCRIPTS_DIR}/build-ios.sh" build
    
    # Copy IPA to release directory
    local ipa_path="${PROJECT_ROOT}/dist/ios/DaidaiPanel-unsigned.ipa"
    if [ -f "${ipa_path}" ]; then
        cp "${ipa_path}" "${RELEASE_DIR}/ios/daidai-panel-v${VERSION}-unsigned.ipa"
        local size=$(du -sh "${RELEASE_DIR}/ios/daidai-panel-v${VERSION}-unsigned.ipa" | cut -f1)
        print_msg "iOS IPA: daidai-panel-v${VERSION}-unsigned.ipa (${size})"
    else
        print_warn "iOS IPA not found"
    fi
}

# Generate release notes
generate_release_notes() {
    print_step "Generating release notes..."
    
    cat > "${RELEASE_DIR}/RELEASE_NOTES.md" << EOF
# 呆呆面板移动端 v${VERSION}

## 版本信息

- 版本号: v${VERSION}
- 构建时间: $(date -u +"%Y-%m-%d %H:%M:%S UTC")
- 面板版本: 2.2.14-mobile

## 更新内容

### Android
- 修复启动面板失败问题
- 使用子进程方式启动Go后端服务
- 支持 arm64 和 armv7 架构
- 最低支持 Android 9 (API 28)

### iOS
- 新增不含签名的 IPA 安装包
- 支持 arm64 架构
- 最低支持 iOS 16

## 安装说明

### Android
1. 下载 \`daidai-panel-v${VERSION}-debug.apk\`
2. 在设备上打开 APK 文件进行安装
3. 首次安装需要允许"未知来源"应用

### iOS
1. 下载 \`daidai-panel-v${VERSION}-unsigned.ipa\`
2. 需要使用签名工具签名后才能安装
3. 或者使用 Xcode 直接编译安装到设备

## 注意事项

- Android 版本需要保持后台运行才能正常使用面板服务
- iOS 版本受系统限制，后台运行时间有限
- 建议定期备份面板数据
EOF
    
    print_msg "Release notes generated"
}

# Show summary
show_summary() {
    print_step "Release Summary"
    
    echo ""
    echo "Release v${VERSION} artifacts:"
    echo ""
    
    # List files
    find "${RELEASE_DIR}" -type f -name "*.apk" -o -name "*.ipa" -o -name "*.md" | sort | while read file; do
        local size=$(du -sh "${file}" | cut -f1)
        echo "  $(basename "${file}") (${size})"
    done
    
    echo ""
    echo "Release directory: ${RELEASE_DIR}"
    echo ""
}

# Show help
show_help() {
    echo "Release Build Script for Daidai Panel Mobile"
    echo ""
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  all        Build everything (Android + iOS)"
    echo "  android    Build Android only"
    echo "  ios        Build iOS only (macOS only)"
    echo "  clean      Clean release artifacts"
    echo "  help       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0           # Build all platforms"
    echo "  $0 all       # Build all platforms"
    echo "  $0 android   # Build Android only"
}

# Main function
main() {
    local command="${1:-all}"
    
    case "${command}" in
        all)
            print_msg "Starting release build for v${VERSION}..."
            check_prerequisites
            clean_release
            build_frontend
            build_android
            build_ios
            generate_release_notes
            show_summary
            ;;
        android)
            print_msg "Starting Android release build for v${VERSION}..."
            check_prerequisites
            clean_release
            build_frontend
            build_android
            generate_release_notes
            show_summary
            ;;
        ios)
            print_msg "Starting iOS release build for v${VERSION}..."
            check_prerequisites
            clean_release
            build_frontend
            build_ios
            generate_release_notes
            show_summary
            ;;
        clean)
            print_msg "Cleaning release artifacts..."
            rm -rf "${PROJECT_ROOT}/dist"
            print_msg "Clean completed"
            ;;
        help|*)
            show_help
            ;;
    esac
}

# Run main function
main "$@"
