package com.hexnext.themer.billing

import android.content.Context

object EntitlementsFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): PackEntitlements = OwnerEntitlements
}
