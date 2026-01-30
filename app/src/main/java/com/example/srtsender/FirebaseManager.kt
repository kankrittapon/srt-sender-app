package com.example.srtsender

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.net.Inet4Address
import java.net.NetworkInterface

class FirebaseManager(private val boatId: String) {

    private val db = FirebaseDatabase.getInstance()
    private val deviceRef = db.getReference("devices").child(boatId)
    private val TAG = "FirebaseManager"

    fun setOnline() {
        val updates = hashMapOf<String, Any>(
            "id" to boatId,
            "status" to "online",
            "lastLoginAt" to ServerValue.TIMESTAMP,
            "lastLoginIp" to getIpAddress(),
            "updatedAt" to ServerValue.TIMESTAMP
        )
        
        deviceRef.updateChildren(updates)
            .addOnSuccessListener { Log.d(TAG, "Device status online") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to update status", e) }

        // Set offline on disconnect
        deviceRef.child("status").onDisconnect().setValue("offline")
        deviceRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
    }

    fun setOffline() {
        val updates = hashMapOf<String, Any>(
            "status" to "offline",
            "lastSeen" to ServerValue.TIMESTAMP
        )
        deviceRef.updateChildren(updates)
    }

    private fun getIpAddress(): String {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "IP Error", ex)
        }
        return "unknown"
    }
}
