package com.hexnext.themer.billing

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.hexnext.themer.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Play Billing (library 8.x) for the one-time pack unlocks: one INAPP product per pack
 * ([ProductIds.forPack]) and [ProductIds.ALL_PACKS], which also covers packs added later.
 *
 * Entitlements are recomputed from `queryPurchasesAsync` on every start, refresh and resume, so
 * a refunded purchase disappears. Only purchases in the PURCHASED state that pass verification
 * unlock anything, and only those are acknowledged; PENDING purchases are shown as pending.
 * Verification uses the license server when [BuildConfig.LICENSE_SERVER_URL] is set, otherwise
 * the purchase signature against [BuildConfig.PLAY_LICENSE_KEY]. Without either, a debuggable
 * build grants with a logged warning and any other build grants nothing.
 *
 * Billing callbacks arrive on the main thread; verification runs on [Dispatchers.IO]. Mutable
 * fields are guarded by [lock] and every result is published through [state].
 */
class PlayEntitlements(private val context: Context) : PackEntitlements {

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** A release build with neither a license server nor a Play license key can verify nothing. */
    private val unverifiable = !debuggable && BuildConfig.LICENSE_SERVER_URL.isBlank() && BuildConfig.PLAY_LICENSE_KEY.isBlank()

    private val current = MutableStateFlow(
        EntitlementState(cachedProductIds = loadHint(), error = if (unverifiable) UNVERIFIABLE else null),
    )
    override val state: StateFlow<EntitlementState> = current.asStateFlow()

    /** Verification runs here; an unexpected failure is logged instead of crashing the app. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Purchase handling failed: ${e.javaClass.simpleName}")
            current.update { it.copy(checking = false, error = "Couldn't check your purchases. Try Restore purchases later.") }
        },
    )

    override val canPurchase: Boolean = true

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(PurchasesUpdatedListener { result, purchases -> onPurchasesUpdated(result, purchases) })
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    // ---- Guarded by lock ----
    private var started = false
    private var connecting = false
    private var retryScheduled = false
    private var retryDelayMs = INITIAL_RETRY_MS
    private val catalogueIds = LinkedHashSet<String>()
    private val details = HashMap<String, ProductDetails>()
    private val owned = HashSet<String>()
    private val pending = HashSet<String>()

    /** Purchase token → products it was verified for, so a resume does not verify again. */
    private val verifiedTokens = HashMap<String, Set<String>>()

    /** Product → [sequence] at which the purchase listener granted it in this run. */
    private val listenerGrants = HashMap<String, Long>()
    private var sequence = 0L

