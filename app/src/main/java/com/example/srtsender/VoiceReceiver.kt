package com.example.srtsender

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource

class VoiceReceiver(private val context: Context) {

    private var player: ExoPlayer? = null
    private val TAG = "VoiceReceiver"
    private var currentUrl: String = ""

    fun startListening(url: String) {
        if (currentUrl == url && player != null && player?.isPlaying == true) return
        
        currentUrl = url
        stopListening() // Reset previous

        Log.d(TAG, "Initializing Player for: $url")

        // Configure Audio Attributes for Speech (High Priority)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_VOICE_COMMUNICATION)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(audioAttributes, true) // Handle Audio Focus automatic ducking
            playWhenReady = true
            
            // Error Handling: Auto-retry on connection loss
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player Error: ${error.message}. Retrying in 3s...")
                    // Simple retry logic
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (player != null) {
                            prepare() // Retry
                        }
                    }, 3000)
                }
            })
        }

        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        // ExoPlayer automatically detects RTSP if the module is included and URI scheme is rtsp://
        player?.setMediaItem(mediaItem)
        player?.prepare()
    }

    fun stopListening() {
        try {
            player?.stop()
            player?.release()
            player = null
            Log.d(TAG, "Player Released")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
