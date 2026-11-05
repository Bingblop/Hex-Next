# Hex Next security review and release-hardening plan

**Status:** Planning document only — no application, billing, entitlement, download, deployment, or CI behavior is changed by this PR.

**Review baseline:** `main` at `eaf8e608a1e6190562c3cc83c5221b8548a901e8` (2026-11-04). This is a static source/configuration review, not a penetration test or a live test of deployed Cloudflare/GitHub infrastructure.

## 1. Executive summary

Hex Next is still in active development and production iteration. The findings below are recorded so they can be addressed deliberately before a paid public release. This document does **not** recommend abruptly disabling development builds, changing current entitlement behavior, moving assets, or altering the in-flight PRs.

The principal release risk is that the current app appears to use local/client-side entitlement checks to gate content whose files are hosted at public GitHub raw URLs. A modified client can bypass a local check, and a public file URL does not enforce a purchase. A second important exposure is the owner build path: the public source contains an owner flavor that grants all packs, and a main-branch owner release artifact was present in GitHub Actions at the time of this review.

These are monetization/access-control risks, not evidence that the Google Play purchase verification service itself is wholly bypassable. The server-side Google Play verification path has useful checks and should be retained.

### Priority legend

- **P0 — release blocker:** close before charging customers or treating paid packs as protected.
- **P1 — high:** resolve before production release, or explicitly accept and document the residual risk.
- **P2 — medium:** harden and monitor; schedule before wider public exposure where practical.
- **Future consideration:** design/operational work to complete as the release architecture is finalized.

## 2. Findings

### HNX-SEC-01 — Paid pack files are served from public GitHub URLs

**Priority:** P0 for a paid release  
**Status:** Confirmed by source review; no live unauthorized-download test was performed.

Evidence:
- `PackStore.kt` defines the default catalogue base as `https://raw.githubusercontent.com/Bingblop/Hex-Next/packs/`.
- `MainViewModel.downloadPack` checks `isPackUnlocked` locally before calling the pack downloader.
- The pack downloader retrieves the file directly from the URL; the request does not carry a server-issued entitlement credential or perform a server-side authorization check.
- Pack application also has local entitlement checks, which are useful UX safeguards but are not a security boundary against a modified APK.

**Impact:** Anyone who can obtain the public catalogue/file path can request the hosted pack directly. A user who modifies the client can remove or bypass local checks. Publicly hosted paid assets therefore should not be considered access-controlled, even if the official app UI hides them.

**Recommended release fix:**
1. Move paid pack archives and any paid catalogue metadata that reveals protected asset locations to private/object storage or another non-public origin.
2. Have the app request a download authorization from the license backend.
3. Verify the Google Play purchase server-side for the requested product/pack and bind the entitlement to the requested asset.
4. Return a short-lived, single-purpose signed URL or stream the asset through the backend. Keep URLs short-lived and avoid logging tokens.
5. Apply per-account and per-device limits where appropriate; support revocation and purchase refund/cancellation updates.
6. Keep free/demo packs separately addressable if they are intentionally public.

**Development-safe interim:** It is acceptable to retain the current public test packs while development continues, but mark them as non-confidential/test content and do not represent them as protected paid assets. Do not put real paid-release content at those public paths.

**Acceptance tests before release:**
- A clean install with no purchase cannot retrieve a paid archive.
- A modified APK with the local unlock check removed still cannot retrieve a paid archive.
- Invalid, pending, expired, refunded, cancelled, wrong-product, and wrong-package purchases are denied.
- A valid purchase receives only the entitled pack; expired download URLs stop working.
- Revocation and refund behavior is tested end-to-end.

### HNX-SEC-02 — Owner entitlement implementation and owner APK distribution are public

**Priority:** P0 if owner access is intended to be private  
**Status:** Confirmed in source and GitHub Actions metadata at review time.

Evidence:
- The public `owner` source set implements owner entitlements with `unlockAll = true` and grants all packs.
- The Android workflow includes an owner-release build/artifact upload path when signing secrets are configured.
- A main-branch artifact named `HexNext-Owner-Release` (artifact ID `13184404532`, associated with commit `219351bfac9a41de0a072a4bb757bcd925cb563e`) was listed as downloadable and unexpired at the time checked on 2026-11-04.
- The repository is public. The owner flavor source is therefore visible and can be built by anyone with the repository.

**Impact:** A public owner APK is a ready-made all-packs build. More importantly, removing that artifact alone is not sufficient: the public source currently exposes the owner-flavor implementation, and a client-side owner flag cannot be a trusted authorization mechanism.

**Recommended release fix:**
1. Decide whether the owner flavor is strictly an internal development convenience or a supported public build.
2. If private, move owner-only entitlement code and its build/distribution path to a private repository or private build layer. Do not rely on a hidden build variant or obfuscation as a security boundary.
3. Do not upload owner APKs to publicly accessible workflow artifacts/releases. Restrict internal builds to private storage and authorized users.
4. Review existing public artifacts/releases and remove or expire owner artifacts that should not remain accessible, following an explicit retention decision.
5. Enforce paid asset access at the server regardless of APK flavor.

