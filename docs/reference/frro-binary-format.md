# Fabricated overlay (.frro) on-disk format and the PNG problem

Source of truth: AOSP `frameworks/base/cmds/idmap2/libidmap2/FabricatedOverlay.cpp`
and `cmds/idmap2/idmap2d/Idmap2Service.cpp` (refs/heads/main, read 2026-11-01).
Everything below is taken from that code, not inferred from samples.

## Why PNGs cannot be committed through OverlayManager on One UI 9

`OverlayManagerServiceImpl.registerFabricatedOverlay` forwards the
`FabricatedOverlayInternal` parcel (including the `binaryData`
ParcelFileDescriptors) to idmap2d over Binder. On the SM-S948U1 the call fails
immediately with `DeadObjectException: Transaction failed on small parcel` while
idmap2d (same pid) stays alive and the next colour-only commit succeeds
(`docs/device/oneui9-sm-s948u1/idmap-log-20261101.txt`). That is Binder
`FAILED_TRANSACTION`, which the kernel returns when the SELinux fd-transfer
check (`fd use` + file `read` for the receiving domain) denies the descriptor.

AOSP `system/sepolicy/private/idmap.te` grants idmap only:
`resourcecache_data_file` (rw), `apk_data_file`, `apk_tmp_file`,
`apk_private_tmp_file`, `vendor_app_file`, `vendor_overlay_file` reads, and
binder use. There is no `fd use` rule for app domains, shell or su, so a PNG
descriptor opened by the app (`untrusted_app`, `app_data_file`) or by the root
Shizuku service (`su`, `shell_data_file`) is always rejected. Both attempts in
the build log failed for this reason.

## What idmap2d would have written

```
u32 magic            0x4f525246 ("FRRO")
u32 version          3
u32 crc              crc32(le32(version) ++ proto_bytes)
u32 total_binary_bytes
[total_binary_bytes]  concatenated raw file payloads, in entry order
u32 sp_size
[sp_size]            android::StringPool::FlattenUtf8 (ResStringPool, UTF-8)
[rest]               pb::FabricatedOverlay, deterministic serialization
```

`FRRO_HEADER_SIZE = 16`, so the first payload starts at file offset 16.

Proto (`frameworks/base/cmds/idmap2/libidmap2/proto/fabricated_v1.proto`):

```
FabricatedOverlay { string package_name=1; string name=2;
                    string target_package_name=3; string target_overlayable=4;
                    repeated Package packages=5; }
Package  { string name=1; repeated Type types=2; }
Type     { string name=1; repeated Entry entries=2; }
Entry    { string name=1; ResourceValue res_value=2; string configuration=3; }
ResourceValue { uint32 data_type=1; uint32 data_value=2; }
```

Ordering: packages, types and entries are emitted from `std::map`s keyed by
name, configurations sorted within an entry (byte-wise string order).

### Colour entry
`data_type = TYPE_INT_COLOR_ARGB8 (0x1c)`, `data_value = AARRGGBB`.

### String entry
`data_type = TYPE_STRING (0x03)`, `data_value = index into the string pool`.

### Binary (PNG) entry
Also `data_type = TYPE_STRING`; the pool string is a URI that points back into
the same file:

```
frro:/<absolute frro path>?offset=<16 + bytes of earlier payloads>&size=<payload bytes>
```

The URI embeds the final absolute path of the .frro, so a file written by hand
must be written to exactly that path (or renamed onto it). The target app's
AssetManager opens `frro:/` URIs as a byte range of that file and decodes the
PNG like any other bitmap drawable; the URI does not end in `.xml`, so it is
never treated as a compiled XML drawable.

### Nine-patch
When `isNinePatch` is set, idmap2d decodes the `.9.png`, strips the 1-pixel
border, and re-encodes a PNG with the `npTc` chunk (aapt2 logic in
`androidfw/Png.cpp`). A hand-written .frro must do the same before embedding;
until then nine-patch entries are reported as unsupported.

## Loading and verification

* `FabricatedOverlay::FromBinaryStream` accepts version 1..3 and, for version 3,
  trusts the CRC read from disk (`crc_from_disk`). Still write the correct CRC.
* `IdmapHeader::IsUpToDate` compares the idmap's recorded overlay CRC with the
  frro's CRC; a mismatch makes OverlayManager recreate the idmap on the next
  `setEnabled` ("create idmap for <target> and <owner>" in logcat).
* `Idmap2Service::createIdmap` unlinks and rewrites the idmap; existing mmaps of
  the old file stay valid.
* `createFabricatedOverlay` names files `<owner>-<name>-<4 random chars>.frro`
  in `/data/resource-cache` and never reuses a path, so re-registering moves
  the overlay to a new path. Locate the current path from
  `cmd overlay dump <id>` (`baseCodePath`) or the newest matching file.

## Root rewrite procedure (Hex Next)

1. Register `<owner>:<name>` through OverlayManager with the colours only
   (works on One UI 9).
2. Find its `.frro` path. Record owner, mode and SELinux label of the existing
   file (expected `root:root 0644 u:object_r:resourcecache_data_file:s0`).
3. Build the full file with `FrroWriter`: colours plus PNG payloads with
   `frro:/<path>?offset=&size=` strings, correct CRC.
4. Write to `<path>.hexnext.tmp` in the same directory, chmod/chown/chcon to
   match, `rename` over `<path>`.
5. Disable then enable the overlay. OverlayManager regenerates the idmap from
   the new content; this also raises the overlay above Samsung's SemWT.
6. Verify: idmap file exists and parses (no `failed to parse idmap header` for
   our path), `cmd overlay lookup <target> <drawable>` returns a `frro:/` value,
   and `FrroReader` round-trips the file.
