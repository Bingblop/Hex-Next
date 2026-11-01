#!/system/bin/sh
# Hex Next: put a theme pack zip into the app's pack folder (root required).
# The app can also import a pack with "Import pack file"; this is the Termux shortcut.
#
# Usage (Termux):  sh pack.sh /sdcard/Download/deluxe-3-8.zip
#                  sh pack.sh https://example.com/packs/deluxe-3-8.zip
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
# A pack is a zip holding pack.json at its root.
if command -v unzip >/dev/null 2>&1 && ! unzip -l "$FILE" 2>/dev/null | grep -q ' pack.json$'; then
  echo "$FILE has no pack.json; not a Hex Next pack"; exit 2
fi
NAME=$(basename "$FILE")
su -c "mkdir -p $DEST && cp '$FILE' /data/local/tmp/$NAME && mv /data/local/tmp/$NAME $DEST/$NAME && \
  uid=\$(stat -c %u /data/data/$PKG) && chown \$uid:\$uid $DEST/$NAME && chmod 600 $DEST/$NAME && \
  restorecon -R $DEST && ls -l $DEST"
echo "Done. Open Hex Next: the pack is listed under Theme pack; pick it and tap Apply theme."
