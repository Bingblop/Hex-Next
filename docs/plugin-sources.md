# Plugin source material

Kyle exported every legacy Hex plugin he owns (49) and the original Hex Installer app from his
phone on 2026-11-06 and decompiled each base APK. All agents use this as the input for pack work.

## Getting it

- `tools/fetch_plugin_sources.sh [<dir>]` (default `../hexplugins`): clones the PRIVATE repo
  `Bingblop/hexnext-plugin-sources`, joins `dump/Theme_Packs_Decrypted.zip.part*`, checks
  `SHA256SUMS`, and unpacks every decompiled APK to `<dir>/src/<package>/`.
- The same zip is in Kyle's Google Drive (file id `14RyXsVynlMkThH83IMXcx3FRjOK3Ts8N`).
- Never commit any of it to Hex-Next, the device lab or another public repo: it holds third-party
  artwork and the original plugin names, which the app never shows.

## Layout

`src/<package>/resources/` holds the text AndroidManifest.xml (`HEX_*` meta-data), `res/` and
`assets/`: `anaconda_tints/` and `common_tints/` (readable layer PNGs), `extra.preferences`
(options), `default.hex` (base64 JSON defaults and palette), `info_images/` (previews) and
`anaconda/` (encrypted templates: never decrypted, see AGENTS.md). `sources/` is decompiled Java.

## Converting

- `tools/hex_pack_builder.py <dir-of-plugins> <out>` reads APKs and decompiled folders alike
  (`tools/decompiled_plugin.py`). A rebuild of the 44 catalogue packs from this dump gives the same
  pack.json as the `packs` branch (only `drawableCount`, the plugin's own UI drawables, differs), so
  the catalogue already holds every readable asset.
- New on 2026-11-06 (`tools/pack_names.json`): glassdn → Aquamarine, kraken → Benitoite,
  kyuubi_mdr → Jadeite, projectx → Malachite, toro → Tanzanite. They were appended to
  `BundleGrant.ORDER` and `PaidPacks`.
- `project.vivid.hex.bodhi` is Hex Installer itself. `tools/make_default_pack.py` builds the free
  default pack Quartz from it: its default palette (`References.java`), the elements it tints
  itself (toast frames, edge handles, text-select handles, widget resize frame, send button,
  message masks) and its preview screenshots. Its 28 Quick panel toggle shapes
  (`assets/hex_qs_toggles*`, XML shapes coloured by slot) are not in the pack format yet.
