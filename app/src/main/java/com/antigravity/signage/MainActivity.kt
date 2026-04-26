package com.antigravity.signage

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private val SUPABASE_URL = "https://tbotzfqrpeufvqxtpjci.supabase.co"
    private val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InRib3R6ZnFycGV1ZnZxeHRwamNpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY3NzIxNzIsImV4cCI6MjA5MjM0ODE3Mn0.OeOOtjHcMS3q-EgrBX-YprMrshcxhRjdrfwcd_4bv6s"
    private val SCREEN_ID = "890615e8-0015-44ac-8504-6f809544a534"
    
    private var playlistUrls = mutableListOf<String>()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        setupPlayer()
        fetchPlaylist()
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player?.repeatMode = Player.REPEAT_MODE_OFF // Controlaremos manualmente a playlist
        
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playNext()
                }
            }
        })
    }

    private fun fetchPlaylist() {
        val client = OkHttpClient()
        // Query complexa para pegar os links dos vídeos da playlist atual da tela
        val url = "$SUPABASE_URL/rest/v1/playlist_items?playlist_id=eq.1c874713-017a-494f-998b-7c4caee5c9e3&select=media(url)&order=sort_order.asc"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("FireSignage", "Error fetching playlist", e)
                // Tenta novamente em 30 segundos
                playerView.postDelayed({ fetchPlaylist() }, 30000)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) return
                    val body = response.body?.string() ?: ""
                    val items = Gson().fromJson(body, Array<PlaylistItemResponse>::class.java)
                    
                    val urls = items.mapNotNull { it.media?.url }
                    if (urls.isNotEmpty()) {
                        runOnUiThread {
                            playlistUrls.clear()
                            playlistUrls.addAll(urls)
                            currentIndex = 0
                            playVideo(playlistUrls[currentIndex])
                        }
                    }
                }
            }
        })
    }

    private fun playVideo(url: String) {
        val mediaItem = MediaItem.fromUri(url)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    private fun playNext() {
        if (playlistUrls.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlistUrls.size
        playVideo(playlistUrls[currentIndex])
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }

    data class PlaylistItemResponse(val media: MediaResponse?)
    data class MediaResponse(val url: String?)
}
