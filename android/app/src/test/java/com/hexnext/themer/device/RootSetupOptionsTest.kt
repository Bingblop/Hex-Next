package com.hexnext.themer.device

import org.junit.Assert.*
import org.junit.Test

class RootSetupOptionsTest {
    @Test fun unrootedWithoutHelpersOffersBothDownloads() {
        val options = RootSetupOptions(false, false, emptySet())
        assertEquals(RootTools.ALL, options.missingTools)
        assertFalse(options.offerRootRequest)
        assertFalse(options.offerOpenDirtyFrag)
    }

    @Test fun installedKernelSuIsNotTreatedAsGrantedRoot() {
        val options = RootSetupOptions(false, false, setOf(RootTools.KERNELSU.packageName))
        assertFalse(options.hasRootSetup)
        assertTrue(options.offerRootRequest)
        assertEquals(listOf(RootTools.DIRTYFRAG), options.missingTools)
    }

    @Test fun installedDirtyFragOnlyOffersMissingManager() {
        val options = RootSetupOptions(false, false, setOf(RootTools.DIRTYFRAG.packageName))
        assertEquals(listOf(RootTools.KERNELSU), options.missingTools)
        assertTrue(options.offerOpenDirtyFrag)
        assertFalse(options.offerRootRequest)
    }

    @Test fun bothInstalledOffersAccessWithoutRedownload() {
        val options = RootSetupOptions(false, false, RootTools.ALL.mapTo(hashSetOf()) { it.packageName })
        assertTrue(options.missingTools.isEmpty())
        assertTrue(options.offerOpenDirtyFrag)
        assertTrue(options.offerRootRequest)
        assertFalse(options.privilegedConnection)
    }

    @Test fun grantedConnectionNeedsNoRootingOrNewPermissionPrompt() {
        val options = RootSetupOptions(true, false, emptySet())
        assertTrue(options.hasRootSetup)
        assertTrue(options.missingTools.isEmpty())
        assertFalse(options.offerRootRequest)
        assertFalse(options.offerOpenDirtyFrag)
    }

    @Test fun existingSuNeedsAccessNotNewRootTools() {
        val options = RootSetupOptions(false, true, emptySet())
        assertTrue(options.missingTools.isEmpty())
        assertTrue(options.offerRootRequest)
        assertFalse(options.offerOpenDirtyFrag)
    }

    @Test fun policyCoversEveryObservationCombination() {
        for (mask in 0 until 16) {
            val connected = mask and 1 != 0
            val su = mask and 2 != 0
            val installed = buildSet {
                if (mask and 4 != 0) add(RootTools.DIRTYFRAG.packageName)
                if (mask and 8 != 0) add(RootTools.KERNELSU.packageName)
            }
            val options = RootSetupOptions(connected, su, installed)
            for (tool in options.missingTools) {
                assertFalse(connected)
                assertFalse(su)
                assertFalse(tool.packageName in installed)
            }
            assertEquals(!connected && (su || RootTools.KERNELSU.packageName in installed), options.offerRootRequest)
        }
    }

    @Test fun linksAreManualUpstreamReleasePagesNotUnverifiedApkAssets() {
        assertEquals("https://github.com/mitschud/DirtyFrag/releases/latest", RootTools.DIRTYFRAG.releasesUrl)
        assertEquals("https://github.com/diabl0w/KernelSU/releases/latest", RootTools.KERNELSU.releasesUrl)
        assertEquals("df.root", RootTools.DIRTYFRAG.packageName)
        assertEquals("me.weishu.kernelsu", RootTools.KERNELSU.packageName)
    }
}
