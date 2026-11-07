#!/usr/bin/env bash
# Downloads Kyle's legacy plugin + Hex Installer export (private repo Bingblop/hexnext-plugin-sources)
# and unpacks every decompiled APK to <dir>/src/<package>/ (resources/, sources/). About 2.5 GB free
# space needed. See docs/plugin-sources.md.
#   tools/fetch_plugin_sources.sh [<dir>]      (default: ../hexplugins)
set -euo pipefail
dir=${1:-../hexplugins}
mkdir -p "$dir"; dir=$(cd "$dir" && pwd)
repo="$dir/hexnext-plugin-sources"
if [ -d "$repo/.git" ]; then git -C "$repo" pull -q
else gh repo clone Bingblop/hexnext-plugin-sources "$repo" -- -q --depth 1; fi
zip="$dir/Theme_Packs_Decrypted.zip"
cat "$repo"/dump/Theme_Packs_Decrypted.zip.part* > "$zip"
(cd "$dir" && sha256sum -c "$repo/dump/SHA256SUMS")
tmp=$(mktemp -d "$dir/.raw.XXXX"); trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$zip" 'Theme_Packs_Decrypted/*/base.apk_Decompiler.com*.zip' 'Theme_Packs_Decrypted/*/app.metadata' -d "$tmp"
for d in "$tmp"/Theme_Packs_Decrypted/*/; do
  pkg=$(basename "$d"); pkg=${pkg%%-*}
  inner=$(ls "$d"/base.apk_Decompiler.com*.zip | head -1)
  mkdir -p "$dir/src/$pkg"; unzip -q -o "$inner" -d "$dir/src/$pkg"
  cp "$d/app.metadata" "$dir/src/$pkg/" 2>/dev/null || true
done
echo "unpacked $(ls "$dir/src" | wc -l) packages to $dir/src"
