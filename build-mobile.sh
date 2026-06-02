#!/bin/bash

# Complete Build Script for Daidai Panel Mobile
# This script builds everything for Android and iOS platforms

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_header() {
    echo -e "${BLUE}"
    echo "=========================================="
    echo "  Daidai Panel Mobile Build System"
    echo "=========================================="
    echo -e "${NC}"
}

print_msg() {
    echo -e "${GREEN}[BUILD]${NC} $1"
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
SCRIPTS_DIR="${PROJECT_ROOT}/scripts"

# Check prerequisites
check_prerequisites() {
    print_step "Checking prerequisites..."
    
    local missing=()
    
    # Check Go
    if ! command -v go &> /dev/null; then
        missing+=("go")
    fi
    
    # Check Node.js
    if ! command -v node &> /dev/null; then
        missing+=("node")
    fi
    
    # Check npm
    if ! command -v npm &> /dev/null; then
        missing+=("npm")
    fi
    
    if [ ${#missing[@]} -gt 0 ]; then
        print_error "Missing prerequisites: ${missing[*]}"
        exit 1
    fi
    
    print_msg "All prerequisites found"
}

# Build Go backend for Android
build_go_android() {
    print_step "Building Go backend for Android..."
    
    cd "${PROJECT_ROOT}/server"
    
    # Create output directories
    mkdir -p "${PROJECT_ROOT}/android/app/src/main/assets/bin"
    
    # Build for arm64 (most modern devices)
    print_msg "Building for arm64..."
    CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build \
        -o "${PROJECT_ROOT}/android/app/src/main/assets/bin/daidai-server-arm64" \
        -ldflags="-s -w" \
        .
    
    print_msg "Go backend built for Android (arm64)"
    print_msg "Binary size: $(du -sh ${PROJECT_ROOT}/android/app/src/main/assets/bin/daidai-server-arm64 | cut -f1)"
}

# Build Go backend for iOS
build_go_ios() {
    print_step "Building Go backend for iOS..."
    
    cd "${PROJECT_ROOT}/server"
    
    # Check gomobile
    if ! command -v gomobile &> /dev/null; then
        print_warn "gomobile not found, installing..."
        go install golang.org/x/mobile/cmd/gomobile@latest
        go install golang.org/x/mobile/cmd/gobind@latest
        gomobile init
    fi
    
    # Build xcframework
    print_msg "Building xcframework..."
    gomobile bind \
        -target=ios \
        -iosversion=16.0 \
        -o "${PROJECT_ROOT}/ios/DaidaiPanel/Frameworks/DaidaiPanel.xcframework" \
        -ldflags="-s -w" \
        ./mobile
    
    print_msg "Go backend built for iOS"
}

# Build frontend
build_frontend() {
    print_step "Building frontend..."
    
    "${SCRIPTS_DIR}/build-frontend.sh" all
    
    print_msg "Frontend built"
}

# Build Android APK
build_android_apk() {
    print_step "Building Android APK..."
    
    cd "${PROJECT_ROOT}/android"
    
    # Check if gradle wrapper exists
    if [ ! -f "gradlew" ]; then
        print_warn "Gradle wrapper not found, creating..."
        gradle wrapper --gradle-version=8.4
    fi
    
    # Build debug APK
    print_msg "Building debug APK..."
    ./gradlew assembleDebug
    
    # Check if APK was created
    local apk_path="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "${apk_path}" ]; then
        local size=$(du -sh "${apk_path}" | cut -f1)
        print_msg "Android APK built: ${apk_path} (${size})"
    else
        print_error "Android APK build failed"
        exit 1
    fi
}

# Build iOS IPA
build_ios_ipa() {
    print_step "Building iOS IPA..."
    
    cd "${PROJECT_ROOT}/ios"
    
    # Check if Xcode is available
    if ! command -v xcodebuild &> /dev/null; then
        print_warn "Xcode not found, skipping iOS build"
        return
    fi
    
    # Build for simulator (for testing)
    print_msg "Building for simulator..."
    xcodebuild \
        -scheme DaidaiPanel \
        -destination 'platform=iOS Simulator,name=iPhone 15' \
        -configuration Debug \
        build
    
    print_msg "iOS build completed"
}

# Show build summary
show_summary() {
    print_step "Build Summary"
    
    echo ""
    echo "Build artifacts:"
    echo ""
    
    # Android
    local android_apk="${PROJECT_ROOT}/android/app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "${android_apk}" ]; then
        local size=$(du -sh "${android_apk}" | cut -f1)
        echo "  Android APK: ${android_apk} (${size})"
    fi
    
    # iOS
    echo "  iOS Project: ${PROJECT_ROOT}/ios/"
    echo ""
    
    echo "Next steps:"
    echo ""
    echo "  Android:"
    echo "    1. Install APK on device: adb install ${android_apk}"
    echo "    2. Or transfer APK to device and install manually"
    echo ""
    echo "  iOS:"
    echo "    1. Open ${PROJECT_ROOT}/ios/DaidaiPanel.xcodeproj in Xcode"
    echo "    2. Configure signing & capabilities"
    echo "    3. Build and run on device"
    echo ""
}

# Show help
show_help() {
    print_header
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  all          Build everything (Android + iOS + Frontend)"
    echo "  android      Build Android only"
    echo "  ios          Build iOS only"
    echo "  frontend     Build frontend only"
    echo "  go-android   Build Go backend for Android"
    echo "  go-ios       Build Go backend for iOS"
    echo "  clean        Clean all build artifacts"
    echo "  help         Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 all       # Build everything"
    echo "  $0 android   # Build Android only"
    echo "  $0 ios       # Build iOS only"
}

# Clean build artifacts
clean_build() {
    print_step "Cleaning build artifacts..."
    
    # Clean frontend
    "${SCRIPTS_DIR}/build-frontend.sh" clean
    
    # Clean Android
    cd "${PROJECT_ROOT}/android"
    if [ -f "gradlew" ]; then
        ./gradlew clean
    fi
    rm -rf app/build
    
    # Clean iOS
    cd "${PROJECT_ROOT}/ios"
    rm -rf build
    rm -rf DerivedData
    
    # Clean Go build artifacts
    rm -rf "${PROJECT_ROOT}/android/app/src/main/jniLibs/arm64-v8a/libdaidai.so"
    rm -rf "${PROJECT_ROOT}/android/app/src/main/jniLibs/armeabi-v7a/libdaidai.so"
    rm -rf "${PROJECT_ROOT}/ios/DaidaiPanel/Frameworks/DaidaiPanel.xcframework"
    
    print_msg "Clean completed"
}

# Main function
main() {
    local command="${1:-help}"
    
    case "${command}" in
        all)
            print_header
            check_prerequisites
            build_frontend
            build_go_android
            build_go_ios
            build_android_apk
            build_ios_ipa
            show_summary
            ;;
        android)
            print_header
            check_prerequisites
            build_frontend
            build_go_android
            build_android_apk
            show_summary
            ;;
        ios)
            print_header
            check_prerequisites
            build_frontend
            build_go_ios
            build_ios_ipa
            show_summary
            ;;
        frontend)
            build_frontend
            ;;
        go-android)
            build_go_android
            ;;
        go-ios)
            build_go_ios
            ;;
        clean)
            clean_build
            ;;
        help|*)
            show_help
            ;;
    esac
}

# Run main function
main "$@"
