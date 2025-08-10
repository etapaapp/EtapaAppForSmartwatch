package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private var originalOrientation: Int = 0
    private var webChromeClient: WebChromeClient? = null
    private var isInFullscreenMode = false
    private var isDestroying = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_webview)

            // Salvar orientação original
            originalOrientation = requestedOrientation

            // Configurar para tela cheia (ocultar barras do sistema)
            hideSystemBars()

            webView = findViewById(R.id.webview)

            // Criar container para fullscreen
            fullscreenContainer = FrameLayout(this)

            setupWebView()
            setupDownloadListener()
            setupBackPressHandler()

            val url = intent.getStringExtra(EXTRA_URL) ?: ""
            if (url.isNotEmpty()) {
                webView.loadUrl(url)
            } else {
                // Se não há URL, volta imediatamente
                finishSafely()
            }
        } catch (e: Exception) {
            finishSafely()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15"
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
            }

            // Configurações para vídeos em tela cheia
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (isDestroying) return

                    // Se já existe uma view customizada, sair dela primeiro
                    if (customView != null) {
                        onHideCustomView()
                        return
                    }

                    customView = view
                    customViewCallback = callback
                    isInFullscreenMode = true

                    try {
                        // Adicionar a view de fullscreen ao container
                        val decor = window.decorView as FrameLayout
                        decor.addView(fullscreenContainer, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))

                        fullscreenContainer.addView(view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))

                        // Ocultar WebView original
                        webView.visibility = View.GONE

                        // Configurar orientação para landscape se necessário
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

                        // Configurar para tela cheia completa
                        hideSystemBarsCompletely()
                    } catch (e: Exception) {
                        // Se falhar, reverter
                        onHideCustomView()
                    }
                }

                override fun onHideCustomView() {
                    if (customView == null || isDestroying) return

                    try {
                        // Mostrar WebView original
                        webView.visibility = View.VISIBLE

                        // Remover view de fullscreen
                        val decor = window.decorView as FrameLayout
                        decor.removeView(fullscreenContainer)
                        fullscreenContainer.removeAllViews()

                        customView = null
                        customViewCallback?.onCustomViewHidden()
                        customViewCallback = null
                        isInFullscreenMode = false

                        // Restaurar orientação original
                        requestedOrientation = originalOrientation

                        // Restaurar sistema de barras normal
                        hideSystemBars()
                    } catch (e: Exception) {
                        // Ignorar erros na restauração
                    }
                }

                override fun getVideoLoadingProgressView(): View? {
                    return null
                }
            }

            // Atribuir o WebChromeClient ao WebView
            webView.webChromeClient = webChromeClient

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isInFullscreenMode && !isDestroying) {
                        hideSystemBars()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (!isDestroying) {
                        mainHandler.post {
                            Toast.makeText(this@WebViewActivity, "Erro ao carregar página", Toast.LENGTH_SHORT).show()
                            finishSafely()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            finishSafely()
        }
    }

    private fun setupDownloadListener() {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val request = DownloadManager.Request(url.toUri())

            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("cookie", cookies)
            }
            request.addRequestHeader("User-Agent", userAgent)

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            request.setDescription("Fazendo download de $fileName")
            request.setTitle(fileName)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            request.setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
            request.setAllowedOverRoaming(false)

            val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(this, "Download iniciado: $fileName", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao iniciar download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun handleBackPress() {
        try {
            // 1. Se estiver em fullscreen de vídeo, sair do fullscreen primeiro
            if (isInFullscreenMode && customView != null) {
                webChromeClient?.onHideCustomView()
                return
            }

            // 2. Se o WebView pode voltar (histórico de navegação), voltar uma página
            if (webView.canGoBack()) {
                webView.goBack()
                return
            }

            // 3. Se não pode voltar no WebView, fechar a activity
            finishSafely()
        } catch (e: Exception) {
            finishSafely()
        }
    }

    private fun finishSafely() {
        if (isDestroying) return

        isDestroying = true

        mainHandler.post {
            try {
                // Definir resultado como OK
                setResult(RESULT_OK)

                // Finalizar activity
                finish()

                // Aplicar transição de saída
                overridePendingTransition(0, android.R.anim.fade_out)

            } catch (e: Exception) {
                // Force finish em caso de erro
                try {
                    finish()
                } catch (ex: Exception) {
                    // Último recurso
                    finishAndRemoveTask()
                }
            }
        }
    }

    private fun hideSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (e: Exception) {
            // Ignorar erros de UI
        }
    }

    private fun hideSystemBarsCompletely() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        } catch (e: Exception) {
            // Ignorar erros de UI
        }
    }

    override fun onDestroy() {
        isDestroying = true

        try {
            // Limpar fullscreen se ativo
            if (isInFullscreenMode && customView != null) {
                try {
                    val decor = window.decorView as FrameLayout
                    decor.removeView(fullscreenContainer)
                    fullscreenContainer.removeAllViews()
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                } catch (e: Exception) {
                    // Ignorar erros de limpeza
                }
            }

            // Limpar WebView
            try {
                webView.stopLoading()
                webView.clearHistory()
                webView.clearCache(true)
                webView.removeAllViews()
                webView.destroyDrawingCache()
                webView.destroy()
            } catch (e: Exception) {
                // Ignorar erros de limpeza da WebView
            }

            // Limpar handler
            mainHandler.removeCallbacksAndMessages(null)

        } catch (e: Exception) {
            // Ignorar todos os erros durante destruição
        }

        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        try {
            if (!isDestroying) {
                webView.onPause()
            }
        } catch (e: Exception) {
            // Ignorar erros
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (!isDestroying) {
                webView.onResume()
                if (!isInFullscreenMode) {
                    hideSystemBars()
                }
            }
        } catch (e: Exception) {
            // Ignorar erros
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        fun start(context: Context, url: String) {
            try {
                val intent = Intent(context, WebViewActivity::class.java).apply {
                    putExtra(EXTRA_URL, url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Em caso de erro, não fazer nada
            }
        }
    }
}