import os
import sys
import subprocess
import signal
from flask import Flask, render_template, jsonify, request

app = Flask(__name__)

DAEMON_PID_FILE = "/data/data/com.termux/files/home/hex-bridge/daemon.pid"
DAEMON_SCRIPT = "/data/data/com.termux/files/home/hex-bridge/hex_daemon.sh"

def run_cmd(cmd):
    """Executes a command via Shizuku rish and returns stdout, stderr, and success status."""
    try:
        res = subprocess.run(["rish", "-c", cmd], capture_output=True, text=True, timeout=5)
        return res.stdout.strip(), res.stderr.strip(), (res.returncode == 0)
    except Exception as e:
        return "", str(e), False

def get_overlays():
    """Retrieves and parses RRO overlays from cmd overlay list."""
    stdout, stderr, ok = run_cmd("cmd overlay list")
    if not ok:
        return []
    
    lines = stdout.splitlines()
    overlays = []
    current_target = None
    
    for line in lines:
        line = line.strip()
        if not line:
            continue
        if line.startswith('[x]') or line.startswith('[ ]') or line.startswith('---'):
            status = 'enabled' if line.startswith('[x]') else ('disabled' if line.startswith('[ ]') else 'static')
            pkg = line[4:].strip()
            
            is_hex = "hex" in pkg.lower() or "vivid" in pkg.lower()
            is_themepark = "themepark" in pkg.lower() or pkg.startswith("android:ThemePark") or (current_target and "themepark" in current_target.lower())
            
            # Treat as custom if it is Hex, ThemePark, or an overlay not owned by system defaults
            is_custom = is_hex or is_themepark or not (pkg.startswith("com.samsung") or pkg.startswith("com.google") or "auto_generated" in pkg)
            
            overlays.append({
                "package": pkg,
                "target": current_target,
                "status": status,
                "is_hex": is_hex,
                "is_themepark": is_themepark,
                "is_custom": is_custom
            })
        else:
            current_target = line
            
    return overlays

def get_package_states():
    """Checks whether One UI Theme Store packages are frozen (disabled) or active."""
    stdout_d, _, _ = run_cmd("pm list packages -d")
    disabled = [line.replace("package:", "").strip() for line in stdout_d.splitlines()]
    
    store_pkg = "com.samsung.android.themestore"
    center_pkg = "com.samsung.android.themecenter"
    
    return {
        "themestore": "frozen" if store_pkg in disabled else "active",
        "themecenter": "frozen" if center_pkg in disabled else "active"
    }

def get_daemon_status():
    """Checks if the background hex_daemon.sh is active."""
    if not os.path.exists(DAEMON_PID_FILE):
        return {"running": False, "pid": None}
    
    try:
        with open(DAEMON_PID_FILE, "r") as f:
            pid = int(f.read().strip())
        # Check if process is running
        os.kill(pid, 0)
        return {"running": True, "pid": pid}
    except (ValueError, ProcessLookupError, FileNotFoundError):
        # PID is stale or invalid
        if os.path.exists(DAEMON_PID_FILE):
            os.remove(DAEMON_PID_FILE)
        return {"running": False, "pid": None}

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/status', methods=['GET'])
def api_status():
    """Aggregates system info, package statuses, daemon, and overlay counts."""
    # System Doctor diagnostics
    stdout, _, _ = run_cmd("plus doctor")
    selinux = "unknown"
    for line in stdout.splitlines():
        if "SELinux" in line:
            selinux = line.split(":")[-1].strip()
            
    pkg_states = get_package_states()
    daemon = get_daemon_status()
    overlays = get_overlays()
    
    custom_overlays = [o for o in overlays if o["is_custom"]]
    enabled_custom = [o for o in custom_overlays if o["status"] == "enabled"]
    
    return jsonify({
        "ok": True,
        "selinux": selinux,
        "package_states": pkg_states,
        "daemon": daemon,
        "overlays_count": {
            "total": len(overlays),
            "custom": len(custom_overlays),
            "enabled_custom": len(enabled_custom)
        }
    })

@app.route('/api/overlays', methods=['GET'])
def api_overlays():
    """Returns list of custom RRO overlays."""
    overlays = get_overlays()
    custom_overlays = [o for o in overlays if o["is_custom"]]
    return jsonify({"ok": True, "overlays": custom_overlays})

