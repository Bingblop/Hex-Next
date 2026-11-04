import {
  ProviderUnavailableError,
  type Env,
  type PurchaseProvider,
  type VerifyRequest,
  type VerifyResult,
} from "./types";

const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
const API_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
/** Refresh the access token this long before Google says it expires. */
const EXPIRY_MARGIN_SECONDS = 120;

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

/** The fields of Google's ProductPurchase resource that are used here. */
interface ProductPurchase {
  purchaseState?: number;
  orderId?: string;
  productId?: string;
}

/**
 * Products sold as auto-renewing subscriptions. They are checked with the subscriptionsv2 API;
 * every other product id is a one-time product. Keep in sync with ProductIds in the app.
 */
export const SUBSCRIPTION_PRODUCTS: ReadonlySet<string> = new Set(["ai_monthly"]);

/** The fields of Google's SubscriptionPurchaseV2 resource that are used here. */
interface SubscriptionPurchaseV2 {
  subscriptionState?: string;
  latestOrderId?: string;
  lineItems?: { productId?: string; expiryTime?: string }[];
}

/** States in which the subscriber keeps access until the line item's expiryTime. */
const ENTITLED_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
  // Cancelled: auto-renew is off, but the paid period has not ended yet.
  "SUBSCRIPTION_STATE_CANCELED",
]);

interface CachedToken {
  key: string;
  accessToken: string;
  expiresAt: number;
}

// Lives as long as the Worker isolate, so most requests skip the token exchange.
let cachedToken: CachedToken | null = null;

/** Test hook: forget the cached access token. */
export function resetTokenCache(): void {
  cachedToken = null;
}

export const googlePlayProvider: PurchaseProvider = {
  name: "google_play",
  async verify(request: VerifyRequest, env: Env): Promise<VerifyResult> {
    const account = parseServiceAccount(env.GOOGLE_SERVICE_ACCOUNT_JSON);
    const accessToken = await getAccessToken(account);

    const subscription = SUBSCRIPTION_PRODUCTS.has(request.productId);
    const url = subscription
      ? `${API_BASE}/${encodeURIComponent(request.packageName)}` +
        `/purchases/subscriptionsv2/tokens/${encodeURIComponent(request.purchaseToken)}`
      : `${API_BASE}/${encodeURIComponent(request.packageName)}` +
        `/purchases/products/${encodeURIComponent(request.productId)}` +
        `/tokens/${encodeURIComponent(request.purchaseToken)}`;

    let response: Response;
    try {
      response = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
    } catch {
      throw new ProviderUnavailableError("Google Play API unreachable");
    }

    if (response.status === 400 || response.status === 404 || response.status === 410) {
      return { valid: false, reason: "purchase_not_found" };
    }
    if (response.status === 401) {
      cachedToken = null;
    }
    if (!response.ok) {
      throw new ProviderUnavailableError(`Google Play API returned ${response.status}`);
    }

    if (subscription) {
      const purchase = (await response.json()) as SubscriptionPurchaseV2;
      return interpretSubscription(purchase, request.productId, Date.now());
    }
    const purchase = (await response.json()) as ProductPurchase;
    return interpretPurchase(purchase, request.productId);
  },
};

/** Maps a ProductPurchase to a verification result. */
export function interpretPurchase(purchase: ProductPurchase, productId: string): VerifyResult {
  if (purchase.productId !== undefined && purchase.productId !== productId) {
    return { valid: false, reason: "product_mismatch" };
  }
  switch (purchase.purchaseState) {
    case 0:
      return { valid: true, productId, orderId: purchase.orderId ?? "" };
    case 1:
      return { valid: false, reason: "purchase_cancelled" };
    case 2:
      return { valid: false, reason: "purchase_pending" };
    default:
      return { valid: false, reason: "not_purchased" };
  }
}

/**
 * Maps a SubscriptionPurchaseV2 to a verification result: valid while the subscription is in an
 * entitled state and its line item for [productId] has not expired at [nowMs].
 */
