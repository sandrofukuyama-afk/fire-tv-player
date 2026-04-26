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
        
        // Habilita depuração remota via Chrome DevTools (chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true)

        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val screenId = sharedPref.getString("screenId", null)

        if (screenId != null) {
            Log.d(TAG, "ID de tela encontrado: $screenId")
            startPlayer(screenId.trim())
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
            Toast.makeText(this, "Erro de layout. Verifique se o arquivo XML existe.", Toast.LENGTH_LONG).show()
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
                    statusText?.text = "Erro de rede. Tentando novamente em 5s..." 
                }
                handler.postDelayed({ generateAndRegisterCode() }, 5000)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "Código registrado com sucesso: $code")
                    runOnUiThread { 
                        statusText?.text = "Aguardando ativação no Dashboard..." 
                        progressBar?.visibility = View.GONE
                    }
                    startPolling()
                } else {
                    Log.e(TAG, "Erro do servidor: ${response.code} ${response.message}")
                    runOnUiThread { statusText?.text = "Servidor ocupado (Erro ${response.code}). Tentando..." }
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
                                        Log.d(TAG, "Tela pareada! ID: $screenId")
                                        saveAndStart(screenId)
                                        return
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao processar JSON de polling", e)
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
        runOnUiThread { startPlayer(screenId.trim()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startPlayer(screenId: String) {
        Log.d(TAG, "Iniciando WebView para tela $screenId")
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)

        webView?.let { v ->
            // ULTRA COMPATIBILIDADE: Força modo software se necessário
            // v.setLayerType(View.LAYER_TYPE_SOFTWARE, null) 
            
            // Limpa tudo para garantir fresh start
            v.clearCache(true)
            v.clearHistory()
            v.clearFormData()
            
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            
            setupWebView()
            
            // Adiciona timestamp para evitar cache do servidor/CDN
            val finalUrl = "$BASE_PLAYER_URL$screenId?t=${System.currentTimeMillis()}"
            Log.d(TAG, "Carregando URL: $finalUrl")
            v.loadUrl(finalUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.let { view ->
            val settings = view.settings
            
            // Configurações críticas para React/Vite
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            
            // Configurações de visualização
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            
            // Suporte a mídia
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            // User Agent moderno para evitar bloqueios ou versões "mobile" limitadas
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            
            // Cache - LOAD_NO_CACHE garante que sempre pegue a versão nova durante o troubleshooting
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Página carregada: $url")
                }

                override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                    val errorCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION.SDK_INT) e?.errorCode else -1
                    Log.e(TAG, "Erro WebView ($errorCode): ${e?.description}")
                    
                    // Se falhar em carregar, tenta novamente após 10 segundos
                    v?.postDelayed({ v.reload() }, 10000)
                }
            }
            
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    Log.d("WebConsole", "[${message?.messageLevel()}] ${message?.message()} (Linha: ${message?.lineNumber()})")
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

    override fun onBackPressed() {
        // Desabilitado para evitar fechar o player acidentalmente no controle remoto
    }

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
