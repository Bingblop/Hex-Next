package com.hexnext.themer.device

/** Metadata only: the user reviews upstream releases and downloads/installs through Android. */
object RootTools {
    data class Tool(val name: String, val repo: String, val packageName: String) {
        val releasesUrl: String get() = "https://github.com/$repo/releases/latest"
    }

    val DIRTYFRAG: Tool = Tool(
        "DirtyFrag",
        "mitschud/DirtyFrag",
        "df.root",
    )

    val KERNELSU: Tool = Tool(
        "KernelSU",
        "diabl0w/KernelSU",
        "me.weishu.kernelsu",
    )

    val ALL: List<Tool> = listOf(DIRTYFRAG, KERNELSU)

}