@app.route('/api/overlays/toggle', methods=['POST'])
def api_toggle_overlay():
    """Toggles status of an individual overlay."""
    data = request.json or {}
    pkg = data.get("package")
    enable = data.get("enable", True)
    
    if not pkg:
        return jsonify({"ok": False, "error": "No package specified"})
        
    cmd = f"cmd overlay {'enable' if enable else 'disable'} {pkg}"
    _, err, ok = run_cmd(cmd)
    
    if ok:
        return jsonify({"ok": True})
    else:
        return jsonify({"ok": False, "error": err})

@app.route('/api/overlays/batch', methods=['POST'])
def api_batch_overlays():
    """Batch enables or disables custom overlays."""
    data = request.json or {}
    action = data.get("action")  # "enable_all", "disable_all"
    
    overlays = get_overlays()
    custom_overlays = [o for o in overlays if o["is_custom"]]
    
    success_count = 0
    fail_count = 0
    
    for o in custom_overlays:
        if action == "enable_all" and o["status"] != "enabled":
            _, _, ok = run_cmd(f"cmd overlay enable {o['package']}")
            if ok: success_count += 1
            else: fail_count += 1
        elif action == "disable_all" and o["status"] == "enabled":
            _, _, ok = run_cmd(f"cmd overlay disable {o['package']}")
            if ok: success_count += 1
            else: fail_count += 1
            
    return jsonify({"ok": True, "success": success_count, "failed": fail_count})

@app.route('/api/themestore/toggle', methods=['POST'])
def api_toggle_themestore():
    """Freezes or Thaws Samsung Theme Store & Center."""
    data = request.json or {}
    action = data.get("action")  # "freeze", "thaw"
    
    store_pkg = "com.samsung.android.themestore"
    center_pkg = "com.samsung.android.themecenter"
    
    if action == "freeze":
        _, _, ok1 = run_cmd(f"pm disable-user --user 0 {store_pkg}")
        _, _, ok2 = run_cmd(f"pm disable-user --user 0 {center_pkg}")
        success = ok1 or ok2
    else:
        _, _, ok1 = run_cmd(f"pm enable {store_pkg}")
        _, _, ok2 = run_cmd(f"pm enable {center_pkg}")
        success = ok1 or ok2
        
    return jsonify({"ok": success, "states": get_package_states()})

@app.route('/api/daemon/toggle', methods=['POST'])
def api_toggle_daemon():
    """Starts or stops the background overlay auto-enforce daemon."""
    data = request.json or {}
    action = data.get("action") # "start", "stop"
    
    daemon_state = get_daemon_status()
    
    if action == "start":
        if daemon_state["running"]:
            return jsonify({"ok": True, "msg": "Daemon is already running"})
            
        # Launch background daemon script
        try:
            # We run it detached so it survives the Flask server restart/lifecycle
            p = subprocess.Popen(["bash", DAEMON_SCRIPT], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, preexec_fn=os.setpgrp)
            return jsonify({"ok": True, "pid": p.pid})
        except Exception as e:
            return jsonify({"ok": False, "error": str(e)})
            
    else:
        if not daemon_state["running"]:
            return jsonify({"ok": True, "msg": "Daemon is already stopped"})
            
        pid = daemon_state["pid"]
        try:
            os.kill(pid, signal.SIGTERM)
            if os.path.exists(DAEMON_PID_FILE):
                os.remove(DAEMON_PID_FILE)
            return jsonify({"ok": True})
        except Exception as e:
            return jsonify({"ok": False, "error": str(e)})

@app.route('/api/rescue', methods=['POST'])
def api_rescue():
    """Emergency Safe Mode: Disables all custom/Hex/ThemePark overlays immediately."""
    overlays = get_overlays()
    # Batch disable EVERYTHING classified as custom, Hex, or ThemePark to recover System UI
    custom_overlays = [o for o in overlays if o["is_custom"]]
    
    disabled_count = 0
    for o in custom_overlays:
        if o["status"] == "enabled":
            run_cmd(f"cmd overlay disable {o['package']}")
            disabled_count += 1
            
    # Always restore the Theme Store so users can re-apply defaults easily
    run_cmd("pm enable com.samsung.android.themestore")
    run_cmd("pm enable com.samsung.android.themecenter")
    
    # Terminate background daemon if running
    daemon_state = get_daemon_status()
    if daemon_state["running"]:
        try: os.kill(daemon_state["pid"], signal.SIGTERM)
        except: pass
        if os.path.exists(DAEMON_PID_FILE):
            os.remove(DAEMON_PID_FILE)
            
    return jsonify({"ok": True, "disabled_count": disabled_count})

if __name__ == '__main__':
    print("\n👑 Starting HexBridge Pro on http://localhost:5000")
    print("Keep this terminal open to maintain the theme bridge daemon.")
    app.run(host='0.0.0.0', port=5000, debug=True)