package com.hexnext.themer.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The owner build: every pack, including packs added later, is unlocked and nothing is sold. */
internal object OwnerEntitlements : PackEntitlements {
    private val unlocked = MutableStateFlow(EntitlementState(ready = true, unlockAll = true))

    override val state: StateFlow<EntitlementState> = unlocked.asStateFlow()

    override val canPurchase: Boolean = false

    override fun start() = Unit

    override fun refresh() = Unit

    override fun isUnlocked(packId: String): Boolean = true

    override fun launchPurchase(activity: Activity, productId: String) = Unit

    override fun productIdFor(packId: String): String = ProductIds.forPack(packId)

    override fun setCatalogue(packIds: Collection<String>) = Unit
}
