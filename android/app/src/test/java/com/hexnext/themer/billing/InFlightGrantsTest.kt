package com.hexnext.themer.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightGrantsTest {
    @Test
    fun aGrantInsideTheSnapshotNeedsNoFurtherCheck() {
        val grants = InFlightGrants()
        val started = grants.sequence
        grants.note(setOf("pack_neon"))
        val snapshot = grants.applySnapshot(started, setOf("pack_neon"))
        assertEquals(setOf("pack_neon"), snapshot.kept)
        assertTrue(snapshot.unconfirmed.isEmpty())
    }

    @Test
    fun aGrantTheSnapshotMissedIsKeptAndMarkedForAnotherCheck() {
        val grants = InFlightGrants()
        val started = grants.sequence
        grants.note(setOf("pack_neon"))
        val snapshot = grants.applySnapshot(started, emptySet())
        assertEquals(setOf("pack_neon"), snapshot.kept)
        assertEquals(setOf("pack_neon"), snapshot.unconfirmed)
    }

    @Test
    fun aLaterSnapshotThatStillOmitsTheGrantDropsIt() {
        val grants = InFlightGrants()
        val started = grants.sequence
        grants.note(setOf("pack_neon"))
        assertEquals(setOf("pack_neon"), grants.applySnapshot(started, emptySet()).unconfirmed)

        // The follow-up query starts after the grant, and Play no longer lists the purchase.
        val followUp = grants.applySnapshot(grants.sequence, emptySet())
        assertTrue(followUp.kept.isEmpty())
        assertTrue(followUp.unconfirmed.isEmpty())
    }

    @Test
    fun aLaterSnapshotThatContainsTheGrantLeavesItToTheSnapshot() {
        val grants = InFlightGrants()
        grants.note(setOf("pack_neon"))
        grants.applySnapshot(0, emptySet())
        val followUp = grants.applySnapshot(grants.sequence, setOf("pack_neon"))
        assertTrue(followUp.kept.isEmpty())
        assertTrue(followUp.unconfirmed.isEmpty())
    }

    @Test
    fun aGrantFromBeforeTheSnapshotDoesNotOutliveIt() {
        val grants = InFlightGrants()
        grants.note(setOf("pack_old"))
        val snapshot = grants.applySnapshot(grants.sequence, emptySet())
        assertTrue(snapshot.kept.isEmpty())
        assertTrue(snapshot.unconfirmed.isEmpty())
    }

    @Test
    fun anEmptyListenerAnswerStillMovesTheSequence() {
        val grants = InFlightGrants()
        grants.note(setOf("pack_neon"))
        grants.note(emptyList())
        val snapshot = grants.applySnapshot(grants.sequence, emptySet())
        assertTrue(snapshot.kept.isEmpty())
    }
}
