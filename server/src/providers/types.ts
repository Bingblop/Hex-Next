/** Worker bindings. Secrets are set with `wrangler secret put`, never in wrangler.toml. */
export interface Env {
  PACKAGE_NAME: string;
  PROVIDER?: string;
  RATE_LIMIT_PER_MINUTE?: string;
  /** Secret: the full JSON key of a Google Cloud service account with Play Console access. */
  GOOGLE_SERVICE_ACCOUNT_JSON?: string;
}

/** A request body that passed validation. */
export interface VerifyRequest {
  packageName: string;
  productId: string;
  purchaseToken: string;
}

export type VerifyResult =
  | { valid: true; productId: string; orderId: string }
  | { valid: false; reason: string };

/**
 * A store that can confirm a purchase. Each provider answers with a VerifyResult
 * for a definite yes or no, and throws ProviderUnavailableError when it cannot
 * tell (network failure, store outage, missing configuration).
 */
export interface PurchaseProvider {
  readonly name: string;
  verify(request: VerifyRequest, env: Env): Promise<VerifyResult>;
}

export class ProviderUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ProviderUnavailableError";
  }
}
