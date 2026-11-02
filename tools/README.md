# Tools

Helper scripts that are not part of the app build. Python scripts run with `python3` from the
repository root; device scripts run on the phone from Termux. None of them are needed to build
the APK.

## Pack tools

A Hex Next **theme pack** is a zip the app downloads or imports:

```
pack.json                     metadata, colour palette, options and layer list
layers/<pass>/<stage>/<tint>/<category>/<resource>.png
previews/<n>.<ext>            preview screenshots (optional)
drawables/<resource>.png      pack UI drawables (optional)
```

Layers are greyscale PNGs. `tint` is a palette slot (dark slot, or `l` + slot for its light twin),
a fixed `Haarrggbb` colour, or `default` (draw untinted). The app composites `base`, `first` and
`second` stages per pass into the final icon for every density. The pack model is defined in the
engine (`android/engine/src/main/kotlin/com/hexnext/engine/pack/Pack.kt`).

| Script | Purpose | Usage |
|---|---|---|
| `hex_pack_builder.py` | Convert a source plugin directory into Hex Next packs: copies only readable PNG layers, writes `pack.json`, renames every pack to its catalogue identity and writes `index.json` for the pack folder. Encrypted templates are never read. | `python3 tools/hex_pack_builder.py <plugin-dir> <out-dir>` |
| `hex_plugin_catalog.py` | Build the metadata catalogue in `docs/plugin-catalog/` (feature flags, target categories, resource names). Copies no artwork. The engine test `ElementsTest` reads `catalog.json`. | `python3 tools/hex_plugin_catalog.py <plugin-dir> <out-dir>` |
| `rename_packs.py` | Give packs built before the naming table their catalogue id, name and `com.hexnext.pack.<id>` package, and regenerate `index.json`. `hex_pack_builder.py` already applies the same table. | `python3 tools/rename_packs.py <in-dir> <out-dir>` |
| `pack_names.json` | The naming table: source id to Hex Next id, display name and package. Nothing from a source plugin's name or package is shown in the app or written into a pack. | data file |
| `make_app_icon.py` | Render the adaptive launcher icon master (1024 px) from the hex artwork. | `python3 tools/make_app_icon.py <src.png> <out.png>` |

Requirements: `pip install androguard loguru colorama` for the catalogue and pack builder,
`pip install pillow` for the icon script.

Pack hosting: the CI debug build bundles a few packs from the `packs` branch
(`android/app/src/debug/assets/packs/`, ignored in git). Release builds bundle none.

## Glyphs

`glyphs/build.js` authors the 27 original dashboard glyphs as geometry and writes
`glyphs/glyphs.svg`, a `contact-sheet.png` for visual review (needs `npm i playwright` and a
Chromium download) and the vector drawables `android/app/src/main/res/drawable/hx_glyph_*.xml`.

```bash
node tools/glyphs/build.js            # svg + contact sheet + xml
node tools/glyphs/build.js --no-xml   # svg + contact sheet only
```

## Device scripts (`device/`)

Run from Termux or `adb shell` on the test phone. Root is required except for `diag.sh`.
Each script writes its report into the current directory or the directory given as the first
argument.

| Script | Purpose |
|---|---|
| `install.sh` | Download the pinned CI debug artifact, verify its SHA-256, install with `pm install -r`. `GH_TOKEN` for a private repo; `RUN_ID` and `APK_ZIP_SHA256` to pin another run. |
| `pack.sh` | Put a pack zip (local path or URL) into the app's pack folder. |
| `diag.sh` | Read-only diagnostics: One UI version, `SemWT_*` overlays, root and Shizuku state, one sample `.frro`. |
| `collect.sh` | Collect apply reports, build logs, scan output and overlay state into `hexnext-collect.txt`. |
| `icons-probe.sh` | Find the real Settings dashboard icon names after "Run Settings test" and check whether Settings caches icons. |
| `test-overlay.sh` | Install the test build, check that a Hex Next overlay beats Samsung's Settings overlay and that Samsung does not re-enable it, collect logs, revert all overlay changes. |

Device captures used in the docs are kept under `docs/device/<oneui>-<model>/`.
