#!/bin/bash

# Daidai Panel Mobile Build Script
# This script builds the panel server for Android and iOS platforms

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
MODULE_NAME="daidai-panel"
BUILD_DIR="./build"
WEB_DIR="./web"
DIST_DIR="./dist"

# Print colored message
print_msg() {
    echo -e "${GREEN}[BUILD]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Clean build directory
clean() {
    print_msg "Cleaning build directory..."
    rm -rf "${BUILD_DIR}"
    mkdir -p "${BUILD_DIR}"
}

# Build frontend
build_frontend() {
    print_msg "Building frontend..."
    cd "${WEB_DIR}"
    
    # Install dependencies if needed
    if [ ! -d "node_modules" ]; then
        npm install
    fi
    
    # Build for production
    npm run build
    
    # Copy to dist
    cd ..
    rm -rf "${DIST_DIR}"
    cp -r "${WEB_DIR}/dist" "${DIST_DIR}"
    
    print_msg "Frontend built successfully"
}

# Build for Android
build_android() {
    print_msg "Building for Android..."
    
    # Android architectures
    local archs=("arm64" "arm")
    local android_archs=("arm64-v8a" "armeabi-v7a")
    
    for i in "${!archs[@]}"; do
        local arch="${archs[$i]}"
        local android_arch="${android_archs[$i]}"
        
        print_msg "Building for Android ${android_arch}..."
        
        # Set output directory
        local output_dir="${BUILD_DIR}/android/${android_arch}"
        mkdir -p "${output_dir}"
        
        # Build binary
        CGO_ENABLED=0 GOOS=android GOARCH="${arch}" go build \
            -o "${output_dir}/daidai-server" \
            -ldflags="-s -w" \
            .
        
        print_msg "Built Android ${android_arch} binary"
    done
    
    # Copy frontend assets
    local web_assets_dir="${BUILD_DIR}/android/assets/web"
    mkdir -p "${web_assets_dir}"
    cp -r "${DIST_DIR}/"* "${web_assets_dir}/"
    
    print_msg "Android build completed"
}

# Build for iOS
build_ios() {
    print_msg "Building for iOS..."
    
    # Check if gomobile is installed
    if ! command -v gomobile &> /dev/null; then
        print_warn "gomobile not found, installing..."
        go install golang.org/x/mobile/cmd/gomobile@latest
        go install golang.org/x/mobile/cmd/gobind@latest
        gomobile init
    fi
    
    # Set output directory
    local output_dir="${BUILD_DIR}/ios"
    mkdir -p "${output_dir}"
    
    # Build xcframework
    gomobile bind \
        -target=ios \
        -iosversion=16.0 \
        -o "${output_dir}/DaidaiPanel.xcframework" \
        -ldflags="-s -w" \
        ./mobile
    
    # Copy frontend assets
    local web_assets_dir="${output_dir}/web"
    mkdir -p "${web_assets_dir}"
    cp -r "${DIST_DIR}/"* "${web_assets_dir}/"
    
    print_msg "iOS build completed"
}

# Build for desktop (development)
build_desktop() {
    print_msg "Building for desktop..."
    
    local output_dir="${BUILD_DIR}/desktop"
    mkdir -p "${output_dir}"
    
    # Build binary
    CGO_ENABLED=0 go build \
        -o "${output_dir}/daidai-server" \
        -ldflags="-s -w" \
        .
    
    # Copy frontend assets
    cp -r "${DIST_DIR}" "${output_dir}/web"
    
    print_msg "Desktop build completed"
}

# Show help
show_help() {
    echo "Daidai Panel Mobile Build Script"
    echo ""
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  all        Build all platforms (Android, iOS, Desktop)"
    echo "  android    Build for Android (arm64, arm)"
    echo "  ios        Build for iOS (arm64)"
    echo "  desktop    Build for desktop (development)"
    echo "  frontend   Build frontend only"
    echo "  clean      Clean build directory"
    echo "  help       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 all       # Build everything"
    echo "  $0 android   # Build Android only"
    echo "  $0 ios       # Build iOS only"
}

# Main function
main() {
    local command="${1:-help}"
    
    case "${command}" in
        all)
            clean
            build_frontend
            build_android
            build_ios
            build_desktop
            print_msg "All builds completed!"
            ;;
        android)
            clean
            build_frontend
            build_android
            print_msg "Android build completed!"
            ;;
        ios)
            clean
            build_frontend
            build_ios
            print_msg "iOS build completed!"
            ;;
        desktop)
            clean
            build_frontend
            build_desktop
            print_msg "Desktop build completed!"
            ;;
        frontend)
            build_frontend
            ;;
        clean)
            clean
            ;;
        help|*)
            show_help
            ;;
    esac
}

# Run main function
main "$@"
