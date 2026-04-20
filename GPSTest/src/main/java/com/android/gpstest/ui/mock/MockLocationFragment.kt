/*
 * Copyright (C) 2024 GPSTest Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.gpstest.ui.mock

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.android.gpstest.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MockLocationFragment : Fragment() {

    companion object {
        const val TAG = "MockLocationFragment"
    }

    private val viewModel: MockLocationViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            AppTheme(darkTheme = isSystemInDarkTheme()) {
                MockLocationScreen(viewModel)
            }
        }
    }
}

@Composable
private fun MockLocationScreen(viewModel: MockLocationViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Mock Location",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Re-publishes GPSTest's GPS position to all other apps via the Android mock location API.",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))

        // Setup card — shown only when not yet mocking
        if (!state.isMocking) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "One-time setup required",
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "1. Enable Developer Options\n" +
                        "   (Settings → About phone → tap Build number 7 times)\n" +
                        "2. Developer Options → Select mock location app → GPSTest",
                        style = MaterialTheme.typography.caption,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Developer Options")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Toggle card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.isMocking) "Active" else "Inactive",
                        style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.Bold,
                        color = if (state.isMocking)
                            MaterialTheme.colors.primary
                        else
                            MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        text = if (state.isMocking)
                            "${state.injectedCount} fixes sent"
                        else
                            "Toggle to start publishing",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = state.isMocking,
                    onCheckedChange = { on ->
                        if (on) viewModel.startMocking() else viewModel.stopMocking()
                    },
                )
            }
        }

        // Live data card — shown while mocking and data is available
        if (state.isMocking && state.latitude != null) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Live feed",
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    InfoRow("Provider",   state.provider ?: "gps")
                    InfoRow("Latitude",   "%.8f°".format(state.latitude))
                    InfoRow("Longitude",  "%.8f°".format(state.longitude))
                    state.altitude?.let   { InfoRow("Altitude",  "%.1f m".format(it)) }
                    state.accuracy?.let   { InfoRow("H-Accuracy","%.2f m".format(it)) }
                    state.verticalAccuracy?.let { InfoRow("V-Accuracy","%.2f m".format(it)) }
                }
            }
        }

        // Error message
        state.errorMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = msg,
                color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(100.dp),
        )
        Text(text = value, style = MaterialTheme.typography.caption)
    }
}
