#!/bin/bash

# Android NDK Cross-Compile Script for Daidai Panel
# This script compiles Go code into Android shared libraries

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_msg() {
    echo -e "${GREEN}[ANDROID]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Configuration
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="${PROJECT_ROOT}/server"
ANDROID_DIR="${PROJECT_ROOT}/android"
OUTPUT_DIR="${ANDROID_DIR}/app/src/main/jniLibs"

# Check NDK
check_ndk() {
    if [ -z "$ANDROID_NDK_HOME" ]; then
        # Try common locations
        for dir in \
            "$HOME/Android/Sdk/ndk" \
            "/usr/local/android-ndk" \
            "/opt/android-ndk"; do
            if [ -d "$dir" ]; then
                # Find latest version
                ANDROID_NDK_HOME=$(ls -d "$dir"/2*.* 2>/dev/null | sort -V | tail -1)
                break
            fi
        done
    fi
    
    if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
        print_error "Android NDK not found. Please set ANDROID_NDK_HOME"
        exit 1
    fi
    
    print_msg "Using NDK: $ANDROID_NDK_HOME"
}

# Build for specific architecture
build_arch() {
    local arch=$1
    local go_arch=$2
    local output_subdir=$3
    
    print_msg "Building for ${arch}..."
    
    local output_dir="${OUTPUT_DIR}/${output_subdir}"
    mkdir -p "${output_dir}"
    
    # Set environment
    export CGO_ENABLED=0
    export GOOS=android
    export GOARCH=${go_arch}
    
    # Build
    cd "${SERVER_DIR}"
    go build \
        -o "${output_dir}/libdaidai.so" \
        -buildmode=c-shared \
        -ldflags="-s -w -extldflags '-lm'" \
        ./mobile
    
    print_msg "Built ${arch} library: ${output_dir}/libdaidai.so"
}

# Main function
main() {
    print_msg "Starting Android cross-compilation..."
    
    # Check NDK
    check_ndk
    
    # Build for arm64-v8a
    build_arch "arm64-v8a" "arm64" "arm64-v8a"
    
    # Build for armeabi-v7a
    build_arch "armeabi-v7a" "arm" "armeabi-v7a"
    
    print_msg "Android cross-compilation completed!"
    print_msg "Libraries are in: ${OUTPUT_DIR}"
}

main "$@"