**Development-safe interim:** Keep the owner flavor available for internal testing if needed, but treat it as a development bypass, not proof of purchase. Avoid publishing owner APK artifacts to a public repository while they grant all packs.

### HNX-SEC-03 — Debug purchase bypass must remain internal

**Priority:** P1  
**Status:** Confirmed intentional debug-only behavior in source; distribution exposure depends on which workflow artifacts are shared.

Evidence:
- `PlayEntitlements.kt` contains a debuggable-build path that can grant a purchase without normal production verification.
- The Android workflow publishes a debug installable artifact.

**Impact:** A debug build is not a trustworthy paid customer build. If shared broadly, it can expose debug-only behavior and undermine the intended purchase flow. This is not evidence that a release-signed production APK automatically accepts fabricated purchases.

**Recommended release fix:**
1. Keep the bypass guarded by an explicit debug/internal build condition and add a regression test proving it is unreachable in release variants.
2. Do not distribute debug APKs as customer builds; restrict debug artifacts to internal collaborators.
3. Ensure release signing and release variant configuration cannot inherit debug flags or debug endpoints.
4. Add a release-build check that fails if the debug entitlement bypass is enabled.

**Development-safe interim:** Continue using debug builds for internal development, but label and distribute them as internal test artifacts only.

### HNX-SEC-04 — Main branch has no branch protection or required checks

**Priority:** P1  
**Status:** Confirmed at review time.

Evidence:
- GitHub reports `main` as unprotected, with no required status checks.

**Impact:** Changes can land on the release branch without required review or passing CI. This increases the chance that a billing, entitlement, or signing regression reaches a release build.

**Recommended fix before release:**
1. Enable branch protection or a ruleset for `main`.
2. Require pull requests and at least one independent review where practical.
3. Require successful Android build/tests and server tests before merge.
4. Require security scanning and dependency checks once restored.
5. Restrict force pushes and branch deletion; require conversation resolution.
6. Keep an emergency break-glass path documented and audited.

**Development-safe interim:** Protection can be introduced in stages. Avoid blocking the current in-flight work unexpectedly; agree on required checks and reviewer expectations before enforcement.

### HNX-SEC-05 — CodeQL workflow is absent from current main

**Priority:** P1  
**Status:** Confirmed configuration gap.

Evidence:
- The latest reviewed main commit deletes `.github/workflows/codeql.yml`.
- Two historical high-severity CodeQL alerts were shown as fixed in the prior review; they are not being reported here as active vulnerabilities.
- No open secret-scanning alerts were returned in the repository alert query.

**Impact:** The repository no longer has the reviewed CodeQL workflow on main, reducing automated detection of future code-level security regressions.

**Recommended fix:**
1. Restore CodeQL or an equivalent maintained static-analysis workflow.
2. Run it on pull requests to main, pushes to main, and a scheduled cadence.
3. Make the security check required once stable and tuned.
4. Triage new alerts; document suppressions with rationale and expiry.
5. Retain secret scanning and enable push protection where available.

### HNX-SEC-06 — License API rate limiting is best-effort and may not protect workers.dev

**Priority:** P2  
**Status:** Conditional deployment concern; not confirmed as an exploitable production endpoint.

Evidence:
- `server/src/rateLimit.ts` describes a naive fixed-window limiter backed by the Cloudflare Cache API, with counts scoped per Cloudflare location and possible concurrent-request races.
- Its comments note that the Cache API rate limiting approach is ineffective on `workers.dev` hostnames.
- `server/wrangler.toml` does not explicitly set `workers_dev = false`; actual deployed hostname exposure and Cloudflare routing were not inspected.

**Impact:** If the Worker is reachable on a workers.dev hostname, or if the limiter is relied on as a strict global quota, requests may bypass or exceed the intended limit. This is primarily an abuse/availability and cost-control concern, not a demonstrated entitlement bypass.

**Recommended fix before public scale:**
1. Inspect deployed routes and disable the workers.dev subdomain for production if it is not required.
2. Use a rate limiter with documented consistency/atomicity guarantees (for example, a Durable Object-backed limiter or an appropriate managed gateway).
3. Apply limits by authenticated account/purchase token as well as IP, with conservative IP fallback and abuse monitoring.
4. Add request-size, concurrency, and batch-size limits; return retry guidance and log abuse signals without logging purchase tokens.
5. Test limits across multiple Cloudflare locations and alternate hostnames.

**Development-safe interim:** Keep the current implementation for development if operationally adequate, but do not treat it as a strong global rate limit or cost ceiling.

## 3. Positive controls to preserve

The review also found useful controls that should not be weakened during refactoring:

