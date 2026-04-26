package com.antigravity.signage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var pairingCodeText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val SUPABASE_URL = "https://tbotzfqrpeufvqxtpjci.supabase.co"
    private val SUPABASE_KEY = "sb_publishable_PA03417QIfOz8FyoaHB36w_VTzRkbL2"
    private val BASE_PLAYER_URL = "https://digitalsignagepro.vercel.app/player/"

    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var currentPairingCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MODO KIOSK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val screenId = sharedPref.getString("screenId", null)

        if (screenId != null) {
            startPlayer(screenId)
        } else {
            showPairingScreen()
        }
    }

    private fun showPairingScreen() {
        setContentView(R.layout.activity_pairing)
        pairingCodeText = findViewById(R.id.pairing_code_text)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.pairing_progress)

        generateAndRegisterCode()
    }

    private fun generateAndRegisterCode() {
        val code = (100000..999999).random().toString()
        currentPairingCode = code
        pairingCodeText.text = code

        val json = JSONObject()
        json.put("code", code)

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/pairing_codes")
            .post(body)
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { statusText.text = "Erro de conexão. Tentando novamente..." }
                handler.postDelayed({ generateAndRegisterCode() }, 5000)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    startPolling()
                } else {
                    runOnUiThread { statusText.text = "Erro ao registrar código. Tentando novamente..." }
                    handler.postDelayed({ generateAndRegisterCode() }, 5000)
                }
            }
        })
    }

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/pairing_codes?code=eq.$currentPairingCode&select=screen_id")
                    .get()
                    .addHeader("apikey", SUPABASE_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val jsonArray = JSONArray(body)
                            if (jsonArray.length() > 0) {
                                val obj = jsonArray.getJSONObject(0)
                                if (!obj.isNull("screen_id")) {
                                    val screenId = obj.getString("screen_id")
                                    saveAndStart(screenId)
                                    return
                                }
                            }
                        }
                        handler.postDelayed(this@Runnable, 3000)
                    }
                })
            }
        }, 3000)
    }

    private fun saveAndStart(screenId: String) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("screenId", screenId)
            apply()
        }
        runOnUiThread { startPlayer(screenId) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startPlayer(screenId: String) {
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        setupWebView()
        webView.loadUrl("$BASE_PLAYER_URL$screenId")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                webView.postDelayed({ webView.reload() }, 5000)
            }
        }
        
        webView.webChromeClient = WebChromeClient()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onBackPressed() {}

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) webView.onPause()
    }
}
