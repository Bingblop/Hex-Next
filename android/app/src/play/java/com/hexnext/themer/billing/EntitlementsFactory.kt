package com.hexnext.themer.billing

import android.content.Context

object EntitlementsFactory {
    fun create(context: Context): PackEntitlements = PlayEntitlements(context.applicationContext)
}
