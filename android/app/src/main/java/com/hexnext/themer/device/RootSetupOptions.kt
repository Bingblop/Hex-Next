package com.hexnext.themer.device

/** Passive observations only. An installed manager or visible su file is not a granted session. */
data class RootSetupOptions(
    val privilegedConnection: Boolean,
    val suPresent: Boolean,
    val installedPackages: Set<String>,
) {
    val hasRootSetup: Boolean get() = privilegedConnection || suPresent
    val missingTools: List<RootTools.Tool> get() =
        if (hasRootSetup) emptyList() else RootTools.ALL.filter { it.packageName !in installedPackages }
    val offerRootRequest: Boolean get() = !privilegedConnection &&
        (suPresent || RootTools.KERNELSU.packageName in installedPackages)
    val offerOpenDirtyFrag: Boolean get() = !hasRootSetup && RootTools.DIRTYFRAG.packageName in installedPackages
}
