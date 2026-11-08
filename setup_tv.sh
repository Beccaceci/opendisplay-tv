#!/bin/bash
# ==============================================================================
# OpenDisplay TV: Automated Build, Install & Setup Script
# ==============================================================================
# Automates the end-to-end setup:
#   1. Connects to the Android TV via ADB Wi-Fi
#   2. Builds & deploys the Android TV Receiver app
#   3. Wakes up the TV and launches the receiver
#   4. Builds & signs the macOS Sender app
#   5. Launches the macOS Sender app to start extending your display with audio
# ==============================================================================

set -e

# Styling
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

info()  { echo -e "${CYAN}[INFO]${NC} $1"; }
ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo -e "${GREEN}=====================================================${NC}"
echo -e "${GREEN}   OpenDisplay TV — Automated Setup & Launch   ${NC}"
echo -e "${GREEN}=====================================================${NC}"

# 1. Detect ADB
if command -v adb &>/dev/null; then
    ADB_BIN="$(command -v adb)"
elif [[ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]]; then
    ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
else
    err "adb could not be found. Please install Android Platform Tools."
fi
ok "Found ADB: $ADB_BIN"

# 2. Detect ANDROID_HOME
if [[ -z "$ANDROID_HOME" ]]; then
    if [[ -d "$HOME/Library/Android/sdk" ]]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    else
        err "ANDROID_HOME is not set and $HOME/Library/Android/sdk does not exist."
    fi
fi
ok "ANDROID_HOME: $ANDROID_HOME"

# 3. Connect to TV over ADB Wi-Fi
TV_IP="${1:-192.168.1.87}"
info "Checking ADB connection to TV at $TV_IP:5555..."

if ! "$ADB_BIN" devices | grep -q "$TV_IP:5555"; then
    info "Connecting to $TV_IP:5555..."
    "$ADB_BIN" connect "$TV_IP:5555" || warn "Could not connect to $TV_IP:5555. Continuing if already connected via USB/another IP."
fi

# 4. Build and Deploy Android APK
APK_PATH="Android/app/build/outputs/apk/debug/app-debug.apk"
REBUILD="${REBUILD:-0}"

if [[ ! -f "$APK_PATH" || "$REBUILD" == "1" ]]; then
    info "Building Android TV receiver app..."
    cd "$ROOT_DIR/Android"
    ./gradlew assembleDebug || err "Android build failed."
    cd "$ROOT_DIR"
    ok "Android APK built successfully."
else
    ok "Android APK already built ($APK_PATH)."
fi

info "Installing app on Android TV..."
"$ADB_BIN" install -r "$APK_PATH" || err "Failed to install APK onto TV."
ok "Android TV app installed."

info "Waking up TV screen..."
"$ADB_BIN" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
sleep 1

info "Starting OpenDisplay on TV..."
"$ADB_BIN" shell am start -n com.peetzweg.opendisplay.debug/app.opendisplay.android.ui.MainActivity || err "Failed to launch app on TV."
ok "OpenDisplay is running on the TV."

# 5. Build and Sign macOS App
MAC_APP="build/Build/Products/Debug/OpenDisplay Dev.app"
if [[ ! -d "$MAC_APP" || "$REBUILD" == "1" ]]; then
    info "Building macOS Sender app..."
    xcodebuild -scheme OpenSidecarMac -configuration Debug -derivedDataPath build CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO build || err "macOS build failed."
    ok "macOS app built successfully."
else
    ok "macOS app already built."
fi

info "Checking code signature..."
# Detect Apple Development identity
DEV_IDENTITY=$(security find-identity -v -p codesigning 2>/dev/null | grep "Apple Development:" | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/' || true)

if [[ -n "$DEV_IDENTITY" ]]; then
    info "Signing with Apple Development certificate: $DEV_IDENTITY"
    codesign --force --deep --sign "$DEV_IDENTITY" "$MAC_APP"
else
    info "No Apple Development identity found. Signing with stable ad-hoc requirement..."
    STABLE_REQUIREMENT='designated => identifier "com.peetzweg.opensidecar.mac.debug"'
    codesign --force --deep --sign - \
        --requirements "=$STABLE_REQUIREMENT" \
        --entitlements Mac/OpenSidecarMac.entitlements \
        "$MAC_APP"
fi
codesign --verify --deep --strict "$MAC_APP"
ok "macOS app signature verified."

# 6. Launch macOS App
info "Launching OpenDisplay on Mac..."
# Close any existing instance
killall "OpenDisplay Dev" 2>/dev/null || true
sleep 1
open "$MAC_APP"

ok "All done! OpenDisplay is running."
echo -e "${GREEN}=====================================================${NC}"
echo -e "Your Mac is now extending display & streaming audio to your TV!"
echo -e "Logs available at: ~/Library/Logs/OpenDisplay Dev/opendisplay.log"
echo -e "${GREEN}=====================================================${NC}"