    // ---- PackEntitlements ----

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        connect()
    }

    override fun refresh() {
        val isStarted = synchronized(lock) { started }
        if (!isStarted) {
            start()
        } else if (client.isReady) {
            queryProducts()
            queryPurchases()
        } else {
            connect()
        }
    }

    override fun isUnlocked(packId: String): Boolean = current.value.unlocks(packId)

    override fun productIdFor(packId: String): String = ProductIds.forPack(packId)

    override fun setCatalogue(packIds: Collection<String>) {
        val changed = synchronized(lock) {
            val before = catalogueIds.size
            catalogueIds += packIds
            catalogueIds.size != before
        }
        if (!changed) return
        publish()
        if (client.isReady) queryProducts()
    }

    override fun launchPurchase(activity: Activity, productId: String) {
        if (unverifiable) {
            // Taking the payment would charge for something this build cannot unlock.
            Log.e(TAG, "Purchase refused: no license server or Play license key configured")
            setError(UNVERIFIABLE)
            return
        }
        val productDetails = synchronized(lock) { details[productId] }
        if (productDetails == null || !client.isReady) {
            setError("This item isn't available right now. Try again in a moment.")
            refresh()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(productDetails)
        // A one-time product with purchase options carries an offer token; a plain product has none.
        offerOf(productDetails)?.offerToken?.takeIf { it.isNotBlank() }?.let { productParams.setOfferToken(it) }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> setError(baseError())
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                setError(baseError())
                queryPurchases()
            }
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                setError("Google Play isn't connected. Try again in a moment.")
                connect()
            }
            else -> {
                Log.w(TAG, "launchBillingFlow failed: ${describe(result)}")
                setError("The purchase couldn't start. Try again later.")
            }
        }
    }

    // ---- Connection ----

    private fun connect() {
        if (client.isReady) {
            onConnected()
            return
        }
        synchronized(lock) {
            if (connecting) return
            connecting = true
        }
        try {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    synchronized(lock) { connecting = false }
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        synchronized(lock) { retryDelayMs = INITIAL_RETRY_MS }
                        onConnected()
                    } else {
                        Log.w(TAG, "Billing setup failed: ${describe(billingResult)}")
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
                            setError("Google Play purchases aren't available on this device.")
                        }
                        scheduleReconnect()
                    }
                }

                override fun onBillingServiceDisconnected() {
                    synchronized(lock) { connecting = false }
                    scheduleReconnect()
                }
            })
        } catch (e: IllegalStateException) {
            // startConnection while a connection is being set up or after endConnection.
            Log.w(TAG, "startConnection failed: ${e.message}")
            synchronized(lock) { connecting = false }
            scheduleReconnect()
        }
    }

    /** Reconnects after [retryDelayMs], doubling the delay up to [MAX_RETRY_MS]. */
    private fun scheduleReconnect() {
        val delay = synchronized(lock) {
            if (retryScheduled) return
            retryScheduled = true
            retryDelayMs.also { retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS) }
        }
        main.postDelayed({
            synchronized(lock) { retryScheduled = false }
            connect()
        }, delay)
    }

    private fun onConnected() {
        queryProducts()
        queryPurchases()
    }

    // ---- Products and prices ----

    private fun queryProducts() {
        val ids = synchronized(lock) { catalogueIds.mapTo(LinkedHashSet()) { ProductIds.forPack(it) } }
        ids.add(ProductIds.ALL_PACKS)
        ids.chunked(PRODUCTS_PER_QUERY).forEach { chunk ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    chunk.map { id ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    },
                )
                .build()
            // Billing 8: the listener gets a QueryProductDetailsResult; products Play could not
            // return (not created in Play Console yet, unavailable) are in unfetchedProductList.
            client.queryProductDetailsAsync(params) { result, queryResult ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "queryProductDetails failed: ${describe(result)}")
                    if (result.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) connect()
                    return@queryProductDetailsAsync
                }
                val fetched: List<ProductDetails> = queryResult.productDetailsList.orEmpty()
                synchronized(lock) { fetched.forEach { details[it.productId] = it } }
                publish()
            }
        }
    }

    // ---- Purchases ----

    private fun queryPurchases() {
        current.update { it.copy(checking = true) }
        val startedAt = synchronized(lock) { sequence }
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases failed: ${describe(result)}")
                current.update { it.copy(checking = false, error = "Couldn't check your purchases. Try Restore purchases later.") }
                if (result.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) connect()
                return@queryPurchasesAsync
            }
            val snapshot = purchases.toList()
            scope.launch { handlePurchases(snapshot, fullSnapshotSince = startedAt) }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val snapshot = purchases.orEmpty().toList()
                if (snapshot.isNotEmpty()) scope.launch { handlePurchases(snapshot, fullSnapshotSince = null) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> setError(baseError())
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                setError("Google Play disconnected during the purchase. Tap Restore purchases if you were charged.")
                connect()
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${describe(result)}")
                setError("The purchase didn't complete. Try again later.")
            }
        }
    }

    /**
     * Verifies [purchases] and publishes the result. [fullSnapshotSince] is non-null for a full
     * `queryPurchasesAsync` answer: owned products are then replaced by what it holds (refunds
     * disappear), keeping only listener grants made after that query started.
     */
    private fun handlePurchases(purchases: List<Purchase>, fullSnapshotSince: Long?) {
        val granted = HashSet<String>()
        val pendingNow = HashSet<String>()
        var failure: String? = null
        for (purchase in purchases) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PENDING -> pendingNow += purchase.products
                Purchase.PurchaseState.PURCHASED -> {
                    val verdict = verify(purchase)
                    if (verdict.products.isNotEmpty()) {
                        granted += verdict.products
                        if (!purchase.isAcknowledged) acknowledge(purchase)
                    } else if (verdict.error != null) {
                        failure = verdict.error
                    }
                }
                else -> Unit
            }
        }
        synchronized(lock) {
            if (fullSnapshotSince != null) {
                listenerGrants.values.removeAll { it <= fullSnapshotSince }
                owned.clear()
                owned += granted
                owned += listenerGrants.keys
                pending.clear()
                pending += pendingNow
            } else {
                sequence++
                granted.forEach { listenerGrants[it] = sequence }
                owned += granted
                pending -= granted
                pending += pendingNow
            }
            pending -= owned
        }
        publish(ready = fullSnapshotSince != null, error = failure, clearError = fullSnapshotSince != null || granted.isNotEmpty())
    }

    private class Verdict(val products: Set<String>, val error: String? = null)

    /** The products [purchase] may unlock; empty (with a reason for the user) when it fails verification. */
    private fun verify(purchase: Purchase): Verdict {
        synchronized(lock) { verifiedTokens[purchase.purchaseToken] }?.let { return Verdict(it) }
        if (purchase.packageName != context.packageName) {
            Log.w(TAG, "Purchase for another package ignored")
            return Verdict(emptySet())
        }
        val serverUrl = BuildConfig.LICENSE_SERVER_URL.trim()
        val licenseKey = BuildConfig.PLAY_LICENSE_KEY.trim()
        val products: Set<String> = when {
            serverUrl.isNotEmpty() -> {
                val ok = HashSet<String>()
                for (productId in purchase.products) {
                    when (verifyWithServer(serverUrl, purchase, productId)) {
                        true -> ok += productId
                        false -> Unit
                        null -> {
                            // The server could not say (offline, rate limited, store unreachable).
                            // A Google-signed purchase still counts when this build has the key.
                            if (licenseKey.isNotEmpty() && PurchaseSignature.verify(licenseKey, purchase.originalJson, purchase.signature)) {
                                Log.w(TAG, "License server unavailable: purchase granted on its Play signature")
                                ok += productId
                            } else {
                                return Verdict(emptySet(), "Couldn't verify your purchase. Check your connection, then tap Restore purchases.")
                            }
                        }
                    }
                }
                if (ok.isEmpty()) return Verdict(emptySet(), "Your purchase couldn't be verified.")
                ok
            }
            licenseKey.isNotEmpty() -> {
                if (!PurchaseSignature.verify(licenseKey, purchase.originalJson, purchase.signature)) {
                    Log.w(TAG, "Purchase signature check failed")
                    return Verdict(emptySet(), "Your purchase couldn't be verified.")
                }
                purchase.products.toSet()
            }
            debuggable -> {
                Log.w(TAG, "No license server or Play license key in this debuggable build: granting the purchase unverified")
                purchase.products.toSet()
            }
            else -> {
                Log.e(TAG, "No license server or Play license key configured: purchases are not granted")
                return Verdict(emptySet(), UNVERIFIABLE)
            }
        }
        synchronized(lock) { verifiedTokens[purchase.purchaseToken] = products }
        return Verdict(products)
    }

    /**
     * POSTs the purchase to `<server>/v1/verify` (server/README.md) and expects
     * `{"valid":true,"productId":...}`. Returns true or false for an answer (HTTP 200, or 400 for a
     * request the server rejects), null when the server could not say (unreachable, 429, 5xx).
     * Runs on [Dispatchers.IO] only (called from [handlePurchases]).
     */
    private fun verifyWithServer(serverUrl: String, purchase: Purchase, productId: String): Boolean? {
        // The configured value may be the server's base URL or the full endpoint.
        val base = serverUrl.trimEnd('/')
        val endpoint = if (base.endsWith(VERIFY_PATH)) base else base + VERIFY_PATH
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "The license server URL is not HTTPS; purchases are not granted")
            return false
        }
        val body = JSONObject()
            .put("packageName", purchase.packageName)
            .put("productId", productId)
            .put("purchaseToken", purchase.purchaseToken)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.e(TAG, "Invalid license server URL: ${e.javaClass.simpleName}")
            return false
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "License server answered HTTP $code")
                // 400 rejects this request; 429, 5xx and anything else mean "unknown".
                return if (code == HttpURLConnection.HTTP_BAD_REQUEST) false else null
            }
            val text = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > MAX_RESPONSE_BYTES) throw IOException("License server response too large")
                    out.write(chunk, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
            json.optBoolean("valid", false) && json.optString("productId") == productId
        } catch (e: IOException) {
            Log.w(TAG, "License server unreachable: ${e.javaClass.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        main.post {
            client.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    // Not fatal: the next query acknowledges again. Unacknowledged purchases are
                    // refunded by Play after three days, so it must eventually succeed.
                    Log.w(TAG, "acknowledgePurchase failed: ${describe(result)}")
                }
            }
        }
    }

    // ---- State ----

    /** The error that stays while nothing else is wrong. */
    private fun baseError(): String? = if (unverifiable) UNVERIFIABLE else null

    private fun setError(message: String?) = current.update { if (it.error == message) it else it.copy(error = message) }

    /** Publishes the guarded fields to [state]; saves the owned hint once purchases were read. */
    private fun publish(ready: Boolean = false, error: String? = null, clearError: Boolean = false) {
        val ownedNow: Set<String>
        val pendingNow: Set<String>
        val packIds: Set<String>
        val prices: Map<String, String>
        synchronized(lock) {
            ownedNow = owned.toSet()
            pendingNow = pending.toSet()
            packIds = catalogueIds.toSet()
            prices = details.mapNotNull { (id, d) -> offerOf(d)?.formattedPrice?.let { id to it } }.toMap()
        }
        val unlockAll = ProductIds.ALL_PACKS in ownedNow
        current.update {
            val isReady = it.ready || ready
            it.copy(
                ready = isReady,
                checking = if (ready) false else it.checking,
                unlockAll = unlockAll,
                ownedProductIds = ownedNow,
                ownedPackIds = packIds.filterTo(HashSet()) { id -> unlockAll || ProductIds.forPack(id) in ownedNow },
                pendingProductIds = pendingNow,
                prices = prices,
                error = error ?: if (clearError) baseError() else it.error,
            )
        }
        if (current.value.ready) saveHint(ownedNow)
    }

    private fun loadHint(): Set<String> = runCatching { prefs.getStringSet(KEY_OWNED_HINT, null)?.toSet() }.getOrNull().orEmpty()

    private fun saveHint(products: Set<String>) {
        if (loadHint() != products) prefs.edit().putStringSet(KEY_OWNED_HINT, HashSet(products)).apply()
    }

    /**
     * The offer that is bought: the base purchase option (no offer id) of a product with several
     * options and offers, else its first one, else the product's single one-time offer.
     */
    private fun offerOf(product: ProductDetails): ProductDetails.OneTimePurchaseOfferDetails? {
        val offers: List<ProductDetails.OneTimePurchaseOfferDetails> = product.oneTimePurchaseOfferDetailsList.orEmpty()
        return offers.firstOrNull { it.offerId.isNullOrEmpty() } ?: offers.firstOrNull() ?: product.oneTimePurchaseOfferDetails
    }

    private fun describe(result: BillingResult): String = "code ${result.responseCode} ${result.debugMessage}"

    private companion object {
        const val TAG = "HexNextBilling"
        const val PREFS = "hexnext_billing"
        const val KEY_OWNED_HINT = "owned_hint"
        const val VERIFY_PATH = "/v1/verify"
        const val UNVERIFIABLE = "Purchases can't be verified in this build"
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 5L * 60 * 1000
        const val PRODUCTS_PER_QUERY = 20
        const val TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
