#!/bin/bash

# Frontend Build Script for Mobile Platforms
# This script builds the Vue.js frontend and packages it for Android and iOS

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_msg() {
    echo -e "${GREEN}[FRONTEND]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Configuration
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB_DIR="${PROJECT_ROOT}/web"
DIST_DIR="${WEB_DIR}/dist"
ANDROID_ASSETS="${PROJECT_ROOT}/android/app/src/main/assets/web"
IOS_RESOURCES="${PROJECT_ROOT}/ios/DaidaiPanel/Resources/web"

# Build frontend
build_frontend() {
    print_msg "Building frontend..."
    
    cd "${WEB_DIR}"
    
    # Install dependencies if needed
    if [ ! -d "node_modules" ]; then
        print_msg "Installing dependencies..."
        npm install
    fi
    
    # Clean previous build
    print_msg "Cleaning previous build..."
    rm -rf dist
    
    # Build for production
    print_msg "Building for production..."
    npm run build
    
    # Check if build was successful
    if [ ! -f "dist/index.html" ]; then
        print_error "Build failed: dist/index.html not found"
        exit 1
    fi
    
    print_msg "Frontend build completed"
}

# Copy to Android assets
copy_to_android() {
    print_msg "Copying to Android assets..."
    
    # Create directory
    mkdir -p "${ANDROID_ASSETS}"
    
    # Clean previous assets
    rm -rf "${ANDROID_ASSETS}"/*
    
    # Copy dist files
    cp -r "${DIST_DIR}"/* "${ANDROID_ASSETS}/"
    
    # Get size
    local size=$(du -sh "${ANDROID_ASSETS}" | cut -f1)
    print_msg "Android assets size: ${size}"
}

# Copy to iOS resources
copy_to_ios() {
    print_msg "Copying to iOS resources..."
    
    # Create directory
    mkdir -p "${IOS_RESOURCES}"
    
    # Clean previous resources
    rm -rf "${IOS_RESOURCES}"/*
    
    # Copy dist files
    cp -r "${DIST_DIR}"/* "${IOS_RESOURCES}/"
    
    # Get size
    local size=$(du -sh "${IOS_RESOURCES}" | cut -f1)
    print_msg "iOS resources size: ${size}"
}

# Optimize assets for mobile
optimize_for_mobile() {
    print_msg "Optimizing assets for mobile..."
    
    # Compress images
    if command -v optipng &> /dev/null; then
        find "${DIST_DIR}" -name "*.png" -exec optipng -o2 {} \;
    fi
    
    if command -v jpegoptim &> /dev/null; then
        find "${DIST_DIR}" -name "*.jpg" -exec jpegoptim --max=80 {} \;
    fi
    
    # Remove source maps
    find "${DIST_DIR}" -name "*.map" -delete
    
    # Remove unnecessary files
    find "${DIST_DIR}" -name ".DS_Store" -delete
    find "${DIST_DIR}" -name "Thumbs.db" -delete
    
    print_msg "Optimization completed"
}

# Generate asset manifest
generate_manifest() {
    print_msg "Generating asset manifest..."
    
    local manifest_file="${DIST_DIR}/manifest.json"
    
    cat > "${manifest_file}" << EOF
{
  "version": "$(date +%s)",
  "build_time": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "files": [
EOF
    
    local first=true
    find "${DIST_DIR}" -type f -not -name "manifest.json" | sort | while read file; do
        local relative_path="${file#${DIST_DIR}/}"
        local file_size=$(stat -f%z "$file" 2>/dev/null || stat -c%s "$file" 2>/dev/null)
        local file_hash=$(md5sum "$file" 2>/dev/null | cut -d' ' -f1 || md5 -q "$file" 2>/dev/null)
        
        if [ "$first" = true ]; then
            first=false
        else
            echo "," >> "${manifest_file}"
        fi
        
        cat >> "${manifest_file}" << EOF
    {
      "path": "${relative_path}",
      "size": ${file_size},
      "hash": "${file_hash}"
    }
EOF
    done
    
    cat >> "${manifest_file}" << EOF
  ]
}
EOF
    
    print_msg "Manifest generated"
}

# Show help
show_help() {
    echo "Frontend Build Script for Mobile Platforms"
    echo ""
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  all        Build and copy to both Android and iOS"
    echo "  android    Build and copy to Android only"
    echo "  ios        Build and copy to iOS only"
    echo "  build      Build frontend only"
    echo "  optimize   Optimize existing build"
    echo "  clean      Clean build artifacts"
    echo "  help       Show this help message"
}

# Main function
main() {
    local command="${1:-help}"
    
    case "${command}" in
        all)
            build_frontend
            optimize_for_mobile
            generate_manifest
            copy_to_android
            copy_to_ios
            print_msg "All builds completed!"
            ;;
        android)
            build_frontend
            optimize_for_mobile
            generate_manifest
            copy_to_android
            print_msg "Android build completed!"
            ;;
        ios)
            build_frontend
            optimize_for_mobile
            generate_manifest
            copy_to_ios
            print_msg "iOS build completed!"
            ;;
        build)
            build_frontend
            ;;
        optimize)
            optimize_for_mobile
            generate_manifest
            ;;
        clean)
            print_msg "Cleaning build artifacts..."
            rm -rf "${DIST_DIR}"
            rm -rf "${ANDROID_ASSETS}"/*
            rm -rf "${IOS_RESOURCES}"/*
            print_msg "Clean completed"
            ;;
        help|*)
            show_help
            ;;
    esac
}

# Run main function
main "$@"
