package com.hexnext.themer.billing

/**
 * Purchases the Play billing listener granted while a full `queryPurchasesAsync` was in flight.
 *
 * A full answer replaces owned products with what that snapshot verified. A grant recorded after
 * the snapshot was taken is kept, because the snapshot cannot contain it yet. [Snapshot.unconfirmed]
 * is that remainder: the caller asks Play once more, and a grant the later snapshot still does
 * not list is dropped. That is how a purchase completed and refunded inside one in-flight check
 * does not stay unlocked until the next resume.
 */
internal class InFlightGrants {
    private val at = HashMap<String, Long>()

    /** How many listener answers have been recorded. A full query captures this when it starts. */
    var sequence: Long = 0
        private set

    /** Records one listener answer. [products] may be empty; the sequence still moves forward. */
    fun note(products: Collection<String>) {
        sequence += 1
        for (product in products) at[product] = sequence
    }

    /**
     * Drops grants at or before [since] and returns what a full snapshot should add on top of
     * [snapshotProducts]. [Snapshot.unconfirmed] needs another query.
     */
    fun applySnapshot(since: Long, snapshotProducts: Set<String>): Snapshot {
        val iterator = at.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= since) iterator.remove()
        }
        val kept = at.keys.toSet()
        return Snapshot(kept = kept, unconfirmed = kept - snapshotProducts)
    }

    /** [kept] stays owned alongside the snapshot. [unconfirmed] was not in the snapshot. */
    internal data class Snapshot(val kept: Set<String>, val unconfirmed: Set<String>)
}
