package com.hexnext.themer.shizuku

import org.junit.Assert.*
import org.junit.Test

class PrivilegeModeTest {
    @Test fun systemAccessWinsRegardlessOfProviderOrCandidateOrder() {
        assertEquals(1000, PrivilegeMode.preferredUid(0, 1000))
        assertEquals(1000, PrivilegeMode.preferredUid(1000, 0))
        assertEquals(1000, PrivilegeMode.preferredUid(2000, 1000))
        assertEquals(1000, PrivilegeMode.preferredUid(null, 1000))
    }

    @Test fun realRootWinsOverOrdinaryShell() {
        assertEquals(0, PrivilegeMode.preferredUid(2000, 0))
        assertEquals(0, PrivilegeMode.preferredUid(0, null))
        assertEquals(PrivilegeMode.ROOT_FALLBACK, PrivilegeMode.fromUid(0))
        assertTrue(PrivilegeMode.ROOT_FALLBACK.overlays)
        assertFalse(PrivilegeMode.ROOT_FALLBACK.survivesReboot)
    }

    @Test fun shellIsMonetOnlyNotRoot() {
        assertEquals(2000, PrivilegeMode.preferredUid(null, 2000))
        assertEquals(PrivilegeMode.MONET_ONLY, PrivilegeMode.fromUid(2000))
        assertFalse(PrivilegeMode.MONET_ONLY.overlays)
        assertFalse(PrivilegeMode.MONET_ONLY.survivesReboot)
    }

    @Test fun appUidsAndUnknownUidsAreNeverPrivileged() {
        for (uid in listOf(-1, 1, 1001, 10000, 10123)) {
            assertEquals(PrivilegeMode.NONE, PrivilegeMode.fromUid(uid))
            assertNull(PrivilegeMode.preferredUid(uid))
        }
        assertNull(PrivilegeMode.preferredUid(null, null))
    }

    @Test fun onlySystemModePromisesOverlayPersistence() {
        assertEquals(PrivilegeMode.SYSTEM, PrivilegeMode.fromUid(1000))
        assertTrue(PrivilegeMode.SYSTEM.survivesReboot)
        assertTrue(PrivilegeMode.SYSTEM.overlays)
        assertEquals(listOf(PrivilegeMode.SYSTEM), PrivilegeMode.entries.filter { it.survivesReboot })
    }
}
