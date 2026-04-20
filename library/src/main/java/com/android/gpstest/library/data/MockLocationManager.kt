/*
 * Copyright (C) 2024 GPSTest Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.gpstest.library.data

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

private const val TAG = "MockLocationManager"

/**
 * Wraps Android's test-provider API so GPSTest can re-publish its own GPS fix
 * to all other apps via the mock location system.
 *
 * Prerequisites: the user must select GPSTest as the Mock location app in
 * Developer Options → Select mock location app, otherwise [start] throws SecurityException.
 */
class MockLocationManager(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Register the mock GPS provider. Throws [SecurityException] if app is not selected. */
    fun start() {
        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            /* requiresNetwork   */ false,
            /* requiresSatellite */ true,
            /* requiresCell      */ false,
            /* hasMonetaryCost   */ false,
            /* supportsAltitude  */ true,
            /* supportsSpeed     */ true,
            /* supportsBearing   */ true,
            /* powerRequirement  */ Criteria.POWER_HIGH,
            /* accuracy          */ Criteria.ACCURACY_FINE,
        )
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        Log.d(TAG, "Mock GPS provider started")
    }

    /** Inject [location] into the Android location system. Returns true on success. */
    fun inject(location: Location): Boolean = runCatching {
        val mock = Location(LocationManager.GPS_PROVIDER).apply {
            latitude  = location.latitude
            longitude = location.longitude
            altitude  = location.altitude
            accuracy  = location.accuracy
            time      = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = location.verticalAccuracyMeters
            }
            if (location.hasSpeed())   speed   = location.speed
            if (location.hasBearing()) bearing = location.bearing
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mock)
        true
    }.getOrElse { e ->
        Log.w(TAG, "inject failed: $e")
        false
    }

    /** Deregister the mock provider. Safe to call even if [start] was never called. */
    fun stop() {
        runCatching {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            Log.d(TAG, "Mock GPS provider stopped")
        }
    }
}
