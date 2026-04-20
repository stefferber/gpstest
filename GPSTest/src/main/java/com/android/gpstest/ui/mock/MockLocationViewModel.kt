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

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.gpstest.library.data.MockLocationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.sqrt

private const val TAG = "MockLocationViewModel"

data class MockUiState(
    val isMocking: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val verticalAccuracy: Float? = null,
    val provider: String? = null,
    val injectedCount: Int = 0,
    val errorMessage: String? = null,
)

@HiltViewModel
class MockLocationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MockUiState())
    val uiState: StateFlow<MockUiState> = _uiState.asStateFlow()

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mockManager = MockLocationManager(context)
    private var nmeaListener: OnNmeaMessageListener? = null

    // Accuracy carried from the real GPS fix and updated by GST sentences.
    @Volatile private var cachedAccuracy = 5.0f
    @Volatile private var cachedVertAccuracy = 5.0f

    @SuppressLint("MissingPermission")
    fun startMocking() {
        viewModelScope.launch {
            // ── Step 1: capture a real GPS fix BEFORE addTestProvider ──────────────
            // After addTestProvider(GPS_PROVIDER), all location listeners only see
            // injected mock data. We must read the real accuracy now.
            val real = captureRealLocation()
            if (real != null) {
                cachedAccuracy = real.accuracy
                cachedVertAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    real.verticalAccuracyMeters else real.accuracy
                Log.d(TAG, "Seeded accuracy = $cachedAccuracy m from real GPS fix")
            } else {
                Log.w(TAG, "No real GPS fix within timeout — using fallback $cachedAccuracy m")
            }

            // ── Step 2: register the mock provider ────────────────────────────────
            val result = runCatching { mockManager.start() }
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Could not register mock provider.\n" +
                            "Go to Developer Options → Select mock location app → GPSTest.\n" +
                            "(${result.exceptionOrNull()?.message})"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(isMocking = true, errorMessage = null, injectedCount = 0)

            // ── Step 3: NMEA listener for continuous updates ──────────────────────
            // addNmeaListener reads directly from the GPS hardware chip and is NOT
            // affected by the mock provider layer, so real sentences keep arriving.
            val listener = OnNmeaMessageListener { message, _ ->
                // GST sentences carry the chip's own horizontal error estimate.
                parseGst(message)?.let { (h, v) ->
                    cachedAccuracy = h
                    cachedVertAccuracy = v
                }
                val loc = parseGga(message) ?: return@OnNmeaMessageListener
                val ok = mockManager.inject(loc)
                if (ok) {
                    val cur = _uiState.value
                    _uiState.value = cur.copy(
                        latitude      = loc.latitude,
                        longitude     = loc.longitude,
                        altitude      = loc.altitude,
                        accuracy      = loc.accuracy,
                        verticalAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            loc.verticalAccuracyMeters else loc.accuracy,
                        provider      = LocationManager.GPS_PROVIDER,
                        injectedCount = cur.injectedCount + 1,
                    )
                }
            }
            nmeaListener = listener
            locationManager.addNmeaListener(listener, Handler(Looper.getMainLooper()))
        }
    }

    fun stopMocking() {
        nmeaListener?.let { locationManager.removeNmeaListener(it) }
        nmeaListener = null
        mockManager.stop()
        _uiState.value = _uiState.value.copy(isMocking = false)
    }

    override fun onCleared() {
        super.onCleared()
        stopMocking()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Suspends until the next real GPS fix arrives (up to 5 s) by registering a
     * one-shot LocationListener directly on the LocationManager.
     * Must be called BEFORE [mockManager.start] so we get a non-mock location.
     */
    @SuppressLint("MissingPermission")
    private suspend fun captureRealLocation(): Location? = withTimeoutOrNull(5_000L) {
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
            }
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L, 0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.w(TAG, "captureRealLocation failed: $e")
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
        }
    }

    /** Parse lat/lon/alt from a \$GxGGA sentence, applying [cachedAccuracy]. */
    private fun parseGga(sentence: String): Location? {
        if (!sentence.contains("GGA")) return null
        val body = if ('*' in sentence) sentence.substringBefore('*') else sentence
        val f = body.split(",")
        if (f.size < 10) return null
        return try {
            if ((f[6].toIntOrNull() ?: 0) == 0) return null   // no fix
            val lat = nmeaToDd(f[2], f[3]) ?: return null
            val lon = nmeaToDd(f[4], f[5]) ?: return null
            val alt = f[9].toDoubleOrNull() ?: 0.0
            val sats = f[7].toIntOrNull() ?: 0
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude  = lat
                longitude = lon
                altitude  = alt
                accuracy  = cachedAccuracy
                time      = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = cachedVertAccuracy
                }
                extras = Bundle().apply { putInt("satellites", sats) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GGA parse error: $e")
            null
        }
    }

    /**
     * Parse horizontal and vertical accuracy from a \$GxGST sentence.
     * Returns Pair(horizontal, vertical) in metres, or null if not parseable.
     */
    private fun parseGst(sentence: String): Pair<Float, Float>? {
        if (!sentence.contains("GST")) return null
        val body = if ('*' in sentence) sentence.substringBefore('*') else sentence
        val f = body.split(",")
        if (f.size < 8) return null
        return try {
            val latErr = f[6].toFloatOrNull() ?: return null
            val lonErr = f[7].toFloatOrNull() ?: return null
            val horiz = sqrt(latErr * latErr + lonErr * lonErr).coerceAtLeast(0.01f)
            // Field index 5 is altitude std-dev; use it as vertical accuracy if present
            val vert  = f[5].toFloatOrNull()?.coerceAtLeast(0.01f) ?: horiz
            Pair(horiz, vert)
        } catch (e: Exception) { null }
    }

    private fun nmeaToDd(raw: String, dir: String): Double? {
        val v = raw.toDoubleOrNull() ?: return null
        val deg = (v / 100).toInt()
        val min = v - deg * 100
        var dd = deg + min / 60.0
        if (dir == "S" || dir == "W") dd = -dd
        return dd
    }
}