- The Google Play provider path uses the Android Publisher API rather than trusting a client-supplied “purchased” boolean.
- The server checks purchase state and product/package consistency and rejects pending/cancelled states.
- Entitlement refresh reconciles the current purchase set rather than blindly preserving every locally cached unlock.
- The app does not appear to contain the Google Play service-account private key; server credentials are configured separately.
- The repository secret-scanning query returned no open alerts at the time of review.
- The historical CodeQL alerts mentioned above were marked fixed.

These are source/configuration observations, not a guarantee that production secrets, Worker bindings, IAM roles, or live purchase flows are correctly configured. Those require deployment-side verification.

## 4. Additional release-readiness considerations

These are not asserted as current vulnerabilities; they are recommended checks for a paid production service.

### Billing and entitlement lifecycle
- Verify purchase token ownership, package name, product ID, purchase state, acknowledgement/consumption requirements, and subscription state where applicable.
- Test restore on a second device, reinstall, account switching, refunds, revocations, chargebacks, and offline startup.
- Make server authorization the source of truth for paid assets; local cache should improve UX, not grant durable authority.
- Define behavior when the license server or Google Play is unavailable. Fail safely for new downloads while allowing a documented offline grace policy for previously authorized content if desired.
- Ensure batch verification cannot mix tokens/products across users or bypass per-item checks.

### Signing, build provenance, and release channels
- Keep upload/signing keys out of the repository and public artifacts; use least-privilege CI secrets.
- Separate debug, internal/owner, beta, and production signing identities and endpoints.
- Pin third-party GitHub Actions to immutable commit SHAs where feasible.
- Consider Gradle dependency verification, dependency update monitoring, an SBOM, and provenance/attestation for release artifacts.
- Define who can trigger production releases and who can access signing secrets.

### Cloudflare and backend operations
- Review production Worker routes, workers.dev exposure, environment bindings, secret names, service-account scopes, and deployment permissions.
- Separate staging and production Worker environments and credentials.
- Add structured security/abuse telemetry, alerting, retention limits, and a documented incident response process.
- Confirm CORS/origin policy is not being mistaken for authentication; native app requests can be made outside the official UI.
- Avoid logging raw purchase tokens, signed URLs, or other bearer credentials.

### Android client and data handling
- Treat every client-side check, local preference, build flag, and obfuscated constant as modifiable by an attacker.
- Review exported components, deep links, content/file providers, backup rules, and intent handling when the app's component surface stabilizes.
- Review sensitive local data storage and Android backup/restore behavior.
- Consider tamper signals only as defense-in-depth; do not use them as the sole authorization control.

## 5. Recommended sequencing

| Stage | Work | Release gate |
|---|---|---|
| During active development | Keep current dev/test behavior; label public test packs and debug/owner builds as non-production; avoid uploading owner builds publicly | No disruption to current development |
| Before monetized beta | Decide owner-flavor distribution; restore security scanning; agree branch protection/CI checks; inspect deployed Worker routes and secrets | No accidental public owner distribution; security checks running |
| Before paid public release | Move paid assets behind server authorization; implement short-lived authorized delivery; test modified-client and purchase lifecycle cases; harden rate limiting | Unpurchased/modified clients cannot obtain paid content |
| Before ongoing scale | Add provenance/SBOM, monitoring, abuse alerts, incident response, periodic access review and penetration testing | Operational controls and recovery tested |

## 6. Explicit scope and limitations

- This PR is documentation only. It intentionally does not change code, build variants, entitlements, pack hosting, CI, branch protection, or Cloudflare configuration.
- The review baseline is the main branch commit stated at the top. Pull request branches are not treated as merged code.
- PR #43 and PR #45 are intentionally left untouched. PR #45 includes billing/license-server work; the relevant areas should be re-reviewed against the final merged implementation before turning these recommendations into code changes.
- No deployed Worker, Cloudflare dashboard, GitHub secret values, private bucket permissions, production purchase account, or live exploit path was tested.
- Public artifact availability is time-sensitive; re-check the artifact list and retention settings when acting on HNX-SEC-02.
- Findings should be revalidated against the exact release candidate and deployment configuration before being treated as release sign-off.

## 7. Suggested follow-up work items

1. **Paid asset authorization:** design and implement server-authorized pack delivery.
2. **Owner build containment:** decide whether owner flavor is private; remove public distribution and move privileged source/build logic if required.
3. **Debug/release separation:** add tests and CI assertions that release variants cannot use debug purchase bypasses.
4. **Repository governance:** restore security scanning and enable agreed branch protection.
5. **Worker abuse controls:** inspect deployed routes and replace best-effort rate limiting before broad public traffic.
6. **Release security test plan:** automate modified-client, direct-download, purchase lifecycle, and rate-limit tests.

---

*Prepared as a planning artifact for an application still in active development. Do not interpret the P0/P1 labels as a request to interrupt current development; they identify what must be addressed before relying on the paid-access model for a public release.*
