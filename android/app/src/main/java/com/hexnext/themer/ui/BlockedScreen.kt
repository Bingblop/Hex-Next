package com.hexnext.themer.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hexnext.themer.device.Compatibility
import com.hexnext.themer.device.CompatibilityCheck

/**
 * Shown instead of [MainScreen] when [CompatibilityCheck.check] is not [Compatibility.Supported]:
 * one card saying why, and for too-old One UI devices a button to the old Hex Installer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(compat: Compatibility) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = { LargeTopAppBar(title = { Text("Hex Next", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Not supported on this device") {
                    when (compat) {
                        is Compatibility.TooOld -> {
                            Text(
                                "Hex Next needs Android 15 or later and One UI 7 or later. This device runs " +
                                    "Android ${Build.VERSION.RELEASE} / ${compat.oneUi ?: "an unknown One UI version"}. " +
                                    "The old Hex Installer supports earlier versions.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { uriHandler.openUri(CompatibilityCheck.HEX_INSTALLER_URL) }) {
                                Text("Get Hex Installer")
                            }
                        }
                        else -> Text(
                            "Hex Next themes Samsung One UI only. This device is not running One UI.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
