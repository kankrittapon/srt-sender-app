package com.example.srtsender

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class LoginActivity : AppCompatActivity() {

    private lateinit var etBoatId: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView

    // Device info to pass to MainActivity
    private var deviceRole: String = "racing_boat"
    private var hasVideo: Boolean = true
    private var hasGps: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etBoatId = findViewById(R.id.etBoatId)
        btnLogin = findViewById(R.id.btnLogin)
        tvStatus = findViewById(R.id.tvStatus)

        btnLogin.setOnClickListener {
            val boatId = etBoatId.text.toString().trim().uppercase()
            if (boatId.isNotEmpty()) {
                checkLogin(boatId)
            } else {
                tvStatus.text = "Please enter Boat ID"
            }
        }
    }

    private fun checkLogin(boatId: String) {
        tvStatus.text = "Checking..."
        btnLogin.isEnabled = false

        // Get Android ID (Device ID)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        Log.d("LoginActivity", "Android ID: $androidId")

        val db = FirebaseDatabase.getInstance()
        val deviceRef = db.getReference("devices/$boatId")

        deviceRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    tvStatus.text = "❌ Invalid Boat ID\n(Not registered by Admin)"
                    btnLogin.isEnabled = true
                    return
                }

                // Read device info
                deviceRole = snapshot.child("role").getValue(String::class.java) ?: "racing_boat"
                hasVideo = snapshot.child("hasVideo").getValue(Boolean::class.java) ?: true
                hasGps = snapshot.child("hasGps").getValue(Boolean::class.java) ?: true

                val boundDeviceId = snapshot.child("deviceId").getValue(String::class.java)

                if (boundDeviceId.isNullOrEmpty()) {
                    // First time login - Bind this device
                    deviceRef.child("deviceId").setValue(androidId).addOnSuccessListener {
                        Log.i("LoginActivity", "Device bound successfully")
                        proceedToMain(boatId)
                    }.addOnFailureListener {
                        tvStatus.text = "Failed to bind device: ${it.message}"
                        btnLogin.isEnabled = true
                    }
                } else {
                    // Already bound - Check if it matches
                    if (boundDeviceId == androidId) {
                        Log.i("LoginActivity", "Device ID matched")
                        proceedToMain(boatId)
                    } else {
                        tvStatus.text = "🚫 Access Denied\nThis Boat ID is bound to another device."
                        btnLogin.isEnabled = true
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                tvStatus.text = "Error: ${error.message}"
                btnLogin.isEnabled = true
            }
        })
    }

    private fun proceedToMain(boatId: String) {
        // Fetch Server Config (URL and Port)
        val configRef = FirebaseDatabase.getInstance().getReference("config")
        configRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var serverIp = "192.168.1.1" // Default fallback
                var serverPort = 9000 // Default SRT port
                var srtLatency = 200 // Default latency 200ms
                
                // Read Server URL
                val url = snapshot.child("serverUrl").getValue(String::class.java)
                if (url != null) {
                    try {
                        // Extract Host (supports URL or plain hostname)
                        serverIp = if (url.startsWith("http")) {
                            java.net.URL(url).host
                        } else {
                            url.split(":")[0]
                        }
                    } catch (e: Exception) {
                        Log.e("LoginActivity", "Error parsing server IP", e)
                        serverIp = url
                    }
                }
                
                // Read SRT Port (if set by Admin)
                val portVal = snapshot.child("srtPort").getValue(Long::class.java)
                if (portVal != null) {
                    serverPort = portVal.toInt()
                }
                
                // Read SRT Latency (if set by Admin)
                val latencyVal = snapshot.child("srtLatency").getValue(Long::class.java)
                if (latencyVal != null) {
                    srtLatency = latencyVal.toInt()
                }
                
                Log.d("LoginActivity", "Config loaded: IP=$serverIp, Port=$serverPort, Latency=${srtLatency}ms")

                // Go to Main with device info
                val intent = Intent(this@LoginActivity, MainActivity::class.java)
                intent.putExtra("BOAT_ID", boatId)
                intent.putExtra("SERVER_IP", serverIp)
                intent.putExtra("SERVER_PORT", serverPort)
                intent.putExtra("SRT_LATENCY", srtLatency)
                intent.putExtra("DEVICE_ROLE", deviceRole)
                intent.putExtra("HAS_VIDEO", hasVideo)
                intent.putExtra("HAS_GPS", hasGps)
                startActivity(intent)
                finish()
            }

            override fun onCancelled(error: DatabaseError) {
                tvStatus.text = "Failed to fetch config"
                btnLogin.isEnabled = true
            }
        })
    }
}

