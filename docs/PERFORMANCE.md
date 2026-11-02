# Performance pass (app)

What changed in the Kotlin app for speed and memory, and why. Behaviour is unchanged: the same
PNGs reach the device, the same preview appears, the same files are written. Only the amount of
work done to get there is smaller.

## Preview path (`theme/ThemePreviewer.kt`, `theme/SnapshotBuilder.kt`)

| Before | After | Why |
| --- | --- | --- |
| Every preview rebuilt the device snapshot from disk: all 19 target `resources.arsc` tables were memory-mapped and parsed, then 17 were thrown away. | `SnapshotBuilder.build(only = …)` reads only the two preview targets (Settings, My Files); the result is identical to the full snapshot filtered to them. | Reading two tables instead of nineteen is the single largest saving per keystroke in the editor. |
| The snapshot was rebuilt on every call. | The two-target snapshot (and the full one `slots()` needs) is cached behind a `SoftReference`, keyed by `SnapshotBuilder.scanStamp()` (report path, size, mtime, shades mtime). A snapshot that fell back to the live system palette is never cached. | A new scan writes a new report file, so the key changes and the cache refreshes itself; the soft reference lets the GC reclaim it under pressure. |
| A new `DirectoryPackResolver` per render. The engine's resolver opens and parses every pack zip on its first lookup. | One resolver per pack-directory listing (names, sizes, mtimes). | Pack zips are opened once per pack change, not once per preview. |
| Every icon was rendered and decoded again. | Rendered bitmaps are kept in a 24 MB `LruCache` keyed by the `PlannedDrawableJob` (a data class, so equal plans hash equally) plus the custom file's size and mtime; the cache is emptied when the pack listing changes. Failures are not cached. | Changing the seed colour re-renders nothing but tinted layers; pack icons come straight from the cache. |
| Two overlapping renders could run at once (the ViewModel cancels the coroutine, not the blocking call). | `render()` and `slots()` are serialised on one lock. | Bitmaps, zip handles and the resolver's caches are not thread safe; parallelism of one is deliberate. |

The ViewModel already debounces spec changes (400 ms), cancels the waiting job and defers renders while an apply is busy; no change was needed there. Lowering `PREVIEW_DEBOUNCE_MS` to 300 ms is a one-line option in `MainViewModel`.

## Apply path (`theme/DrawableRenderer.kt`)

- Layers of a composite are decoded straight from the zip `InputStream` (`BitmapFactory.decodeStream`) instead of being copied into a `ByteArray` first; where the bytes are shipped as they are, the array is sized from the zip entry header and filled with one `readFully`.
- A custom image is measured with `inJustDecodeBounds` first. If it ships unchanged (already the slot size, or a background within 2400 px) it is never decoded. If it must shrink, it is decoded with the largest power-of-two `inSampleSize` that still leaves it at least the output size, so a 4096 px import no longer becomes a 64 MB ARGB bitmap on its way to a 138 px icon. The output dimensions are computed exactly as before; only the resampling path differs (subsample, then one bilinear scale), which can shift individual pixel values by rounding.
- The synthesis reference icon is composited to a bitmap directly instead of being PNG-encoded and decoded again. Jobs that need the exact device bytes (9-patch, custom, nested) still go through `render()`.
- One `Paint` per purpose and one PNG output buffer per renderer, reset per job (dropped when a background grew it past 4 MB), instead of a new `Paint` per layer and a new `ByteArrayOutputStream` per encode.
- Already in place and kept: one renderer per apply, one `ZipFile` handle per pack for the renderer's lifetime, every intermediate bitmap recycled in `finally`.

## Icon synthesis (`theme/IconSynthesizer.kt`)

The per-pixel analysis collected ring and glyph colours in `ArrayList<Int>`, boxing ~20 000 ints per synthesised icon. A small `IntList` over an `IntArray` replaces it; the median computation is unchanged.

## Listings (`theme/PackStore.kt`, `theme/CustomAssetStore.kt`)

`installed()` and `list()` run after every import, delete, download and catalogue fetch. Both now remember their per-file result keyed by path, size and mtime, so a relisting opens only files that changed instead of re-parsing every `pack.json` and re-reading every image header. Stale keys are dropped on each listing.

## Device scan (`device/DeviceScanner.kt`)

`.frro` candidates (up to 16 MB each) were read whole into memory to check the first 64 KB for a target name, then written out. The copy is now streamed: the header is read and checked, then the rest goes to the file in 64 KB chunks. The keep/skip/root-fallback decisions are the same as before.

## Release APK (`android/app/build.gradle.kts`, `android/app/proguard-rules.pro`)

- Release: `isMinifyEnabled` and `isShrinkResources` stay on; debug now says `false` for both explicitly, so a debug build is never obfuscated.
- Keep rules added for kotlinx.serialization (companion `serializer()` lookups and `$$serializer` classes) and the engine's `@Serializable` models, which are read back from `theme.json`, `apply-last.json` and the bundled One UI rules. The AIDL surface (`IThemeService`, its `Stub`/`Proxy`, `ThemeService`), Shizuku, HiddenApiBypass and the Material colour utilities were already kept.
- `bin/verify-release-apk.py` checks the manifest, `classes.dex`, ABIs, SDK levels, locales, alignment and signatures; none of these depend on minification, so it was left unchanged. Signing is untouched.

## Still on the main thread (outside this pass's file set)

- `CompatibilityCheck.versionLine()` / `oneUiName()` may spawn `getprop` when `android.os.SystemProperties` reflection fails; `MainViewModel.initialState()` calls them during construction. Moving the first call onto `Dispatchers.IO` (as `loadDeviceInfo()` already does) would remove the last potential disk/process I/O from startup.
