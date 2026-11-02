package com.hexnext.themer.billing

import android.content.Context

/**
 * The one [PackEntitlements] of the process, made by this flavor's [EntitlementsFactory] the
 * first time it is needed and started at once. A process-wide instance keeps a single store
 * connection across activity re-creations.
 */
object EntitlementsProvider {
    @Volatile
    private var instance: PackEntitlements? = null

    fun get(context: Context): PackEntitlements =
        instance ?: synchronized(this) {
            instance ?: EntitlementsFactory.create(context.applicationContext).also {
                instance = it
                it.start()
            }
        }
}
