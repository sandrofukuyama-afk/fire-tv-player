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
        
        // MODO KIOSK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val screenId = sharedPref.getString("screenId", null)

        if (screenId != null) {
            Log.d(TAG, "ID de tela encontrado: $screenId")
            startPlayer(screenId)
        } else {
            Log.d(TAG, "Nenhum ID encontrado, iniciando pareamento")
            showPairingScreen()
        }
    }

    private fun showPairingScreen() {
        try {
            setContentView(R.layout.activity_pairing)
            pairingCodeText = findViewById(R.id.pairing_code_text)
            statusText = findViewById(R.id.status_text)
            progressBar = findViewById(R.id.pairing_progress)
            
            Log.d(TAG, "Layout de pareamento carregado")
            generateAndRegisterCode()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar layout de pareamento", e)
            Toast.makeText(this, "Erro de layout", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateAndRegisterCode() {
        val code = (100000..999999).random().toString()
        currentPairingCode = code
        pairingCodeText?.text = code
        statusText?.text = "Registrando código no servidor..."
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
                Log.e(TAG, "Falha ao registrar código", e)
                runOnUiThread { 
                    statusText?.text = "Erro de rede. Tentando novamente..." 
                }
                handler.postDelayed({ generateAndRegisterCode() }, 5000)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Código registrado com sucesso: $code")
                    runOnUiThread { statusText?.text = "Aguardando ativação no Dashboard..." }
                    startPolling()
                } else {
                    Log.e(TAG, "Erro do servidor: ${response.code} ${response.message}")
                    runOnUiThread { statusText?.text = "Servidor ocupado. Tentando novamente..." }
                    handler.postDelayed({ generateAndRegisterCode() }, 5000)
                }
                response.close()
            }
        })
    }

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val self = this // Captura a referência correta do Runnable
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
                                        Log.d(TAG, "Tela pareada! ID: $screenId")
                                        saveAndStart(screenId)
                                        return
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao processar JSON", e)
                            }
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
        runOnUiThread { startPlayer(screenId) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startPlayer(screenId: String) {
        Log.d(TAG, "Iniciando WebView para tela $screenId")
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        setupWebView()
        webView?.loadUrl("$BASE_PLAYER_URL$screenId")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.let { view ->
            val settings = view.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            
            // Suporte a Mixed Content para vídeos HTTP em sites HTTPS
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            view.webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                    Log.w(TAG, "Erro no WebView. Recarregando...")
                    view.postDelayed({ view.reload() }, 5000)
                }
            }
            
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    Log.d("WebViewConsole", message?.message() ?: "")
                    return true
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
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPairingCode = null // Para parar o polling
    }
}
