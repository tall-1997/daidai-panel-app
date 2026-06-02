#!/bin/bash

# iOS Build Script for Daidai Panel
# Builds an unsigned IPA without code signing

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_msg() {
    echo -e "${GREEN}[iOS]${NC} $1"
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
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS_DIR="${PROJECT_ROOT}/ios"
BUILD_DIR="${IOS_DIR}/build"
OUTPUT_DIR="${PROJECT_ROOT}/dist/ios"
APP_NAME="DaidaiPanel"
SCHEME="DaidaiPanel"

# Check prerequisites
check_prerequisites() {
    print_step "Checking prerequisites..."
    
    if ! command -v xcodebuild &> /dev/null; then
        print_error "Xcode command line tools not found. Please install Xcode."
        exit 1
    fi
    
    if ! command -v xcodegen &> /dev/null; then
        print_warn "xcodegen not found. Installing via Homebrew..."
        if command -v brew &> /dev/null; then
            brew install xcodegen
        else
            print_error "Homebrew not found. Please install xcodegen manually: brew install xcodegen"
            exit 1
        fi
    fi
    
    print_msg "All prerequisites found"
}

# Generate Xcode project
generate_project() {
    print_step "Generating Xcode project..."
    
    cd "${IOS_DIR}"
    
    # Generate project using xcodegen
    xcodegen generate
    
    if [ ! -d "${APP_NAME}.xcodeproj" ]; then
        print_error "Failed to generate Xcode project"
        exit 1
    fi
    
    print_msg "Xcode project generated"
}

# Clean build
clean_build() {
    print_step "Cleaning build..."
    
    rm -rf "${BUILD_DIR}"
    rm -rf "${OUTPUT_DIR}"
    mkdir -p "${OUTPUT_DIR}"
    
    print_msg "Clean completed"
}

# Build for device (unsigned)
build_app() {
    print_step "Building ${APP_NAME} for device (unsigned)..."
    
    cd "${IOS_DIR}"
    
    # Build for generic iOS device (arm64)
    xcodebuild \
        -project "${APP_NAME}.xcodeproj" \
        -scheme "${SCHEME}" \
        -configuration Release \
        -destination "generic/platform=iOS" \
        -derivedDataPath "${BUILD_DIR}/DerivedData" \
        CODE_SIGN_IDENTITY="" \
        CODE_SIGNING_REQUIRED=NO \
        CODE_SIGNING_ALLOWED=NO \
        ENABLE_BITCODE=NO \
        DEVELOPMENT_TEAM="" \
        PROVISIONING_PROFILE_SPECIFIER="" \
        build
    
    if [ $? -ne 0 ]; then
        print_error "Build failed"
        exit 1
    fi
    
    print_msg "Build completed"
}

# Create unsigned IPA
create_ipa() {
    print_step "Creating unsigned IPA..."
    
    local app_path="${BUILD_DIR}/DerivedData/Build/Products/Release-iphoneos/${APP_NAME}.app"
    
    if [ ! -d "${app_path}" ]; then
        print_error "App not found at: ${app_path}"
        exit 1
    fi
    
    # Create Payload directory
    local payload_dir="${BUILD_DIR}/Payload"
    mkdir -p "${payload_dir}"
    
    # Copy app to Payload
    cp -R "${app_path}" "${payload_dir}/${APP_NAME}.app"
    
    # Create IPA
    local ipa_path="${OUTPUT_DIR}/${APP_NAME}-unsigned.ipa"
    cd "${BUILD_DIR}"
    zip -r "${ipa_path}" Payload/
    
    if [ -f "${ipa_path}" ]; then
        local size=$(du -sh "${ipa_path}" | cut -f1)
        print_msg "IPA created: ${ipa_path} (${size})"
    else
        print_error "Failed to create IPA"
        exit 1
    fi
}

# Show summary
show_summary() {
    print_step "Build Summary"
    
    echo ""
    echo "Build artifacts:"
    echo ""
    
    local ipa_path="${OUTPUT_DIR}/${APP_NAME}-unsigned.ipa"
    if [ -f "${ipa_path}" ]; then
        local size=$(du -sh "${ipa_path}" | cut -f1)
        echo "  iOS IPA (unsigned): ${ipa_path} (${size})"
    fi
    
    echo ""
    echo "Notes:"
    echo "  - This is an unsigned IPA, it cannot be installed directly on devices"
    echo "  - To install on a device, you need to sign it with a valid provisioning profile"
    echo "  - For development testing, use Xcode to build and run on a connected device"
    echo ""
}

# Show help
show_help() {
    echo "iOS Build Script for Daidai Panel"
    echo ""
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  build      Build unsigned IPA (default)"
    echo "  clean      Clean build artifacts"
    echo "  help       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0           # Build unsigned IPA"
    echo "  $0 build     # Build unsigned IPA"
    echo "  $0 clean     # Clean build artifacts"
}

# Main function
main() {
    local command="${1:-build}"
    
    case "${command}" in
        build)
            print_msg "Starting iOS build..."
            check_prerequisites
            generate_project
            clean_build
            build_app
            create_ipa
            show_summary
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
