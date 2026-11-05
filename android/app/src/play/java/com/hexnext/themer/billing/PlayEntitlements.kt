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
import com.hexnext.engine.pack.PackIdAliases
import com.hexnext.themer.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Play Billing (library 8.x) for the one-time pack unlocks: one INAPP product per pack
 * ([ProductIds.forPack]), [ProductIds.ALL_PACKS], which also covers packs added later, and
 * [ProductIds.AI_LIFETIME] for AI theme creation. A
 * product of a pack's former catalogue id ([ProductIds.allForPack]) still unlocks that pack.
 *
 * Entitlements are recomputed from `queryPurchasesAsync` on every start, refresh and resume, so
 * a refunded purchase disappears. Only purchases in the PURCHASED state that pass verification
 * unlock anything, and only those are acknowledged; PENDING purchases are shown as pending.
 * Verification uses the license server when [BuildConfig.LICENSE_SERVER_URL] is set (one
 * `/v1/verify-batch` request for a whole restore, see [LicenseServer]), otherwise
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
    // Both: BuildConfig.DEBUG is a compile-time false in release builds, so R8 removes the unverified
    // grant below entirely; a release APK repackaged with android:debuggable="true" gains nothing.
    private val debuggable = BuildConfig.DEBUG && (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** A release build with neither a license server nor a Play license key can verify nothing. */
    private val unverifiable = !debuggable && BuildConfig.LICENSE_SERVER_URL.isBlank() && BuildConfig.PLAY_LICENSE_KEY.isBlank()

    private val current = MutableStateFlow(
        EntitlementState(
            cachedProductIds = loadHint(),
            cachedBundlePackIds = loadBundleHint(),
            error = if (unverifiable) UNVERIFIABLE else null,
        ),
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

    /** Grants the purchase listener made while a full query was still in flight. */
    private val listenerGrants = InFlightGrants()

    /**
     * Purchase token → the packs a verified bundle purchase unlocked. Rebuilt from Play on every
     * full query, so a refund drops the packs. [storedGrants] is the same map from the last run,
     * used only when Play does not echo the pack ids back on the purchase.
     */
    private val bundleByToken = HashMap<String, Set<String>>()
    private val storedGrants = loadStoredGrants()

    /** Token → [sequence] when the purchase listener recorded it, so a full query does not drop it. */
    private val bundleListenerSince = HashMap<String, Long>()

    /** Product id → packs the purchase just launched should unlock, until Play returns them. */
    private val launchGrants = HashMap<String, Set<String>>()

    /** Packs [bundleByToken] currently unlocks. Published by [publishLocked]. */
    private val bundlePackIdsNow = HashSet<String>()

    /** Generation of the newest full purchase query started, and of the newest one applied. */
    private var queryGeneration = 0L
    private var appliedGeneration = 0L

    /** False once the license server answered 404 for `/v1/verify-batch` (an older server). */
    private var batchSupported = true

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

    override fun launchPurchase(activity: Activity, productId: String, grantPackIds: Set<String>) {
        if (unverifiable) {
            // Taking the payment would charge for something this build cannot unlock.
            Log.e(TAG, "Purchase refused: no license server or Play license key configured")
            setError(UNVERIFIABLE)
            return
        }
        // Play product ids cannot be renamed, so a renamed pack may only exist in Play under a
        // former id. Buy the first product of the pack that has details, in the order priceOf uses.
        val productDetails = synchronized(lock) {
            val candidates = catalogueIds
                .map { PackIdAliases.current(it) }
                .firstOrNull { productId in ProductIds.allForPack(it) }
                ?.let { ProductIds.allForPack(it) }
                ?: listOf(productId)
            candidates.firstNotNullOfOrNull { details[it] }
        }
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
        val grant = grantPackIds.map { PackIdAliases.current(it) }.filter { it.isNotBlank() }.toSet()
        if (grant.isNotEmpty() && PackPricing.countOf(productDetails.productId) != null) {
            synchronized(lock) { launchGrants[productDetails.productId] = grant }
            val fields = BundleGrant.fields(grant)
            if (fields.profile.isNotEmpty()) params.setObfuscatedProfileId(fields.profile)
            if (fields.account.isNotEmpty()) params.setObfuscatedAccountId(fields.account)
        }
        val built = params.build()
        val result = client.launchBillingFlow(activity, built)
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
        // Each pack's current product and the products of its former catalogue ids
        // (ProductIds.allForPack), so a pack bought before it was renamed still shows its price.
        val ids = synchronized(lock) { catalogueIds.flatMapTo(LinkedHashSet()) { ProductIds.allForPack(it) } }
        ids.addAll(ProductIds.ONE_TIME_EXTRAS)
        ids.addAll(PackPricing.allProductIds())
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
        val query = synchronized(lock) { FullQuery(generation = ++queryGeneration, since = listenerGrants.sequence) }
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases failed: ${describe(result)}")
                current.update { it.copy(checking = false, error = "Couldn't check your purchases. Try Restore purchases later.") }
                if (result.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) connect()
                return@queryPurchasesAsync
            }
            val snapshot = purchases.toList()
            scope.launch { handlePurchases(snapshot, full = query) }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val snapshot = purchases.orEmpty().toList()
                if (snapshot.isNotEmpty()) {
                    // Taken on receipt: a full answer applied before the coroutine starts still counts.
                    val appliedAtReceipt = synchronized(lock) { appliedGeneration }
                    scope.launch { handlePurchases(snapshot, full = null, appliedAtStart = appliedAtReceipt) }
                }
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
     * One full `queryPurchasesAsync` call: [generation] grows with every call, [since] is
     * [InFlightGrants.sequence] when it started.
     */
    private class FullQuery(val generation: Long, val since: Long)

    /**
     * Verifies [purchases] and publishes the result. [full] is non-null for a full
     * `queryPurchasesAsync` answer: owned products are then replaced by what it holds (refunds
     * disappear), keeping only listener grants made after that query started. A kept grant the
     * snapshot does not contain starts one more full query, so a purchase refunded before this
     * answer was applied does not stay unlocked until the next resume.
     * Verification can take a while, so full answers can finish out of order: one older than the
     * newest full answer already applied is dropped, and the state is published under [lock] so
     * publications keep the order of the updates.
     */
    private suspend fun handlePurchases(
        purchases: List<Purchase>,
        full: FullQuery?,
        appliedAtStart: Long = synchronized(lock) { appliedGeneration },
    ) {
        val serverAnswers = verifyOnServer(purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED })
        val granted = HashSet<String>()
        val pendingNow = HashSet<String>()
        val grantedBundles = HashMap<String, Set<String>>()
        val snapshotTokens = purchases.mapTo(HashSet()) { it.purchaseToken }
        var failure: String? = null
        for (purchase in purchases) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PENDING -> pendingNow += purchase.products
                Purchase.PurchaseState.PURCHASED -> {
                    val verdict = verify(purchase, serverAnswers)
                    if (verdict.products.isNotEmpty()) {
                        granted += verdict.products
                        val packs = bundlePacksOf(purchase)
                        if (packs.isNotEmpty()) grantedBundles[purchase.purchaseToken] = packs
                        if (!purchase.isAcknowledged) acknowledge(purchase)
                    } else if (verdict.error != null) {
                        failure = verdict.error
                    }
                }
                else -> Unit
            }
        }
        var recheck = false
        synchronized(lock) {
            if (full != null) {
                if (full.generation <= appliedGeneration) {
                    Log.i(TAG, "Dropping purchase check ${full.generation}: check $appliedGeneration is newer")
                    return
                }
                appliedGeneration = full.generation
                // Every product Play listed as purchased, verified or not: a listener grant the
                // snapshot lists is not missing from it, so it is not rechecked and dropped when
                // the server is briefly unavailable. A stale PENDING entry does not confirm a
                // grant (the purchase may have completed and been refunded since), so it gets a
                // recheck. Refunded purchases leave the list entirely.
                val listed = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .flatMapTo(HashSet()) { it.products }
                val kept = listenerGrants.applySnapshot(full.since, listed)
                owned.clear()
                owned += granted
                owned += kept.kept
                pending.clear()
                pending += pendingNow
                val keep = bundleListenerSince.filterValues { it > full.since }.keys
                bundleByToken.keys.retainAll(grantedBundles.keys + keep)
                bundleByToken.putAll(grantedBundles)
                bundleListenerSince.keys.retainAll(bundleByToken.keys)
                recheck = kept.unconfirmed.isNotEmpty()
            } else {
                listenerGrants.note(granted)
                owned += granted
                pending -= granted
                pending += pendingNow
                grantedBundles.forEach { (token, ids) ->
                    bundleByToken[token] = ids
                    bundleListenerSince[token] = listenerGrants.sequence
                }
                // A full answer applied while this grant was verifying may not list it.
                recheck = granted.isNotEmpty() && appliedGeneration != appliedAtStart
            }
            pending -= owned
            recomputeBundleIdsLocked()
            saveStoredGrantsLocked(if (full != null) snapshotTokens else snapshotTokens + storedGrants.keys)
            // Still checking while a newer full query has not answered yet.
            val newestAnswered = full != null && full.generation == queryGeneration
            publishLocked(
                ready = full != null,
                checkingDone = newestAnswered,
                error = failure,
                clearError = full != null || granted.isNotEmpty(),
            )
        }
        // After the lock: queryPurchases takes it too. A purchase Play has not listed yet stays
        // owned for this one extra check; the next snapshot is authoritative.
        if (recheck) main.post { queryPurchases() }
    }

    private class Verdict(val products: Set<String>, val error: String? = null)

    /** A purchase token and one product of it, as the license server checks them. */
    private data class ServerKey(val purchaseToken: String, val productId: String)

    /**
     * The products [purchase] may unlock; empty (with a reason for the user) when it fails
     * verification. [serverAnswers] holds the license server's answers ([verifyOnServer]).
     */
    private fun verify(purchase: Purchase, serverAnswers: Map<ServerKey, Boolean?>): Verdict {
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
                    when (serverAnswers[ServerKey(purchase.purchaseToken, productId)]) {
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
     * Asks the license server about every product of [purchases] not verified yet in this run:
     * true or false for an answer, null when the server could not say. Uses `/v1/verify-batch`
     * (one rate-limited request per [LicenseServer.MAX_BATCH] products) and falls back to one
     * `/v1/verify` request per product when the server has no batch endpoint (HTTP 404). Empty
     * when no license server is configured. Runs on [Dispatchers.IO] only.
     */
    private suspend fun verifyOnServer(purchases: List<Purchase>): Map<ServerKey, Boolean?> {
        val serverUrl = BuildConfig.LICENSE_SERVER_URL.trim()
        if (serverUrl.isEmpty()) return emptyMap()
        val keys = LinkedHashSet<ServerKey>()
        for (purchase in purchases) {
            if (purchase.packageName != context.packageName) continue
            if (synchronized(lock) { purchase.purchaseToken in verifiedTokens }) continue
            purchase.products.forEach { keys += ServerKey(purchase.purchaseToken, it) }
        }
        if (keys.isEmpty()) return emptyMap()
        if (!serverUrl.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "The license server URL is not HTTPS; purchases are not granted")
            return keys.associateWith { false }
        }
        val answers = HashMap<ServerKey, Boolean?>()
        var single: List<ServerKey> = emptyList()
        if (synchronized(lock) { batchSupported }) {
            val chunks = keys.toList().chunked(LicenseServer.MAX_BATCH)
            for ((index, chunk) in chunks.withIndex()) {
                val batch = verifyBatch(LicenseServer.endpoint(serverUrl, LicenseServer.VERIFY_BATCH_PATH), chunk)
                if (batch == null) {
                    // No batch endpoint on this server: check the rest one by one from now on.
                    synchronized(lock) { batchSupported = false }
                    single = chunks.drop(index).flatten()
                    break
                }
                answers += batch
            }
        } else {
            single = keys.toList()
        }
        if (single.isNotEmpty()) {
            val endpoint = LicenseServer.endpoint(serverUrl, LicenseServer.VERIFY_PATH)
            for (key in single) answers[key] = verifyOne(endpoint, key)
        }
        return answers
    }

    /**
     * POSTs [keys] to `/v1/verify-batch` and maps its `results` (request order) to answers. Null
     * when the server answers 404 (no batch endpoint); every answer is null when it could not say.
     */
    private suspend fun verifyBatch(endpoint: String, keys: List<ServerKey>): Map<ServerKey, Boolean?>? {
        val items = JSONArray()
        keys.forEach { items.put(JSONObject().put("productId", it.productId).put("purchaseToken", it.purchaseToken)) }
        val body = JSONObject().put("packageName", context.packageName).put("purchases", items).toString()
        val answer = postJson(endpoint, body)
        val unknown = keys.associateWith<ServerKey, Boolean?> { null }
        return when (answer.code) {
            HttpURLConnection.HTTP_NOT_FOUND -> null
            HttpURLConnection.HTTP_OK -> {
                val results = runCatching { JSONObject(answer.body).getJSONArray("results") }.getOrNull()
                if (results == null || results.length() != keys.size) {
                    Log.w(TAG, "License server batch answer could not be read")
                    unknown
                } else {
                    val mapped = HashMap<ServerKey, Boolean?>()
                    keys.forEachIndexed { i, key ->
                        val item: JSONObject? = results.optJSONObject(i)
                        mapped[key] = item?.let {
                            LicenseServer.itemVerdict(
                                valid = it.optBoolean("valid", false),
                                answeredProductId = it.optString("productId", ""),
                                reason = it.optString("reason", ""),
                                requestedProductId = key.productId,
                            )
                        }
                    }
                    mapped
                }
            }
            // 400 rejects the whole request (the same check as /v1/verify).
            HttpURLConnection.HTTP_BAD_REQUEST, CODE_BAD_URL -> keys.associateWith { false }
            else -> unknown
        }
    }

    /**
     * POSTs one product to `/v1/verify` and expects `{"valid":true,"productId":...}`. True or
     * false for an answer (HTTP 200 with a readable verdict, or 400 for a request the server
     * rejects), null when the server could not say (unreachable, 429 after the retries, 5xx,
     * or an HTTP 200 body that is not the documented JSON).
     */
    private suspend fun verifyOne(endpoint: String, key: ServerKey): Boolean? {
        val body = JSONObject()
            .put("packageName", context.packageName)
            .put("productId", key.productId)
            .put("purchaseToken", key.purchaseToken)
            .toString()
        val answer = postJson(endpoint, body)
        return when (answer.code) {
            HttpURLConnection.HTTP_OK -> LicenseServer.bodyVerdict(answer.body, key.productId)
            HttpURLConnection.HTTP_BAD_REQUEST, CODE_BAD_URL -> false
            else -> null
        }
    }

    /** An HTTP answer; [code] is [CODE_UNREACHABLE] or [CODE_BAD_URL] when there was none. */
    private class HttpAnswer(val code: Int, val body: String = "", val retryAfter: String? = null)

    /**
     * POSTs [json] to [endpoint]. On HTTP 429 it waits as `Retry-After` asks (bounded, see
     * [LicenseServer.retryWaitMs]) and tries again, at most [LicenseServer.MAX_RATE_LIMIT_RETRIES]
     * times; the last answer is returned.
     */
    private suspend fun postJson(endpoint: String, json: String): HttpAnswer {
        val body = json.toByteArray(Charsets.UTF_8)
        var attempt = 0
        while (true) {
            val answer = postOnce(endpoint, body)
            if (answer.code != HTTP_TOO_MANY_REQUESTS || attempt >= LicenseServer.MAX_RATE_LIMIT_RETRIES) return answer
            val wait = LicenseServer.retryWaitMs(answer.retryAfter, attempt)
            Log.w(TAG, "License server is rate limiting; retrying in ${wait / 1000} s")
            delay(wait)
            attempt++
        }
    }

    private fun postOnce(endpoint: String, body: ByteArray): HttpAnswer {
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.e(TAG, "Invalid license server URL: ${e.javaClass.simpleName}")
            return HttpAnswer(CODE_BAD_URL)
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
                return HttpAnswer(code, retryAfter = connection.getHeaderField("Retry-After"))
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
            HttpAnswer(code, text)
        } catch (e: IOException) {
            Log.w(TAG, "License server unreachable: ${e.javaClass.simpleName}")
            HttpAnswer(CODE_UNREACHABLE)
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
    private fun publish() = synchronized(lock) { publishLocked() }

    /**
     * [publish] with [lock] held, so publications happen in the order of the updates. [ready]
     * marks a full purchase answer; [checkingDone] ends "checking" (the newest full query answered).
     */
    private fun publishLocked(ready: Boolean = false, checkingDone: Boolean = false, error: String? = null, clearError: Boolean = false) {
        val ownedNow = owned.toSet()
        val pendingNow = pending.toSet()
        val packIds = catalogueIds.toSet()
        val prices = details.mapNotNull { (id, d) -> offerOf(d)?.formattedPrice?.let { id to it } }.toMap()
        val unlockAll = ProductIds.ALL_PACKS in ownedNow
        current.update {
            val isReady = it.ready || ready
            it.copy(
                ready = isReady,
                checking = if (checkingDone) false else it.checking,
                unlockAll = unlockAll,
                ownedProductIds = ownedNow,
                ownedPackIds = packIds.filterTo(HashSet()) { id ->
                    unlockAll || ProductIds.allForPack(id).any { it in ownedNow } || PackIdAliases.current(id) in bundlePackIdsNow
                },
                bundlePackIds = bundlePackIdsNow.toSet(),
                pendingProductIds = pendingNow,
                prices = prices,
                error = error ?: if (clearError) baseError() else it.error,
            )
        }
        if (current.value.ready) {
            saveHint(ownedNow)
            saveBundleHint(bundlePackIdsNow)
        }
    }

    /**
     * Packs [purchase] unlocks when one of its products is a bundle. The ids Play stored on the
     * purchase win; the set remembered at launch, then the set from the last run, fill in when
     * Play did not echo them. Never more packs than the product was sold for.
     */
    private fun bundlePacksOf(purchase: Purchase): Set<String> {
        val product = purchase.products.firstOrNull { PackPricing.countOf(it) != null } ?: return emptySet()
        val count = PackPricing.countOf(product) ?: return emptySet()
        val echoed = purchase.accountIdentifiers?.let { BundleGrant.decode(it.obfuscatedProfileId, it.obfuscatedAccountId) }.orEmpty()
        val stored = synchronized(lock) { storedGrants[purchase.purchaseToken].orEmpty() }
        val launched = synchronized(lock) { launchGrants[product].orEmpty() }
        // The ids on this purchase win. The last run's copy of the same token fills a gap (Play
        // sometimes omits the fields). The set from the launch is only for a purchase that has
        // neither, so an older purchase of the same product is not relabelled.
        val base = when {
            stored.size > echoed.size && (echoed.isEmpty() || echoed.all { it in stored }) -> stored
            echoed.isNotEmpty() -> echoed
            stored.isNotEmpty() -> stored
            else -> launched
        }
        return BundleGrant.limit(base, count)
    }

    /** Recomputes [bundlePackIdsNow] from [bundleByToken]. Holds [lock]. */
    private fun recomputeBundleIdsLocked() {
        bundlePackIdsNow.clear()
        bundleByToken.values.forEach { ids -> ids.forEach { bundlePackIdsNow += PackIdAliases.current(it) } }
    }

    private fun loadBundleHint(): Set<String> =
        runCatching { prefs.getStringSet(KEY_BUNDLE_HINT, null)?.toSet() }.getOrNull().orEmpty()

    private fun saveBundleHint(packs: Set<String>) {
        if (loadBundleHint() != packs) prefs.edit().putStringSet(KEY_BUNDLE_HINT, HashSet(packs)).apply()
    }

    /** Token → pack ids from the last run. Not an unlock until Play still owns that purchase. */
    private fun loadStoredGrants(): HashMap<String, Set<String>> {
        val out = HashMap<String, Set<String>>()
        val raw = prefs.getString(KEY_BUNDLE_GRANTS, null) ?: return out
        raw.lineSequence().forEach { line ->
            val parts = line.split('|', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) {
                val ids = parts[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                if (ids.isNotEmpty()) out[parts[0]] = ids
            }
        }
        return out
    }

    /**
     * Writes verified bundles plus any stored grant whose purchase is still in [keepTokens]
     * (a check that could not verify it yet). A token Play no longer returns is dropped, so a
     * refund locks those packs. Holds [lock].
     */
    private fun saveStoredGrantsLocked(keepTokens: Set<String>) {
        val kept = storedGrants.filterKeys { it in keepTokens && it !in bundleByToken }
        storedGrants.clear()
        storedGrants.putAll(bundleByToken)
        storedGrants.putAll(kept)
        val text = storedGrants.entries.joinToString("\n") { (token, ids) -> "$token|${ids.joinToString(",")}" }
        prefs.edit().putString(KEY_BUNDLE_GRANTS, text).apply()
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
        const val KEY_BUNDLE_HINT = "bundle_hint"
        const val KEY_BUNDLE_GRANTS = "bundle_grants"
        const val UNVERIFIABLE = "Purchases can't be verified in this build"
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 5L * 60 * 1000
        const val PRODUCTS_PER_QUERY = 20
        const val TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** [HttpAnswer.code] when the server could not be reached. */
        const val CODE_UNREACHABLE = -1

        /** [HttpAnswer.code] when the configured URL cannot be opened. */
        const val CODE_BAD_URL = -2
    }
}
