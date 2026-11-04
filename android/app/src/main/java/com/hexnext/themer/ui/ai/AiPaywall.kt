package com.hexnext.themer.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.billing.EntitlementState
import com.hexnext.themer.billing.EntitlementsProvider
import com.hexnext.themer.billing.PackEntitlements
import com.hexnext.themer.billing.PaidFeature
import com.hexnext.themer.billing.ProductIds
import com.hexnext.themer.ui.findActivity

/*
 * The paywall in front of AI creation ([PaidFeature.AI_CREATION]): "Describe your theme" and AI
 * icon generation. Three ways in, all through Google Play: a monthly subscription, a one-time
 * forever unlock of AI creation, and a one-time unlock of everything (every pack and every paid
 * feature, including ones added later). Prices always come from Play, never from this file.
 */

/** The process-wide entitlements, for composables that gate a paid feature themselves. */
@Composable
internal fun rememberEntitlements(): PackEntitlements {
    val context = LocalContext.current
    return remember { EntitlementsProvider.get(context) }
}

/** The live [EntitlementState] for gating; recomposes when a purchase lands or lapses. */
@Composable
internal fun rememberEntitlementState(): EntitlementState {
    val entitlements = rememberEntitlements()
    val state by entitlements.state.collectAsStateWithLifecycle()
    return state
}

/**
 * The locked state of an AI feature: what the subscription gives, the three Play products with
 * their prices, and Restore purchases. While the store has not answered yet but the cached hint
 * says AI was bought, a short "checking" line shows instead, so a subscriber never sees a paywall
 * flash on start.
 *
 * @param what the feature in the user's words, such as "Describe your theme".
 */
@Composable
internal fun AiPaywall(what: String, state: EntitlementState, modifier: Modifier = Modifier) {
    val entitlements = rememberEntitlements()
    val context = LocalContext.current
    val feature = PaidFeature.AI_CREATION

    fun buy(productId: String) {
        val activity = context.findActivity() ?: return
        entitlements.launchPurchase(activity, productId)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.looksUnlocked(feature) || (state.checking && !state.ready)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Checking your purchases…", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        Text(
            "$what is part of AI theme creation. It uses your own AI key, so prompts go straight from this phone to the provider you chose.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isPending(feature)) {
            Text(
                "Your payment is pending. AI theme creation unlocks as soon as Google Play confirms it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (!entitlements.canPurchase) {
            Text("Purchases aren't available in this build.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val monthly = state.prices[ProductIds.AI_MONTHLY]
        val lifetime = state.prices[ProductIds.AI_LIFETIME]
        val everything = state.prices[ProductIds.EVERYTHING]
        Button(
            onClick = { buy(ProductIds.AI_MONTHLY) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (monthly != null) "Subscribe · $monthly / month" else "Subscribe monthly") }
        FilledTonalButton(
            onClick = { buy(ProductIds.AI_LIFETIME) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (lifetime != null) "AI forever · $lifetime once" else "Unlock AI forever") }
        OutlinedButton(
            onClick = { buy(ProductIds.EVERYTHING) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (everything != null) "Everything forever · $everything once" else "Unlock everything forever") }
        Text(
            "Everything forever unlocks AI theme creation and every pack, including packs and features added later. The subscription renews monthly until you cancel it in Google Play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { entitlements.refresh() }) { Text("Restore purchases") }
    }
}
