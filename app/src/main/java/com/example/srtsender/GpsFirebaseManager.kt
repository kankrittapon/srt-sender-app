package com.example.srtsender

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase

/**
 * GPS Manager that sends location updates to Firebase RTDB
 * Replaces MQTT due to Android 14+ compatibility issues
 */
class GpsFirebaseManager(private val context: Context, private val boatId: String) {

    private var locationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private val database = FirebaseDatabase.getInstance()
    private val TAG = "GpsFirebaseManager"

    init {
        initLocation()
    }

    private fun initLocation() {
        locationClient = LocationServices.getFusedLocationProviderClient(context)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    publishLocation(location)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates() {
        if (locationClient == null || locationCallback == null) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(2000)
            .build()

        locationClient?.requestLocationUpdates(request, locationCallback!!, android.os.Looper.getMainLooper())
        Log.d(TAG, "Location updates started for $boatId")
    }

    fun stopUpdates() {
        locationCallback?.let { locationClient?.removeLocationUpdates(it) }
        Log.d(TAG, "Location updates stopped")
    }

    private fun publishLocation(location: Location) {
        val speedKnots = location.speed * 1.94384 // m/s to knots
        
        val locationData = mapOf(
            "lat" to location.latitude,
            "lon" to location.longitude,
            "speed" to speedKnots,
            "accuracy" to location.accuracy,
            "timestamp" to System.currentTimeMillis()
        )
        
        // Update device location in Firebase RTDB
        database.getReference("devices/$boatId/location").setValue(locationData)
            .addOnSuccessListener {
                Log.d(TAG, "GPS Published: lat=${location.latitude}, lon=${location.longitude}, speed=${"%.2f".format(speedKnots)} knots")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to publish GPS", e)
            }
    }
}
