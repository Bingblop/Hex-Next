#!/usr/bin/env python3
import argparse
import json
import os
import sys
from .fabricated import FabricatedOverlayBuilder
from .oneui9_overlays import ONEUI9_SYSTEM_RRO_CATALOG, resolve_overlay_tokens
from .shizuku_bridge import ShizukuBridge
from .system_properties import SamsungSystemProperties


def print_header():
    print("\033[1;36m======================================================\033[0m")
    print("\033[1;32m   HexNext — One UI 9.0 System RRO Overlay Engine     \033[0m")
    print("\033[1;36m======================================================\033[0m\n")


def cmd_status(args):
    print_header()
    bridge = ShizukuBridge()
    print(f"Privilege Bridge    : \033[1;33m{bridge.mode} (UID {bridge.uid})\033[0m")
    print(f"RRO Owning Package  : \033[1;32m{bridge.owner_package}\033[0m")
    print("Theme Park Required : \033[1;32mNo (Direct Samsung System RRO Stack)\033[0m")
    print(f"Packed One UI 9 RROs: \033[1;36m{len(ONEUI9_SYSTEM_RRO_CATALOG)} System Targets Ready\033[0m")


def cmd_build_oneui9(args):
    print_header()
    bridge = ShizukuBridge()
    if not bridge.is_privileged:
        print(f"\033[1;31m[!] Error: Shizuku (UID 0 or UID 1000) or SU access required (current UID={bridge.uid}).\033[0m")
        sys.exit(1)

    spec = {
        "accent": args.accent,
        "secondary": args.secondary,
        "pure_black": not args.no_pure_black,
        "style": args.style,
        "privilege_uid": bridge.uid,
    }
    packages = resolve_overlay_tokens(spec)
    for pkg in packages:
        if not pkg["selected"]:
            continue
        print(f"[*] Building One UI 9.0 System RRO: {pkg['system_rro_package']} -> {pkg['target_package']}")
        builder = FabricatedOverlayBuilder(
            pkg["target_package"],
            pkg["fabricated_name"],
            bridge=bridge,
            rro_carrier=pkg["system_rro_package"],
        )
        for res in pkg["resolved_resources"]:
            builder.add_color(res["name"], res["value"])
        builder.build_and_apply()

    print("\n\033[1;32m[✓] One UI 9.0 System RRO overlays built and enabled via UID "
          f"{bridge.uid} (Zero Theme Park dependencies)!\033[0m")


def cmd_apply_theme(args):
    print_header()
    theme_file = args.file
    if not os.path.exists(theme_file):
        print(f"\033[1;31mError: Theme file {theme_file} not found.\033[0m")
        sys.exit(1)

    with open(theme_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    name = data.get("name", "CustomTheme")
    bridge = ShizukuBridge()
    print(f"[*] Applying Theme: \033[1;32m{name}\033[0m via UID {bridge.uid} ({bridge.mode})")

    overlays = data.get("overlays", {})
    for target_pkg, values in overlays.items():
        overlay_name = f"hex_oneui9_{name.lower()}_{target_pkg.replace('.', '_')}"
        print(f"  -> Fabricating One UI 9.0 system RRO for {target_pkg}...")
        builder = FabricatedOverlayBuilder(target_pkg, overlay_name, bridge)
        for k, v in values.get("colors", {}).items():
            builder.add_color(k, v)
        for k, v in values.get("booleans", {}).items():
            builder.add_bool(k, v)
        for k, v in values.get("integers", {}).items():
            builder.add_integer(k, v)
        builder.build_and_apply()

    print("\n\033[1;32m[✓] Theme applied directly onto Samsung System RRO stack!\033[0m")


def main():
    parser = argparse.ArgumentParser(description="One UI 9.0 System RRO Theming Engine")
    subparsers = parser.add_subparsers(dest="command")

    subparsers.add_parser("status", help="Inspect UID 0 / UID 1000 privilege and System RRO status")

    build_p = subparsers.add_parser("build", help="Build and apply all packed One UI 9.0 System RROs")
    build_p.add_argument("--accent", default="#00E5FF", help="Seed accent hex color")
    build_p.add_argument("--secondary", default="#7C4DFF", help="Secondary accent hex color")
    build_p.add_argument("--style", default="VIBRANT", help="Monet palette style")
    build_p.add_argument("--no-pure-black", action="store_true", help="Disable AMOLED pure black")

    apply_p = subparsers.add_parser("apply", help="Apply a theme json profile")
    apply_p.add_argument("file", help="Path to theme.json")

    args = parser.parse_args()
    if args.command == "status":
        cmd_status(args)
    elif args.command == "build":
        cmd_build_oneui9(args)
    elif args.command == "apply":
        cmd_apply_theme(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
