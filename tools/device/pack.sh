#!/system/bin/sh
# Hex Next: put a theme pack zip into the app's pack folder (root required).
# The app can also import a pack with "Import pack file"; this is the Termux shortcut.
#
# Usage (Termux):  sh pack.sh /sdcard/Download/iolite.zip
#                  sh pack.sh https://example.com/packs/iolite.zip
set -u
SRC="${1:-}"
PKG=com.hexnext.themer
DEST=/data/data/$PKG/files/packs
[ -n "$SRC" ] || { echo "usage: sh pack.sh <pack.zip or URL>"; exit 1; }
WORK="${HOME:-$PWD}/hexnext-packs"; mkdir -p "$WORK" || { echo "cannot create $WORK"; exit 1; }
case "$SRC" in
  http://*|https://*)
    FILE="$WORK/$(basename "$SRC")"
    echo "Downloading $SRC ..."
    curl -fL --retry 3 -o "$FILE" "$SRC" || { echo "download failed"; exit 1; }
    ;;
  *) FILE="$SRC" ;;
esac
[ -f "$FILE" ] || { echo "not a file: $FILE"; exit 1; }
# A pack is a zip holding pack.json at its root. It is stored as <id>.zip whatever the source file
# is called, as the app's own import does: the app deletes and replaces a pack by that name only,
# so a pack stored as iolite-1.zip could never be deleted and would be listed twice.
command -v unzip >/dev/null 2>&1 || { echo "unzip is needed to read the pack id (Termux: pkg install unzip)"; exit 1; }
unzip -l "$FILE" 2>/dev/null | grep -q ' pack.json$' || { echo "$FILE has no pack.json; not a Hex Next pack"; exit 2; }
ID=$(unzip -p "$FILE" pack.json 2>/dev/null | tr ',{}' '\n\n\n' \
  | sed -n 's/^[[:space:]]*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
# The id and path are interpolated into a root shell: allow only plain file-name characters,
# and refuse a source path with a single quote (it would end the quoted expansion).
case "$ID" in
  *[!A-Za-z0-9._-]*|''|.*) echo "pack id '$ID' in pack.json is missing or not a plain name; not a Hex Next pack"; exit 2 ;;
esac
NAME="$ID.zip"
case "$FILE" in
  *"'"*) echo "the path '$FILE' contains a quote; move the file to a plain path first"; exit 1 ;;
esac
# Stage inside the app's private pack folder, not a shell-writable /data/local/tmp path,
# so a planted symlink cannot redirect the root copy. The staging name is the app's own
# <id>.zip.part: a copy that fails part-way is removed here, and the app sweeps any leftover
# .part at start-up.
PART="$DEST/$NAME.part"
su -c "uid=\$(stat -c %u '/data/data/$PKG') && mkdir -p '$DEST' && \
  chown \"\$uid:\$uid\" '/data/data/$PKG/files' '$DEST' && \
  rm -f '$PART' && { cp '$FILE' '$PART' || { rm -f '$PART'; false; }; } && mv -f '$PART' '$DEST/$NAME' && rm -f '$DEST/$ID.hexnext' && \
  chown \"\$uid:\$uid\" '$DEST/$NAME' && chmod 600 '$DEST/$NAME' && \
  restorecon -R '/data/data/$PKG/files' && ls -l '$DEST'"
status=$?
[ "$status" -eq 0 ] || { echo "Pack installation failed (exit $status)." >&2; exit "$status"; }
echo "Done. Open Hex Next: the pack is listed under Theme pack; pick it and tap Apply theme."
