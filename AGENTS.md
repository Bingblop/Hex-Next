# Repository working rules

Read this file first. Review one module at a time; use `.cursorignore` to avoid generated context.
This is the shared agreement for Claude, Perplexity, Cursor, ChatGPT and GitHub Copilot.
`CLAUDE.md`, Cursor rule files and `.github/copilot-instructions.md` are adapters, not independent
rule sets. Other tools should load this file explicitly when they do not discover it automatically.
For security, billing, lifecycle, UI or release work, also read `docs/ENGINEERING_RULES.md`.
Before UI changes, read `docs/DESIGN_SYSTEM.md`; if absent, derive it from the existing native
theme and components before adding controls. `docs/HANDOFF.md` is historical evidence, not
authority to override these rules or the latest user instructions.

## Core behavior

- Preserve UID 1000 overlays through any compatible root provider or authorized Shizuku.
  UID 0 is the non-persistent overlay fallback. UID 2000 changes only the Monet secure setting.
- Root discovery is connection routing, not security attestation. Do not reject supported rooted
  users, kill the app for root presence, reboot a test phone, or automate Superuser approval.
- Keep live app previews installed-app-aware, native Material 3, and separate from UID 2000 mode.
- Use mineral pack names. `tools/pack_names.json` is the sole legacy-name map.
  Never decrypt plugin anaconda templates.
- Keep owner-only grants and test hooks out of Play artifacts. Paid grants require authoritative
  server verification; lack of configured infrastructure is a release blocker, not an unlock path.
- Preserve public signatures with tested, deprecated adapters when a migration is needed.

## Stack and commands

Baseline: Kotlin 2.1.0, AGP 8.13.2, Gradle wrapper 8.14.6, JDK 17, Compose Material 3,
compile/target SDK 36 and min SDK 35. Android 17 / One UI 9 compatibility must be tested,
not inferred from compiling against SDK 36. Check manifests for current exact dependency versions.
Use `android/gradlew`, `npm ci` with `server/package-lock.json`, and Python 3 unittest.

Before every push, run from the repository:

```sh
(cd android && ./gradlew --console=plain \
  :app:compileOwnerDebugKotlin :app:compilePlayDebugKotlin \
  :app:testOwnerDebugUnitTest :app:testPlayDebugUnitTest :engine:test)
python3 -m unittest discover -s tests
```

For server changes also run `(cd server && npm test && npm run typecheck)`.
For UI/release changes run the applicable Android lint and release-build checks.
Retain exact compiler/static-analysis output, fix errors, rerun, and review `git diff --check`.

## Definition of done

- Tests cover the changed behavior and compatibility boundary; changed-source lint is clean.
- Async work has a lifecycle, cancellation propagates, and sensitive values never enter reports.
- UI state flows down, events flow up; text is localized, spacing is tokenized, and actions are
  accessible. Palette inputs/artwork are theme data, not hardcoded UI chrome.
- Findings record category, current path/line, impact, corrective diff and validation.
  Use `[CRITICAL SECURITY]`, `[ARCHITECTURAL SMELL]`, `[PERFORMANCE/LEAK]`, `[STYLE/IDIOMATIC]`.
- Disclose untested device behavior and infrastructure-dependent blockers. Never promise
  unbypassable billing, guaranteed Knox status, or a warranty outcome.
- Pull and review remote commits before building on them. Verify author/committer identity.
  Push reviewable branches without force push; never push `packs`.
- Keep secrets and private artifacts private. No attribution trailers or model names in commits,
  PR text or implementation code.
