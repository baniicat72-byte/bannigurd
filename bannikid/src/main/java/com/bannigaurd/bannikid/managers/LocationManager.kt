package com.bannigaurd.bannikid.managers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.DatabaseReference

data class LocationData(val latitude: Double = 0.0, val longitude: Double = 0.0, val timestamp: Long = 0) {
    constructor() : this(0.0, 0.0, 0)
}

class LocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun sync(deviceId: String, dbRef: DatabaseReference) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("LocationManager", "Location permission not granted.")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val locationData = LocationData(location.latitude, location.longitude, System.currentTimeMillis())
                dbRef.child("locations").child(deviceId).child("liveLocation").setValue(locationData)
                dbRef.child("locations").child(deviceId).child("history").push().setValue(locationData)
            } else {
                Log.w("LocationManager", "Last location is null.")
            }
        }.addOnFailureListener { e ->
            Log.e("LocationManager", "Failed to get location", e)
        }
    }
}