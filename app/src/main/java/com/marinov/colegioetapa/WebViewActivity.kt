package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15"
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        // Configurações importantes para vídeo
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.javaScriptCanOpenWindowsAutomatically = true

        // Configurações para vídeos em tela cheia
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                // Se já existe uma view customizada, sair dela primeiro
                if (customView != null) {
                    onHideCustomView()
                    return
                }

                customView = view
                customViewCallback = callback

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
            }

            override fun onHideCustomView() {
                // Se não há view customizada, não fazer nada
                if (customView == null) return

                // Mostrar WebView original
                webView.visibility = View.VISIBLE

                // Remover view de fullscreen
                val decor = window.decorView as FrameLayout
                decor.removeView(fullscreenContainer)
                fullscreenContainer.removeAllViews()

                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                // Restaurar orientação original
                requestedOrientation = originalOrientation

                // Restaurar sistema de barras normal
                hideSystemBars()
            }

            override fun getVideoLoadingProgressView(): View? {
                // Você pode retornar uma view customizada para loading de vídeo
                return null
            }
        }

        // Atribuir o WebChromeClient ao WebView
        webView.webChromeClient = webChromeClient

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Manter tela cheia mesmo após carregamento (apenas se não estiver em fullscreen)
                if (customView == null) {
                    hideSystemBars()
                }
            }
        }
    }

    private fun setupDownloadListener() {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            // Para Android 11+, não precisamos de permissões especiais para Downloads
            // O DownloadManager já tem acesso à pasta Downloads
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val request = DownloadManager.Request(url.toUri())

            // Configurar cookies para a requisição
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("cookie", cookies)
            }
            request.addRequestHeader("User-Agent", userAgent)

            // Gerar nome do arquivo
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

            // Configurar descrição e título
            request.setDescription("Fazendo download de $fileName")
            request.setTitle(fileName)

            // Permitir que o download seja visível na notificação
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            // Para Android 10+ (API 29+), usar setDestinationInExternalPublicDir é seguro
            // Para Android 11+ (API 30+), o sistema gerencia automaticamente o acesso
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - usar diretório público sem permissões especiais
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } else {
                // Android 9 e inferior - usar diretório público (requer permissão)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            // Permitir downloads em redes móveis e Wi-Fi
            request.setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )

            // Permitir que o download continue mesmo se o usuário sair do app
            request.setAllowedOverRoaming(false)

            // Iniciar o download
            val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            // Mostrar mensagem de confirmação
            Toast.makeText(this, "Download iniciado: $fileName", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao iniciar download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupBackPressHandler() {
        // Usar OnBackPressedDispatcher em vez de onBackPressed deprecated
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Se estiver em fullscreen, sair do fullscreen primeiro
                if (customView != null) {
                    webChromeClient?.onHideCustomView()
                    return
                }

                // Se pode voltar no WebView
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Remove este callback e chama o comportamento padrão
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hideSystemBarsCompletely() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Para compatibilidade com versões mais antigas
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
    }

    override fun onDestroy() {
        // Limpar recursos quando a activity for destruída
        if (customView != null) {
            webChromeClient?.onHideCustomView()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        fun start(context: Context, url: String) {
            val intent = Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }
}