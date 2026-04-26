package com.antigravity.signage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "SignagePlayer"
    private var webView: WebView? = null
    private var pairingCodeText: TextView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val SUPABASE_URL = "https://tbotzfqrpeufvqxtpjci.supabase.co"
    private val SUPABASE_KEY = "sb_publishable_PA03417QIfOz8FyoaHB36w_VTzRkbL2"
    private val BASE_PLAYER_URL = "https://digitalsignagepro.vercel.app/player/"

    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var currentPairingCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "App iniciado")
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        
        WebView.setWebContentsDebuggingEnabled(true)

        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val screenId = sharedPref.getString("screenId", null)

        if (screenId != null) {
            startPlayer(screenId.trim())
        } else {
            showPairingScreen()
        }
    }

    private fun showPairingScreen() {
        try {
            setContentView(R.layout.activity_pairing)
            pairingCodeText = findViewById(R.id.pairing_code_text)
            statusText = findViewById(R.id.status_text)
            progressBar = findViewById(R.id.pairing_progress)
            generateAndRegisterCode()
        } catch (e: Exception) {
            Log.e(TAG, "Erro layout", e)
        }
    }

    private fun generateAndRegisterCode() {
        val code = (100000..999999).random().toString()
        currentPairingCode = code
        pairingCodeText?.text = code
        statusText?.text = "Registrando código..."
        progressBar?.visibility = View.VISIBLE

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
                runOnUiThread { statusText?.text = "Erro de rede. Tentando..." }
                handler.postDelayed({ generateAndRegisterCode() }, 5000)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    runOnUiThread { 
                        statusText?.text = "Aguardando ativação..." 
                        progressBar?.visibility = View.GONE
                    }
                    startPolling()
                } else {
                    handler.postDelayed({ generateAndRegisterCode() }, 5000)
                }
                response.close()
            }
        })
    }

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val self = this
                if (currentPairingCode == null) return

                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/pairing_codes?code=eq.$currentPairingCode&select=screen_id")
                    .get()
                    .addHeader("apikey", SUPABASE_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        handler.postDelayed(self, 5000)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            try {
                                val jsonArray = JSONArray(body)
                                if (jsonArray.length() > 0) {
                                    val obj = jsonArray.getJSONObject(0)
                                    if (!obj.isNull("screen_id")) {
                                        val screenId = obj.getString("screen_id")
                                        saveAndStart(screenId)
                                        return
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                        response.close()
                        handler.postDelayed(self, 3000)
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
        runOnUiThread { startPlayer(screenId.trim()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startPlayer(screenId: String) {
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)

        webView?.let { v ->
            v.clearCache(true)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            
            setupWebView()
            
            val finalUrl = "$BASE_PLAYER_URL$screenId?t=${System.currentTimeMillis()}"
            v.loadUrl(finalUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.let { view ->
            val settings = view.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(v: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e(TAG, "Erro WebView: $description")
                    v?.postDelayed({ v.reload() }, 10000)
                }
            }
        }
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
        webView?.onResume()
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPairingCode = null
    }
}
