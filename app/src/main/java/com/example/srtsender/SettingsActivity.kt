package com.example.srtsender

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class SettingsActivity : AppCompatActivity() {

    private lateinit var menuDeviceInfo: android.widget.LinearLayout
    private lateinit var menuNetwork: android.widget.LinearLayout
    private lateinit var menuConfig: android.widget.LinearLayout
    private lateinit var menuAccount: android.widget.LinearLayout
    private lateinit var menuAbout: android.widget.LinearLayout
    private lateinit var textVersion: TextView
    private lateinit var btnBack: Button

    private var boatId: String = ""
    private var deviceRole: String = ""
    private var hasGps: Boolean = false
    private var isConnectedToSrt: Boolean = false

    private val database = FirebaseDatabase.getInstance()

    // Dialog References to update dynamically
    private var networkDialog: AlertDialog? = null
    private var networkDialogView: android.view.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Get data from intent
        boatId = intent.getStringExtra("boatId") ?: ""
        deviceRole = intent.getStringExtra("deviceRole") ?: "unknown"
        hasGps = intent.getBooleanExtra("hasGps", false)
        isConnectedToSrt = intent.getBooleanExtra("isConnectedToSrt", false)

        initViews()
        setupListeners()
        
        // Start listening to Firebase for status updates (in case dialog is open)
        listenFirebaseConnection()
    }

    private fun initViews() {
        menuDeviceInfo = findViewById(R.id.menuDeviceInfo)
        menuNetwork = findViewById(R.id.menuNetwork)
        menuConfig = findViewById(R.id.menuConfig)
        menuAccount = findViewById(R.id.menuAccount)
        menuAbout = findViewById(R.id.menuAbout)
        textVersion = findViewById(R.id.textVersion)
        btnBack = findViewById(R.id.btnBack)

        // Display current version in menu
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            textVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            textVersion.text = "Version Unknown"
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        // 1. Device Info
        menuDeviceInfo.setOnClickListener { showDeviceInfoDialog() }

        // 2. Network Status
        menuNetwork.setOnClickListener { showNetworkStatusDialog() }

        // 3. Configuration
        menuConfig.setOnClickListener { showConfigDialog() }

        // 4. Account
        menuAccount.setOnClickListener { showAccountDialog() }

        // 5. About
        menuAbout.setOnClickListener { showAboutDialog() }
    }

    private fun showDeviceInfoDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_device_info, null)
        val tvBoatId = view.findViewById<TextView>(R.id.dialogTextBoatId)
        val tvRole = view.findViewById<TextView>(R.id.dialogTextRole)

        tvBoatId.text = boatId.ifEmpty { "---" }
        tvRole.text = getRoleLabel(deviceRole)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showNetworkStatusDialog() {
        networkDialogView = layoutInflater.inflate(R.layout.dialog_network_status, null)
        refreshNetworkStatusUI() // Update UI immediately

        networkDialog = AlertDialog.Builder(this)
            .setView(networkDialogView)
            .setPositiveButton("Close", null)
            .show()
        
        networkDialog?.setOnDismissListener {
            networkDialog = null
            networkDialogView = null
        }
    }

    private fun refreshNetworkStatusUI() {
        val view = networkDialogView ?: return
        
        val tvSrt = view.findViewById<TextView>(R.id.dialogStatusSrt)
        val tvFirebase = view.findViewById<TextView>(R.id.dialogStatusFirebase)
        val tvGps = view.findViewById<TextView>(R.id.dialogStatusGps)

        // SRT Status
        if (isConnectedToSrt) {
            tvSrt.text = "🟢 Connected"
            tvSrt.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvSrt.text = "🔴 Disconnected"
            tvSrt.setTextColor(0xFFFF6B6B.toInt())
        }

        // GPS Status
        if (hasGps) {
            tvGps.text = "🟢 Active"
            tvGps.setTextColor(0xFF4CAF50.toInt())
        } else {
            tvGps.text = "⚪ Inactive"
            tvGps.setTextColor(0xFF888888.toInt())
        }

        // Firebase Status (Updated via Listener)
        // logic is handled in listenFirebaseConnection which refreshes if dialog is open
    }

    private fun showConfigDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_configuration, null)
        val switchModule = view.findViewById<Switch>(R.id.dialogSwitchModuleMode)
        val switchAuto = view.findViewById<Switch>(R.id.dialogSwitchAutoStream)

        val prefs = getSharedPreferences("SrtSenderPrefs", Context.MODE_PRIVATE)
        switchModule.isChecked = prefs.getBoolean(BootReceiver.PREF_MODULE_MODE, false)
        switchAuto.isChecked = prefs.getBoolean(BootReceiver.PREF_AUTO_STREAM, false)
        switchAuto.isEnabled = switchModule.isChecked

        switchModule.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(BootReceiver.PREF_MODULE_MODE, isChecked).apply()
            switchAuto.isEnabled = isChecked
            if (!isChecked) {
                switchAuto.isChecked = false
                prefs.edit().putBoolean(BootReceiver.PREF_AUTO_STREAM, false).apply()
            }
        }

        switchAuto.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(BootReceiver.PREF_AUTO_STREAM, isChecked).apply()
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showAccountDialog() {
        val options = arrayOf("📤 Clear Binding Request", "🚪 Logout")
        AlertDialog.Builder(this)
            .setTitle("Account Management")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> requestClearBinding()
                    1 -> confirmLogout()
                }
            }
            .setPositiveButton("Cancel", null)
            .show()
    }

    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val tvVersion = view.findViewById<TextView>(R.id.dialogTextVersion)
        
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "Version ${pInfo.versionName} (${pInfo.versionCode})"
        } catch (e: Exception) {
            tvVersion.text = "Version Unknown"
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Close", null)
            .setNeutralButton("Check for Updates") { _, _ ->
                val updateManager = UpdateManager(this)
                updateManager.checkUpdate(isManualCheck = true)
            }
            .show()
    }

    private fun listenFirebaseConnection() {
        val connectedRef = database.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                
                // Update Dialog if Open
                if (networkDialogView != null) {
                    val tvFirebase = networkDialogView!!.findViewById<TextView>(R.id.dialogStatusFirebase)
                    runOnUiThread {
                        if (connected) {
                            tvFirebase.text = "🟢 Connected"
                            tvFirebase.setTextColor(0xFF4CAF50.toInt())
                        } else {
                            tvFirebase.text = "🔴 Disconnected"
                            tvFirebase.setTextColor(0xFFFF6B6B.toInt())
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ -> performLogout() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun performLogout() {
        val prefs = getSharedPreferences("SrtSenderPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        if (boatId.isNotEmpty()) {
            database.getReference("devices/$boatId/status").setValue("offline")
        }

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun requestClearBinding() {
        if (boatId.isEmpty()) return

         AlertDialog.Builder(this)
            .setTitle("Clear Binding")
            .setMessage("This will require you to login again. Proceed?")
            .setPositiveButton("Yes") { _, _ ->
                database.getReference("devices/$boatId/deviceId").setValue(null)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Binding cleared. Logging out...", Toast.LENGTH_SHORT).show()
                        performLogout()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun getRoleLabel(role: String): String {
        return when (role) {
            "racing_boat" -> "🚤 Racing Boat"
            "committee_boat" -> "🛥️ Committee Boat"
            "buoy_1" -> "🔴 Buoy 1"
            "buoy_2" -> "🟠 Buoy 2"
            "buoy_3" -> "🟡 Buoy 3"
            "buoy_4" -> "🟢 Buoy 4"
            "start_buoy_left" -> "🏁 Start L"
            "start_buoy_right" -> "🏁 Start R"
            "finish_buoy_left" -> "🎯 Finish L"
            "finish_buoy_right" -> "🎯 Finish R"
            else -> role
        }
    }
}
