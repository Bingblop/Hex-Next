# Engineering and release gates

These rules apply to repository reviews and implementation. Keep the native Kotlin/Compose app,
pure JVM engine, TypeScript Worker and Python tooling separate in the review.

## Review evidence

Inventory tracked source and configuration before sampling modules. Inspect exact dependency
manifests and lockfiles despite their default context exclusion. Keep a coverage ledger recording
what was checked, findings, fixes, commands and unverified areas. Preserve raw compiler output;
redact secrets before including logs in a shared artifact. Do not hide unresolved warnings.

Every finding uses one category: `[CRITICAL SECURITY]`, `[ARCHITECTURAL SMELL]`,
`[PERFORMANCE/LEAK]` or `[STYLE/IDIOMATIC]`. Identify the file and current line, explain the
impact, and provide the exact applied patch or a targetable proposed patch with its blocker.
Separate confirmed vulnerabilities, hardening opportunities and unverified hypotheses.

## Billing and trust boundaries

- Verify Play purchase tokens through the authenticated backend and Google Play Developer API.
  Purchase JSON, callbacks, local booleans, preferences, or obfuscation alone cannot grant access.
- Bind account, purchase, product, package, nonce and integrity request server-side. Check purchase
  state and obfuscated identity, record token ownership idempotently, and acknowledge on the server.
- Accept Play Integrity tokens as opaque client values. Backend verification must bind the actual
  request and enforce an explicit verdict policy. Requiring an unrooted device conflicts with this
  app's core purpose; use app/install/request authenticity without blindly rejecting root.
- Authenticating a time-bound server grant or verifying a signed downloaded asset is not local
  Play purchase verification. Those local signature checks remain necessary transport/integrity
  defenses; the server is still the entitlement authority.
- Owner builds are private test tools, never public production bypasses. Build gates and artifact
  checks must prove that their unlock code and test entry points are absent from Play release.
- No purchase tokens, raw receipts, signatures, credentials, account identifiers or AI keys in logs,
  analytics, crash attachments or diagnostics. Public keys and API URLs are not secrets, but
  endpoints need strict validation; R8 does not turn strings or rooted-client state into secrets.

## Environment and recovery

Root/UID discovery must remain usable. Do not add root-ban heuristics, hidden exits, ptrace
interference, or broad process-map scans that break supported theming, accessibility or recovery.
Terminate an unsafe transaction or connection, not an in-progress overlay session without cleanup.
Favor server-side integrity evidence over local heuristics and distinguish unknown from verified.

Unsigned remote configuration is never permission to expand privileges. Authenticate signed
remote policy/assets and enforce expiry, bounded parsing, allowed origins and path constraints.
Remote executable patches are outside the app's intended design. A kill switch must have an
explicit cached/offline policy and must never strand a user unable to remove their theme.

Do not add certificate pinning without a real owned endpoint, backup pins, certificate-rotation
plan and tested recovery. Force HTTPS, use normal platform trust, and prohibit release user-CA or
cleartext overrides. Treat missing pinning/attestation infrastructure as an explicit deployment
decision, not a fictional protection.

## Lifecycle and error handling

- Use lifecycle-aware collection, scoped coroutines and immutable StateFlow state. Cancel timers,
  listeners, receivers, jobs and HTTP/process resources at their ownership boundary.
- Singletons retain application context, not Activity/View instances. Track UI-bound work with
  explicit binding/unbinding or weak references.
- Rethrow coroutine cancellation. Catch known recoverable failures; unexpected errors go to a
  redacted diagnostic boundary. Do not swallow errors or send user data to a new telemetry service.
- Prefer structured, opt-in diagnostics. Configure and disclose any remote crash-reporting
  destination before transmitting; scrub tokens/keys and keep bounded breadcrumbs.
- Validate invariants and bounds, preserve user state, and test invalid, concurrent and cancelled
  operations. Assert loudly in development and fail safely in release without weakening recovery.

## UI and compatibility

Follow `docs/DESIGN_SYSTEM.md`. Resource-backed labels and Material theme tokens are the default.
Colors selected by users, generated palettes and supplied pack artwork are functional data.
Decorative icons may have null descriptions when the containing accessible control already has
a resource-backed label; avoid duplicate TalkBack speech.

Business logic belongs in controllers/view models. Save small UI state with rememberSaveable
or SavedStateHandle; persist asset references instead of bitmaps or security grants. Paginate
network-backed collections and bound buffers; a finite list of local settings is not an unbounded
fetch and does not require a paging dependency.

Preserve public API/DTO compatibility through overloads, adapters and explicit schema versions.
Test dependent modules. Pin dependencies and action references, verify checksums, and distinguish
“newer version exists” from a confirmed vulnerability or abandoned project.
