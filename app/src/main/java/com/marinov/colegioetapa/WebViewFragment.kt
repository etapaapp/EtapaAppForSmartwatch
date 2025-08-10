package com.marinov.colegioetapa

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var sharedPrefs: SharedPreferences

    private var loginDetected = false
    private var isNavigatingToHome = false

    interface LoginSuccessListener {
        fun onLoginSuccess()
    }

    companion object {
        private const val ARG_URL = "url"
        private const val HOME_URL_IDENTIFIER = "areaexclusiva.colegioetapa.com.br/home"
        private const val PREFS_NAME = "app_prefs"
        private const val AUTOFILL_PREFS = "autofill_prefs"
        private const val KEY_ASKED_STORAGE = "asked_storage"
        private const val REQUEST_STORAGE_PERMISSION = 1001
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "WebViewFragment"

        @JvmStatic
        fun createArgs(url: String): Bundle = Bundle().apply { putString(ARG_URL, url) }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        sharedPrefs = requireContext().getSharedPreferences(AUTOFILL_PREFS, Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_webview, container, false)
        webView = view.findViewById(R.id.webview)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)

        if (!isOnline()) showNoInternetUI() else initializeWebView()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    requireActivity().supportFragmentManager.popBackStack()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private inner class JsInterface(private val prefs: SharedPreferences) {
        @JavascriptInterface
        fun saveCredentials(user: String, pass: String) = prefs.edit {
            putString("user", user)
            putString("password", pass)
        }

        @JavascriptInterface
        fun getSavedUser(): String = prefs.getString("user", "") ?: ""

        @JavascriptInterface
        fun getSavedPassword(): String = prefs.getString("password", "") ?: ""
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Downloads" }
            requireContext().getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }
        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            visibility = View.INVISIBLE
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15"
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(
                    this,
                    if (isSystemDarkMode()) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY) && isSystemDarkMode()) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDarkStrategy(
                    this,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        }
        webView.addJavascriptInterface(JsInterface(sharedPrefs), "AndroidAutofill")
        setupWebViewSecurity()
        checkStoragePermissions()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                Log.d(TAG, "shouldOverrideUrlLoading: $url")

                if (isHomeUrl(url) && !isNavigatingToHome) {
                    Log.d(TAG, "Home URL detectada em shouldOverrideUrlLoading. Navegando para a Home nativa.")
                    isNavigatingToHome = true
                    simulateHomeButtonClick()
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url")

                // A injeção de scripts e remoção do header devem ocorrer aqui.
                removeHeader(view)
                injectAutoFillScript(view)
                if (isSystemDarkMode()) injectCssDarkMode(view)

                // Verificar se chegamos na home após um login bem-sucedido
                if (isHomeUrl(url) && !loginDetected && !isNavigatingToHome) {
                    Log.d(TAG, "Home URL detectada em onPageFinished após login. Notificando login bem-sucedido.")
                    loginDetected = true
                    isNavigatingToHome = true
                    notifyLoginSuccess()
                    simulateHomeButtonClick()
                    return
                }

                showWebViewWithAnimation(view)
                layoutSemInternet.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.e(TAG, "WebView error: ${error.description}")
                if (!isOnline()) showNoInternetUI()
            }

            private fun isHomeUrl(url: String?): Boolean {
                return url?.contains(HOME_URL_IDENTIFIER) == true
            }
        }

        arguments?.getString(ARG_URL)?.let {
            Log.d(TAG, "Carregando URL inicial: $it")
            webView.loadUrl(it)
        }
        webView.webChromeClient = WebChromeClient()
        configureDownloadListener()
    }

    private fun notifyLoginSuccess() {
        Log.d(TAG, "Notificando login bem-sucedido")
        Handler(Looper.getMainLooper()).post {
            (activity as? LoginSuccessListener)?.onLoginSuccess()
        }
    }

    private fun simulateHomeButtonClick() {
        Log.d(TAG, "Simulando clique do botão Home")
        Handler(Looper.getMainLooper()).postDelayed({
            (activity as? MainActivity)?.navigateToHome()
        }, 100)
    }

    private fun removeHeader(view: WebView) {
        val js = """
            document.documentElement.style.webkitTouchCallout='none';
            document.documentElement.style.webkitUserSelect='none';
            var nav=document.querySelector('#page-content-wrapper > nav'); if(nav) nav.remove();
            var sidebar=document.querySelector('#sidebar-wrapper'); if(sidebar) sidebar.remove();
            var responsavelTab=document.querySelector('#responsavel-tab'); if(responsavelTab) responsavelTab.remove();
            var alunoTab=document.querySelector('#aluno-tab'); if(alunoTab) alunoTab.remove();
            var login=document.querySelector('#login'); if(login) login.remove();
            var cardElement=document.querySelector('body > div.row.mx-0.pt-4 > div > div.card.mt-4.border-radius-card.border-0.shadow'); if(cardElement) cardElement.remove();
            var backButton = document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(1) > div.card-header.bg-soft-blue.border-left-blue.text-blue.rounded > i.fas.fa-chevron-left.btn-outline-primary.py-1.px-2.rounded.mr-2');
            if (backButton) backButton.remove();
            var darkHeader = document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-header.bg-dark.rounded.d-flex.align-items-center.justify-content-center');
            if (darkHeader) darkHeader.remove();
            var style=document.createElement('style');
            style.type='text/css';
            style.appendChild(document.createTextNode('::-webkit-scrollbar{display:none;}'));
            document.head.appendChild(style);
        """.trimIndent()

        view.evaluateJavascript(js, null)
    }

    private fun injectAutoFillScript(view: WebView) {
        val script = """
            (function() {
                const observerConfig = { childList: true, subtree: true };
                const userFields = ['#matricula'];
                const passFields = ['#senha'];
                
                function setupAutofill() {
                    const userField = document.querySelector(userFields.join(', '));
                    const passField = document.querySelector(passFields.join(', '));
                    
                    if (userField && passField) {
                        if (userField.value === '') {
                            userField.value = AndroidAutofill.getSavedUser();
                        }
                        if (passField.value === '') {
                            userField.value = AndroidAutofill.getSavedPassword();
                        }
                        
                        function handleInput() {
                            AndroidAutofill.saveCredentials(userField.value, passField.value);
                        }
                        
                        userField.addEventListener('input', handleInput);
                        passField.addEventListener('input', handleInput);
                        return true;
                    }
                    return false;
                }
                
                if (!setupAutofill()) {
                    const observer = new MutationObserver((mutations) => {
                        if (setupAutofill()) {
                            observer.disconnect();
                        }
                    });
                    observer.observe(document.body, observerConfig);
                }
                
                document.querySelectorAll('.nav-link').forEach(tab => {
                    tab.addEventListener('click', () => {
                        setTimeout(setupAutofill, 300);
                    });
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun injectCssDarkMode(view: WebView) {
        val css = "html{filter:invert(1) hue-rotate(180deg)!important;background:#121212!important;}" +
                "img,picture,video,iframe{filter:invert(1) hue-rotate(180deg)!important;}"
        val js = "(function(){var s=document.createElement('style');s.innerHTML=\"$css\";document.head.appendChild(s);})();"
        view.evaluateJavascript(js, null)
    }

    private fun setupWebViewSecurity() {
        webView.apply {
            setOnLongClickListener { true }
            isLongClickable = false
            isHapticFeedbackEnabled = false
        }
    }

    private fun showWebViewWithAnimation(view: WebView) {
        Handler(Looper.getMainLooper()).postDelayed({
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).duration = 300
        }, 100)
    }

    private fun isSystemDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun isOnline(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return false
        return cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun showNoInternetUI() {
        webView.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
        btnTentarNovamente.setOnClickListener {
            if (isOnline()) {
                layoutSemInternet.visibility = View.GONE
                webView.reload()
            } else {
                Toast.makeText(requireContext(), "Sem conexão com a internet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkStoragePermissions() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !prefs.getBoolean(KEY_ASKED_STORAGE, false)) {
            prefs.edit { putBoolean(KEY_ASKED_STORAGE, true) }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_PERMISSION)
            }
        }
    }

    private fun configureDownloadListener() {
        webView.setDownloadListener { url, ua, cd, mime, _ ->
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return@setDownloadListener
            val cookies = CookieManager.getInstance().getCookie(url)
            val referer = webView.url
            val fileName = URLUtil.guessFileName(url, cd, mime)
            downloadManually(url, fileName, cookies, ua, referer, mime)
        }
    }

    private fun downloadManually(
        url: String,
        fileName: String,
        cookies: String?,
        userAgent: String?,
        referer: String?,
        mimeType: String?
    ) {
        val notif = NotificationCompat.Builder(requireContext(), CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Download")
            .setContentText("Baixando $fileName")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(requireContext()).notify(NOTIFICATION_ID, notif.build())
        }

        Executors.newSingleThreadExecutor().execute {
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            var output: OutputStream? = null
            var downloadedPath: String? = null

            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    cookies?.let { setRequestProperty("Cookie", it) }
                    userAgent?.let { setRequestProperty("User-Agent", it) }
                    referer?.let { setRequestProperty("Referer", it) }
                    connect()
                }
                if (conn.responseCode / 100 == 3) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    downloadManually(loc, fileName, cookies, userAgent, referer, mimeType)
                    return@execute
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")

                input = conn.inputStream
                val targetUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        mimeType?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val outFile = File(dir, fileName)
                    downloadedPath = outFile.absolutePath
                    FileOutputStream(outFile).also { output = it }
                    Uri.fromFile(outFile)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) output = requireContext().contentResolver.openOutputStream(targetUri)
                val buffer = ByteArray(8192)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) output?.write(buffer, 0, len)
                output?.flush()
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(targetUri, mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val pi = PendingIntent.getActivity(requireContext(), 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notif.setContentText("Concluído: $fileName").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentIntent(pi).setOngoing(false).setAutoCancel(true)

                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(requireContext()).notify(NOTIFICATION_ID, notif.build())
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && downloadedPath != null) {
                    MediaScannerConnection.scanFile(requireContext(), arrayOf(downloadedPath), arrayOf(mimeType), null)
                }
            } catch (e: Exception) {
                Log.e("DownloadManual", "erro", e)
                notif.setContentText("Falha: $fileName").setSmallIcon(android.R.drawable.stat_notify_error).setOngoing(false).setAutoCancel(true)

                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    NotificationManagerCompat.from(requireContext()).notify(NOTIFICATION_ID, notif.build())
                }
            } finally {
                conn?.disconnect()
                try { input?.close() } catch (_: IOException) {}
                try { output?.close() } catch (_: IOException) {}
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::webView.isInitialized) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(webView.settings,
                    if (isSystemDarkMode()) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            webView.reload()
        }
    }

    override fun onDestroyView() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroyView()
    }
}