export function interpretSubscription(
  purchase: SubscriptionPurchaseV2,
  productId: string,
  nowMs: number,
): VerifyResult {
  const item = purchase.lineItems?.find((line) => line.productId === productId);
  if (!item) {
    return { valid: false, reason: "product_mismatch" };
  }
  const state = purchase.subscriptionState ?? "";
  if (state === "SUBSCRIPTION_STATE_PENDING") {
    return { valid: false, reason: "purchase_pending" };
  }
  if (!ENTITLED_STATES.has(state)) {
    return { valid: false, reason: "subscription_inactive" };
  }
  // ACTIVE and IN_GRACE_PERIOD are entitled whatever expiryTime says: during a grace period the
  // line item can still carry the unextended billing date, already in the past. A CANCELED
  // subscription stays paid only until its expiryTime.
  if (state !== "SUBSCRIPTION_STATE_CANCELED") {
    return { valid: true, productId, orderId: purchase.latestOrderId ?? "" };
  }
  const expiry = item.expiryTime ? Date.parse(item.expiryTime) : NaN;
  if (!Number.isFinite(expiry) || expiry <= nowMs) {
    return { valid: false, reason: "subscription_expired" };
  }
  return { valid: true, productId, orderId: purchase.latestOrderId ?? "" };
}

function parseServiceAccount(json: string | undefined): ServiceAccount {
  if (!json) {
    throw new ProviderUnavailableError("GOOGLE_SERVICE_ACCOUNT_JSON is not set");
  }
  let parsed: Partial<ServiceAccount>;
  try {
    parsed = JSON.parse(json) as Partial<ServiceAccount>;
  } catch {
    throw new ProviderUnavailableError("GOOGLE_SERVICE_ACCOUNT_JSON is not valid JSON");
  }
  if (typeof parsed.client_email !== "string" || typeof parsed.private_key !== "string") {
    throw new ProviderUnavailableError("GOOGLE_SERVICE_ACCOUNT_JSON lacks client_email or private_key");
  }
  return {
    client_email: parsed.client_email,
    private_key: parsed.private_key,
    token_uri: typeof parsed.token_uri === "string" ? parsed.token_uri : undefined,
  };
}

async function getAccessToken(account: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.key === account.client_email && cachedToken.expiresAt > now) {
    return cachedToken.accessToken;
  }

  const tokenUri = account.token_uri ?? DEFAULT_TOKEN_URI;
  const assertion = await createSignedJwt(account, tokenUri, now);

  let response: Response;
  try {
    response = await fetch(tokenUri, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }).toString(),
    });
  } catch {
    throw new ProviderUnavailableError("Google OAuth endpoint unreachable");
  }
  if (!response.ok) {
    throw new ProviderUnavailableError(`Google OAuth returned ${response.status}`);
  }

  const body = (await response.json()) as { access_token?: string; expires_in?: number };
  if (typeof body.access_token !== "string") {
    throw new ProviderUnavailableError("Google OAuth response lacks access_token");
  }
  const lifetime = typeof body.expires_in === "number" ? body.expires_in : 3600;
  cachedToken = {
    key: account.client_email,
    accessToken: body.access_token,
    expiresAt: now + Math.max(0, lifetime - EXPIRY_MARGIN_SECONDS),
  };
  return body.access_token;
}

/** Builds the RS256-signed JWT assertion for the OAuth2 service-account flow. */
export async function createSignedJwt(
  account: ServiceAccount,
  audience: string,
  issuedAt: number,
): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: account.client_email,
    scope: SCOPE,
    aud: audience,
    iat: issuedAt,
    exp: issuedAt + 3600,
  };
  const signingInput = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(claims)}`;

  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey(
      "pkcs8",
      pemToDer(account.private_key),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"],
    );
  } catch {
    throw new ProviderUnavailableError("Service account private_key could not be imported");
  }
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`;
}

function pemToDer(pem: string): ArrayBuffer {
  const base64 = pem
    .replace(/-----BEGIN [A-Z ]+-----/, "")
    .replace(/-----END [A-Z ]+-----/, "")
    .replace(/\s+/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

function base64UrlEncodeJson(value: unknown): string {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
