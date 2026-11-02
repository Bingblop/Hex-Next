import type { Env, PurchaseProvider, VerifyRequest, VerifyResult } from "./types";

/**
 * Placeholder for selling packs outside Google Play (for example through Paddle
 * or Lemon Squeezy). Enable it with PROVIDER = "direct_sale" once implemented.
 *
 * To implement: treat `purchaseToken` as the licence key the checkout issued,
 * call the provider's licence or order lookup API with an API key stored as a
 * Worker secret, map the provider's product to `productId`, and return
 * { valid: true, productId, orderId } only for a paid, non-refunded order.
 * Throw ProviderUnavailableError when the provider cannot be reached.
 */
export const directSaleProvider: PurchaseProvider = {
  name: "direct_sale",
  async verify(_request: VerifyRequest, _env: Env): Promise<VerifyResult> {
    return { valid: false, reason: "provider_not_configured" };
  },
};
