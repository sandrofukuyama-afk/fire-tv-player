package com.antigravity.signage

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
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

class MainActivity : AppCompatActivity() {

    private val TAG = "SignagePlayer"
    private var webView: WebView? = null
    private var pairingCodeText: TextView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val SUPABASE_URL = "https://tbotzfqrpeufvqxtpjci.supabase.co"
    private val SUPABASE_KEY = "sb_publishable_PA03417QIfOz8FyoaHB36w_VTzRkbL2"
    private val BASE_PLAYER_URL = "https://digitalsignagepro.vercel.app/player/"

    // O banco expira o código em 10 minutos; trocamos antes disso para o código na tela sempre valer
    private val PAIRING_CODE_LIFETIME_MS = 9 * 60 * 1000L

    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var currentPairingCode: String? = null
    private var currentScreenId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "App iniciado")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Depuração remota da WebView só em builds de teste
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)

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
        // Cancela tentativas e consultas do código anterior
        handler.removeCallbacksAndMessages(null)

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
                if (code != currentPairingCode) return
                runOnUiThread { statusText?.text = "Erro de rede. Tentando..." }
                handler.postDelayed({ generateAndRegisterCode() }, 5000)
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.use { it.isSuccessful }
                if (code != currentPairingCode) return
                if (ok) {
                    runOnUiThread {
                        statusText?.text = "Aguardando ativação..."
                        progressBar?.visibility = View.GONE
                    }
                    startPolling(code, System.currentTimeMillis())
                } else {
                    handler.postDelayed({ generateAndRegisterCode() }, 5000)
                }
            }
        })
    }

    private fun startPolling(code: String, registeredAt: Long) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val self = this
                if (code != currentPairingCode) return

                if (System.currentTimeMillis() - registeredAt > PAIRING_CODE_LIFETIME_MS) {
                    generateAndRegisterCode()
                    return
                }

                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/pairing_codes?code=eq.$code&select=screen_id")
                    .get()
                    .addHeader("apikey", SUPABASE_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_KEY")
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        handler.postDelayed(self, 5000)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                        if (code != currentPairingCode) return
                        if (body != null) {
                            try {
                                val jsonArray = JSONArray(body)
                                if (jsonArray.length() > 0) {
                                    val obj = jsonArray.getJSONObject(0)
                                    if (!obj.isNull("screen_id")) {
                                        saveAndStart(obj.getString("screen_id"))
                                        return
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                        handler.postDelayed(self, 3000)
                    }
                })
            }
        }, 3000)
    }

    private fun saveAndStart(screenId: String) {
        currentPairingCode = null
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("screenId", screenId)
            apply()
        }
        runOnUiThread { startPlayer(screenId.trim()) }
    }

    private fun startPlayer(screenId: String) {
        currentScreenId = screenId
        destroyWebView()
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)

        // Sem limpar o cache: o player guarda a página e os vídeos para funcionar sem internet
        setupWebView()
        webView?.loadUrl("$BASE_PLAYER_URL$screenId")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView?.let { view ->
            val settings = view.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Adiciona SignagePlayer ao final do UserAgent para o site reconhecer o App
            val defaultUA = settings.userAgentString
            settings.userAgentString = "$defaultUA SignagePlayer/2.0"

            view.addJavascriptInterface(WebAppInterface(), "Android")

            view.webChromeClient = WebChromeClient()
            view.webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun onReceivedError(v: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e(TAG, "Erro WebView: $description")
                    v?.postDelayed({ v.reload() }, 10000)
                }

                // O navegador interno travou ou foi encerrado pelo sistema: recria o player em vez de fechar o app
                override fun onRenderProcessGone(v: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    Log.e(TAG, "WebView encerrada, recriando o player")
                    val screenId = currentScreenId
                    handler.post {
                        if (screenId != null) startPlayer(screenId) else showPairingScreen()
                    }
                    return true
                }
            }
        }
    }

    // Chamado pela página do player
    inner class WebAppInterface {
        // A tela foi excluída no painel: apaga o pareamento e mostra um novo código
        @JavascriptInterface
        fun resetPairing() {
            runOnUiThread {
                Log.w(TAG, "Tela não existe mais no painel, voltando ao pareamento")
                getPreferences(Context.MODE_PRIVATE).edit().remove("screenId").apply()
                currentScreenId = null
                destroyWebView()
                showPairingScreen()
            }
        }
    }

    private fun destroyWebView() {
        webView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            v.stopLoading()
            v.destroy()
        }
        webView = null
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

    @Deprecated("Deprecated in Java")
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
        currentPairingCode = null
        handler.removeCallbacksAndMessages(null)
        destroyWebView()
        super.onDestroy()
    }
}
