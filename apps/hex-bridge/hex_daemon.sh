#!/data/data/com.termux/files/usr/bin/bash
# HexBridge Pro - Background Overlay Enforcer Daemon
# Periodically re-enables Hex/ThemePark overlays and maintains Theme Store freeze

PID_FILE="/data/data/com.termux/files/home/hex-bridge/daemon.pid"
LOG_FILE="/data/data/com.termux/files/home/hex-bridge/daemon.log"

# Save the PID of the current daemon process
echo $$ > "$PID_FILE"

echo "[$(date)] Daemon started (PID: $$)" > "$LOG_FILE"

# Clean up handler for exits (SIGTERM, SIGINT)
cleanup() {
    echo "[$(date)] Daemon stopping..." >> "$LOG_FILE"
    rm -f "$PID_FILE"
    exit 0
}
trap cleanup SIGTERM SIGINT EXIT

# Helper to run rish commands safely
run_rish() {
    /data/data/com.termux/files/usr/bin/rish -c "$1"
}

while true; do
    # 1. Fetch current overlays
    OVERLAY_LIST=$(run_rish "cmd overlay list")
    
    # Check if we succeeded in talking to Shizuku
    if [ -z "$OVERLAY_LIST" ]; then
        echo "[$(date)] Warning: Shizuku bridge unresponsive. Retrying..." >> "$LOG_FILE"
        sleep 15
        continue
    fi
    
    # 2. Iterate through and force-enable any custom RROs that are disabled
    CURRENT_TARGET=""
    echo "$OVERLAY_LIST" | while read -r line; do
        line=$(echo "$line" | xargs) # trim whitespace
        if [ -z "$line" ]; then
            continue
        fi
        
        # Check if line is overlay entry or target package name
        if [[ "$line" == "[ ]"* ]] || [[ "$line" == "[x]"* ]] || [[ "$line" == "---"* ]]; then
            STATUS="disabled"
            if [[ "$line" == "[x]"* ]]; then STATUS="enabled"; fi
            if [[ "$line" == "---"* ]]; then STATUS="static"; fi
            
            # Extract package name (remove bracket status)
            PKG="${line:4}"
            PKG=$(echo "$PKG" | xargs) # clean
            
            # Identify if it is a custom theme overlay (Hex or ThemePark)
            IS_HEX=0
            IS_THEMEPARK=0
            if [[ "$PKG" == *"hex"* ]] || [[ "$PKG" == *"vivid"* ]]; then IS_HEX=1; fi
            if [[ "$PKG" == *"themepark"* ]] || [[ "$PKG" == "ThemePark"* ]]; then IS_THEMEPARK=1; fi
            
            # If it is a Hex or ThemePark overlay and it's disabled, force enable it!
            if [ "$STATUS" = "disabled" ]; then
                if [ $IS_HEX -eq 1 ] || [ $IS_THEMEPARK -eq 1 ]; then
                    echo "[$(date)] Enforcing overlay: Enabling $PKG" >> "$LOG_FILE"
                    run_rish "cmd overlay enable $PKG" >/dev/null 2>&1
                fi
            fi
        else
            CURRENT_TARGET="$line"
        fi
    done
    
    # 3. Ensure Theme Store and Theme Center remain frozen (disabled) to bypass the trial limit
    # Query if Theme Store is enabled. If it outputs 'package:com.samsung.android.themestore', it's enabled.
    THEMESTORE_STATUS=$(run_rish "pm list packages -e | grep com.samsung.android.themestore")
    if [ -n "$THEMESTORE_STATUS" ]; then
        echo "[$(date)] Warning: Theme Store detected as active. Freezing to prevent 10-min trial reset." >> "$LOG_FILE"
        run_rish "pm disable-user --user 0 com.samsung.android.themestore" >/dev/null 2>&1
        run_rish "pm disable-user --user 0 com.samsung.android.themecenter" >/dev/null 2>&1
    fi
    
    sleep 15
done
