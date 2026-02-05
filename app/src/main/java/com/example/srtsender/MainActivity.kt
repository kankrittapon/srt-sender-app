package com.example.srtsender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.srtsender.databinding.ActivityMainBinding
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: SurfaceView
    private lateinit var etIp: EditText
    private lateinit var etBoatId: EditText
    private lateinit var btnStart: Button
    private lateinit var btnSettings: Button
    private lateinit var statusText: android.widget.TextView
    private lateinit var statusSrtText: android.widget.TextView
    private lateinit var statusFirebaseText: android.widget.TextView
    private lateinit var statusGpsText: android.widget.TextView
    private lateinit var textBoatIdDisplay: android.widget.TextView

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaCodec: MediaCodec? = null

    private var isStreaming = false
    private val backgroundThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)
    
    // Video configuration
    private val VIDEO_WIDTH = 1280
    private val VIDEO_HEIGHT = 720
    private val VIDEO_BITRATE = 2000000 // 2 Mbps
    private val VIDEO_FRAMERATE = 30

    // SRT Config (Read from Firebase via Intent, or fallback to 9000)
    private var srtPort: Int = 9000

    // JNI
    external fun nativeInit(ip: String, port: Int, boatId: String): Boolean
    external fun nativeSendFrame(data: ByteBuffer, length: Int, timestamp: Long)
    external fun nativeRelease()

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    private var gpsFirebaseManager: GpsFirebaseManager? = null
    private var firebaseManager: FirebaseManager? = null
    private lateinit var updateManager: UpdateManager
    private var voiceReceiver: VoiceReceiver? = null

    private var serverIp: String = "192.168.1.1"
    private var boatId: String = "boat01"
    
    // Room assignment (used in SRT stream path)
    private var assignedRoomId: String? = null
    
    // SRT Latency (ms) - configurable from Firebase
    private var srtLatency: Int = 200
    
    // Device role info
    private var deviceRole: String = "racing_boat"
    private var hasVideo: Boolean = true
    private var hasGps: Boolean = true

    // Permission tracking
    private var allPermissionsGranted = false

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prevent screen sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Get params from Login FIRST so we have boatId for managers
        serverIp = intent.getStringExtra("SERVER_IP") ?: "192.168.1.1"
        boatId = intent.getStringExtra("BOAT_ID") ?: "boat01"
        deviceRole = intent.getStringExtra("DEVICE_ROLE") ?: "racing_boat"
        hasVideo = intent.getBooleanExtra("HAS_VIDEO", true)
        hasGps = intent.getBooleanExtra("HAS_GPS", true)
        srtPort = intent.getIntExtra("SERVER_PORT", 9000)
        srtLatency = intent.getIntExtra("SRT_LATENCY", 200)
        val autoStart = intent.getBooleanExtra("AUTO_START", false)
        
        Log.d("MainActivity", "Role: $deviceRole, Video: $hasVideo, GPS: $hasGps, Port: $srtPort, Latency: ${srtLatency}ms, AutoStart: $autoStart")

        // Initialize Managers
        firebaseManager = FirebaseManager(boatId)
        gpsFirebaseManager = GpsFirebaseManager(this, boatId)
        updateManager = UpdateManager(this)
        voiceReceiver = VoiceReceiver(this)

        // Check for updates automatically
        updateManager.checkUpdate()

        // Setup UI
        initViews()
        loadSavedConfig()
        
        updateStatusIndicators()

        // Default state: Disabled until assigned
        btnStart.isEnabled = false
        btnStart.text = "Waiting Assign..."
        statusText.text = "Checking room assignment..."

        // Settings button
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("boatId", boatId)
            intent.putExtra("deviceRole", deviceRole)
            intent.putExtra("hasGps", hasGps)
            intent.putExtra("isConnectedToSrt", isStreaming)
            startActivity(intent)
        }

        checkRoomAssignment()

        btnStart.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
                btnStart.text = "Start Stream"
                isStreaming = false
                statusText.text = "⏹️ Stream Stopped"
                updateStatusIndicators()
                // Check assignment again to enable/disable if room changed while streaming (edge case)
                checkRoomAssignment()
            } else {
                if (checkPermissions()) {
                    startStreaming()
                } else {
                    requestPermissions()
                }
            }
        }
        
        // Handle AUTO_START from BootReceiver (Module Mode)
        if (autoStart) {
            Log.d("MainActivity", "AUTO_START enabled - will start streaming after room check")
            // Will be handled in checkRoomAssignment callback
        }
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.viewFinder)
        etIp = findViewById(R.id.etIp)
        etBoatId = findViewById(R.id.etBoatId)
        btnStart = findViewById(R.id.btnStart)
        btnSettings = findViewById(R.id.btnSettings)
        statusText = findViewById(R.id.statusText)
        statusSrtText = findViewById(R.id.statusSrt)
        statusFirebaseText = findViewById(R.id.statusFirebase)
        statusGpsText = findViewById(R.id.statusGps)
        textBoatIdDisplay = findViewById(R.id.textBoatId)

        // Pre-fill and lock UI use values from Intent
        etIp.setText(serverIp)
        etIp.isEnabled = false
        etBoatId.setText(boatId)
        etBoatId.isEnabled = false
        
        // Display Boat ID
        textBoatIdDisplay.text = boatId
    }

    private fun loadSavedConfig() {
        // Implementation for loading any other local configs if needed
        // Currently handled by Intent extras from LoginActivity
    }

    private fun checkRoomAssignment() {
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val deviceRef = db.getReference("devices/$boatId/roomId")

        deviceRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val roomId = snapshot.getValue(String::class.java)
                
                // Store room ID for SRT stream path
                this@MainActivity.assignedRoomId = roomId
                
                if (isStreaming) return; // Don't interrupt if already streaming

                if (!roomId.isNullOrEmpty()) {
                    btnStart.isEnabled = true
                    btnStart.text = "Start Stream"
                    statusText.text = "Assigned to Room: $roomId"
                } else {
                    btnStart.isEnabled = false
                    btnStart.text = "Waiting Assign..."
                    statusText.text = "Not assigned to any room"
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                statusText.text = "Error checking assignment"
            }
        })
    }

    private fun checkPermissions(): Boolean {
        val camera = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val location = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return camera && location
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.CAMERA, 
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), 101)
    }

    private fun startStreaming() {
        btnStart.isEnabled = false
        statusText.text = "Initializing SRT Connection..."

        Thread {
            try {
                val resolvedIp = java.net.InetAddress.getByName(serverIp).hostAddress
                
                // Create stream path: roomId_boatId (required by MediaMTX/Backend)
                val streamPath = if (!assignedRoomId.isNullOrEmpty()) {
                    "${assignedRoomId}_${boatId}"
                } else {
                    boatId // Fallback to boatId only if no room assigned
                }
                Log.d("MainActivity", "Resolved $serverIp -> $resolvedIp, Port: $srtPort, StreamPath: $streamPath")

                // 1. Init Native SRT with roomId_boatId format
                val success = nativeInit(resolvedIp, srtPort, streamPath)

                runOnUiThread {
                    if (success) {
                        try {
                            // Only start video components if device has video capability
                            if (hasVideo) {
                                statusText.text = "SRT Connected. Starting Video..."
                                
                                // 2. Start MediaCodec
                                startMediaCodec()

                                // 3. Start Camera
                                startCamera()
                            } else {
                                statusText.text = "GPS-Only Mode (No Video)"
                                viewFinder.visibility = android.view.View.GONE
                            }
                            
                            // 4. Start GPS (using Firebase instead of MQTT for Android 14+ compatibility)
                            if (hasGps) {
                                gpsFirebaseManager = GpsFirebaseManager(this, boatId)
                                gpsFirebaseManager?.startUpdates()
                            }
                            
                            firebaseManager = FirebaseManager(boatId)
                            firebaseManager?.setOnline()

                            btnStart.text = "Stop Stream"
                            btnStart.isEnabled = true
                            isStreaming = true
                            statusText.text = "🟢 Streaming Live"
                            updateStatusIndicators()
                            
                            // Start Foreground Service for background streaming
                            val serviceIntent = Intent(this, StreamingService::class.java).apply {
                                action = StreamingService.ACTION_START
                                putExtra("boatId", boatId)
                            }
                            startForegroundService(serviceIntent)
                            
                            // Start Voice Receiver (RTSP)
                            // Voice URL: rtsp://<SERVER_IP>:8554/voice_<DEVICE_ID>
                            val voiceUrl = "rtsp://$serverIp:8554/voice_$boatId"
                            voiceReceiver?.startListening(voiceUrl)

                        } catch (e: Exception) {
                            e.printStackTrace()
                            statusText.text = "Error starting components: ${e.message}"
                            btnStart.isEnabled = true
                            btnStart.text = "Start Stream"
                            stopStreaming()
                        }
                    } else {
                        statusText.text = "SRT Connection FAILED. Check Server IP / Port ($srtPort)."
                        btnStart.isEnabled = true
                        btnStart.text = "Start Stream"
                    }
                }
            } catch (e: Exception) {
                 runOnUiThread {
                     statusText.text = "Crash in Logic: ${e.message}"
                     btnStart.isEnabled = true
                     btnStart.text = "Start Stream"
                     e.printStackTrace()
                 }
            }
        }.start()
    }

    private fun stopStreaming() {
        try {
            voiceReceiver?.stopListening()
            gpsFirebaseManager?.stopUpdates()
            firebaseManager?.setOffline()
            
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            nativeRelease()
            
            // Stop Foreground Service
            val serviceIntent = Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_STOP
            }
            startService(serviceIntent)
            updateStatusIndicators()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startMediaCodec() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAMERATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second
                // Removed KEY_PROFILE to allow device to use default supported profile.
                // This fixes 0x80001001 on devices like Vivo V9 which might conflict with specific Profile requests.
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "MediaCodec Init Failed", e)
            // Show error in UI instead of crashing the app
            runOnUiThread {
                statusText.text = "Error: MediaCodec Failed (0x${Integer.toHexString(e.hashCode())})"
                btnStart.isEnabled = true
                btnStart.text = "START STREAM"
            }
            // Prevent app crash by not re-throwing
            return
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0] // Assume back camera
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, backgroundHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        try {
            // MediaCodec Input Surface
            val codecSurface = mediaCodec?.createInputSurface() ?: return
            mediaCodec?.start()
            
            drainEncoder()
            
            val targets = mutableListOf<Surface>(codecSurface)
            
            // Preview Surface
            if (viewFinder.holder.surface.isValid) {
                targets.add(viewFinder.holder.surface)
            }
            
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(codecSurface)
                        if (viewFinder.holder.surface.isValid) {
                            addTarget(viewFinder.holder.surface)
                        }
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(VIDEO_FRAMERATE, VIDEO_FRAMERATE))
                    }
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun drainEncoder() {
        Thread {
            while (isStreaming && mediaCodec != null) {
                try {
                    val bufferInfo = MediaCodec.BufferInfo()
                    val index = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (index >= 0) {
                        val buffer = mediaCodec?.getOutputBuffer(index)
                        if (buffer != null) {
                            // Send to JNI
                            // NALUs are here.
                            Log.d("MainActivity", "Sending frame: ${bufferInfo.size} bytes");
                            nativeSendFrame(buffer, bufferInfo.size, bufferInfo.presentationTimeUs * 1000) // ns
                            mediaCodec?.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
            }
        }.start()
    }

    private fun updateStatusIndicators() {
        // SRT Status
        if (isStreaming) {
            statusSrtText.text = "SRT 🟢"
            statusSrtText.setTextColor(0xFF4CAF50.toInt())
        } else {
            statusSrtText.text = "SRT 🔴"
            statusSrtText.setTextColor(0xFFFF6B6B.toInt())
        }

        // GPS Status
        if (hasGps) {
            statusGpsText.text = "GPS 🟢"
            statusGpsText.setTextColor(0xFF4CAF50.toInt())
        } else {
            statusGpsText.text = "GPS ⚪"
            statusGpsText.setTextColor(0xFF888888.toInt())
        }

        // Firebase Status - listen for connection
        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        db.getReference(".info/connected").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                runOnUiThread {
                    if (connected) {
                        statusFirebaseText.text = "Firebase 🟢"
                        statusFirebaseText.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        statusFirebaseText.text = "Firebase 🔴"
                        statusFirebaseText.setTextColor(0xFFFF6B6B.toInt())
                    }
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }
}
