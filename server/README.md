# Hex Next licence server

A small Cloudflare Worker that confirms theme-pack purchases with the store. It is not deployed yet.

The app sends the purchase token it got from Play Billing, and the Worker asks Google Play whether that purchase is real. The app still acknowledges purchases itself; the Worker only reads.

## API

`POST /v1/verify` with a JSON body of at most 4 KB:

```json
{ "packageName": "com.hexnext.themer", "productId": "pack_neon", "purchaseToken": "..." }
```

| Status | Body | Meaning |
| --- | --- | --- |
| 200 | `{"valid":true,"productId":"pack_neon","orderId":"GPA.1234-..."}` | The purchase exists and is in the purchased state. `orderId` can be empty for licence-tester purchases. |
| 200 | `{"valid":false,"reason":"purchase_not_found"}` | The store answered no. Other reasons: `purchase_cancelled`, `purchase_pending`, `not_purchased`, `product_mismatch`. |
| 400 | `{"valid":false,"reason":"..."}` | The request was malformed: `invalid_json`, `body_not_object`, `invalid_package_name`, `wrong_package`, `invalid_product_id`, `invalid_purchase_token`. |
| 413 | `reason: body_too_large` | The body was over 4 KB. |
| 429 | `reason: rate_limited` | Too many requests from this IP in the current minute. |
| 503 | `reason: provider_unavailable` or `unknown_provider` | The Worker could not reach the store, or it is misconfigured. The client should treat this as "unknown" and keep its cached state, not as "not purchased". |
| 404, 405 | `not_found`, `method_not_allowed` | Wrong path or method. |

The Worker checks that `packageName` equals `PACKAGE_NAME`, then calls `purchases.products.get` on the Google Play Developer API. It returns `valid: true` only when `purchaseState` is `0`. Purchase tokens are never logged.

## Configuration

Variables live in `wrangler.toml` and hold nothing secret:

- `PACKAGE_NAME`: the only application id the Worker accepts (`com.hexnext.themer`). The owner build unlocks every pack locally and never calls the Worker.
- `PROVIDER`: `google_play` (the default) or `direct_sale`. `direct_sale` is a stub in `src/providers/directSale.ts` for a later Paddle or Lemon Squeezy integration, and it answers `provider_not_configured` until it is implemented.
- `RATE_LIMIT_PER_MINUTE`: the per-IP limit (default 30, and `0` turns it off). It is a naive count kept in the Cache API, so it applies separately at each Cloudflare location. The Cache API does nothing on `*.workers.dev` hostnames, so the limit only works once the Worker has a custom domain.

The only secret is `GOOGLE_SERVICE_ACCOUNT_JSON`.

## Deploy

1. **Create a service account.** In Google Cloud, create a service account in a project linked to the Play Console and create a JSON key for it. Keep the key file outside this repository, and never commit it.
2. **Grant it access.** In Play Console, go to Users and permissions, invite the service account's email, and give it **View financial data** for Hex Next. New permissions can take up to 24 hours to apply.
3. **Install and log in.**
   ```sh
   cd server
   npm ci
   npx wrangler login
   ```
4. **Store the key as a secret.** Wrangler reads the value from stdin.
   ```sh
   npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON < /path/to/service-account.json
   ```
5. **Deploy.**
   ```sh
   npm run deploy
   ```
6. **Add a custom domain** (for example `licence.example.com`) under the Worker's Settings > Domains & Routes, so the rate limit works.
7. **Point the app at it.** Set the repository variable `HEXNEXT_LICENSE_SERVER_URL` to `https://<domain>` (the app appends `/v1/verify`; a value that already ends in `/v1/verify` also works). CI passes it into the play build's `BuildConfig.LICENSE_SERVER_URL`. The app only accepts an HTTPS URL.

To rotate the key, create a new JSON key, run `wrangler secret put` again, and delete the old key in Google Cloud.

## Develop and test

```sh
cd server
npm ci
npm test            # vitest: request validation, handler, JWT signing, Play responses
npm run typecheck   # tsc --noEmit
npm run dev         # local server; put GOOGLE_SERVICE_ACCOUNT_JSON in .dev.vars (gitignored)
```

The tests never touch the network. They use a throwaway RSA key generated on each run and a mocked `fetch`.

## Layout

- `src/index.ts`: routing, the body-size limit, the rate limit, validation, and provider dispatch.
- `src/validate.ts`: request validation and the capped body reader.
- `src/rateLimit.ts`: the Cache API rate limiter.
- `src/providers/types.ts`: the `PurchaseProvider` interface and the `Env` bindings.
- `src/providers/googlePlay.ts`: the OAuth2 service-account JWT (RS256 through WebCrypto), the access-token cache, and `purchases.products.get`.
- `src/providers/directSale.ts`: the direct-sale stub.
