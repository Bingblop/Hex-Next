package com.hexnext.themer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hexnext.themer.billing.EntitlementState
import com.hexnext.themer.billing.PackAccess
import com.hexnext.themer.billing.ProductIds

/**
 * What the pack lists show about unlocks. Every theme pack is a one-time purchase; the view model
 * enforces the same rules, so this only decides what is drawn.
 */
internal data class PackLocks(
    val entitlement: EntitlementState,
    val bundled: Set<String>,
    val official: Set<String>,
    val canPurchase: Boolean,
) {
    /** True when pack [id] shows a lock (the cached purchase hint counts until the store answers). */
    fun locked(id: String): Boolean = !PackAccess.looksUnlocked(entitlement, id, bundled, official)

    fun pending(id: String): Boolean = entitlement.isPending(id)

    fun price(id: String): String? = entitlement.priceOf(id)

    val allPacksPrice: String? get() = entitlement.prices[ProductIds.ALL_PACKS]

    /** True when "Unlock all packs" is on offer: a store build without it. */
    val offerUnlockAll: Boolean get() = canPurchase && !entitlement.unlockAll

    companion object {
        fun of(ui: UiState) = PackLocks(ui.entitlement, ui.bundledPackIds, ui.officialPackIds, ui.canPurchase)
    }
}

/**
 * "Unlock · $0.99": starts the store purchase. The price comes from the store and the button stays
 * off until it is known (or while [pending], which reads "Payment pending").
 */
@Composable
internal fun UnlockButton(
    name: String,
    price: String?,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String = "Unlock",
) {
    val text = when {
        pending -> "Payment pending"
        price != null -> "$label · $price"
        else -> label
    }
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && price != null && !pending,
        modifier = Modifier.semantics { contentDescription = if (pending) "Payment pending for $name" else "$text, $name" },
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/** The "Unlock all packs" row: every pack, including packs added later, for one purchase. */
@Composable
internal fun UnlockAllRow(locks: PackLocks, busy: Boolean, onUnlock: (String) -> Unit) {
    if (!locks.offerUnlockAll) return
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Unlock all packs", style = MaterialTheme.typography.titleSmall)
            Text(
                "Every pack, including packs added later. One-time purchase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UnlockButton(
            name = "all packs",
            price = locks.allPacksPrice,
            pending = ProductIds.ALL_PACKS in locks.entitlement.pendingProductIds,
            enabled = !busy,
            onClick = { onUnlock(ProductIds.ALL_PACKS) },
            label = "Buy",
        )
    }
}

/** "Locked" (with [price] when known) and a lock icon, for a pack row whose pack is not unlocked. */
@Composable
internal fun LockedLabel(pending: Boolean, price: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when {
                pending -> "Locked · payment pending"
                price != null -> "Locked · $price"
                else -> "Locked"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
