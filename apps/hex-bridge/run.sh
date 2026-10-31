#!/data/data/com.termux/files/usr/bin/bash
# HexBridge Pro Startup Script

APP_DIR="/data/data/com.termux/files/home/hex-bridge"
PID_FILE="$APP_DIR/daemon.pid"

echo -e "\033[1;36m===================================================\033[0m"
echo -e "\033[1;32m             Starting HexBridge Pro                \033[0m"
echo -e "\033[1;36m===================================================\033[0m"

# Ensure directories exist
mkdir -p "$APP_DIR/templates" "$APP_DIR/static"

# Clean stale daemon PID files
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ! ps -p "$PID" >/dev/null 2>&1; then
        echo -e "\033[1;33mCleaning up stale background daemon PID...\033[0m"
        rm -f "$PID_FILE"
    fi
fi

# Launch Flask Server in the user's Termux home local directory
echo -e "\033[1;34mStarting local theme engine dashboard...\033[0m"
cd "$APP_DIR" || exit 1
python3 app.py
