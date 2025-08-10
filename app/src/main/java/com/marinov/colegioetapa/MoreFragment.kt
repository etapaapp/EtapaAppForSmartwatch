package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MoreFragment : Fragment() { // 1. Implemente a interface

    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvStudentName: TextView
    private lateinit var tvStudentRegistration: TextView
    private lateinit var tvStudentClass: TextView
    private lateinit var tvStudentNumber: TextView
    private lateinit var btnReloadProfile: ImageView
    private lateinit var webView: WebView

    private lateinit var sharedPreferences: SharedPreferences
    private val AUTH_CHECK_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"

    // 2. Variável de controle para refresh
    private var isRefreshing = false

    private companion object {
        private const val PROFILE_PREFS = "profile_preferences"
        private const val PROFILE_DATA_KEY = "profile_data"
        private const val PROFILE_IMAGE_KEY = "profile_image_path"
        private const val PROFILE_HAS_DATA_KEY = "profile_has_data"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_more, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreferences = requireContext().getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)

        initViews(view)
        setupClickListeners(view)
        loadProfileData()
        loadProfileImage()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )

        webView = view.findViewById(R.id.webView)
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true

        checkInternetAndAuthentication()
    }

    private fun initViews(view: View) {
        ivProfilePhoto = view.findViewById(R.id.iv_profile_photo)
        tvStudentName = view.findViewById(R.id.tv_student_name)
        tvStudentRegistration = view.findViewById(R.id.tv_student_registration)
        tvStudentClass = view.findViewById(R.id.tv_student_class)
        tvStudentNumber = view.findViewById(R.id.tv_student_number)
        btnReloadProfile = view.findViewById(R.id.btn_reload_profile)
    }

    private fun setupClickListeners(view: View) {
        btnReloadProfile.setOnClickListener {
            (activity as? MainActivity)?.openCustomFragment(ProfileFragment())
        }
        view.findViewById<View>(R.id.option_detalhes_provas).setOnClickListener {
            (activity as MainActivity).openCustomFragment(DetalhesProvas())
        }
        view.findViewById<View>(R.id.option_ead_online).setOnClickListener {
            (activity as MainActivity).openCustomFragment(EADOnlineFragment())
        }
        view.findViewById<View>(R.id.navigation_material).setOnClickListener {
            (activity as MainActivity).openCustomFragment(MaterialFragment())
        }
        view.findViewById<View>(R.id.option_provas_gabaritos).setOnClickListener {
            (activity as MainActivity).openCustomFragment(ProvasGabaritos())
        }
    }

    private fun showLoading() {
    }

    private fun showContent() {
    }

    private fun showError() {
    }

    private fun checkInternetAndAuthentication() {
        if (!hasInternetConnection()) {
            if (hasOfflineProfileData()) {
                showContent()
            } else {
                showError()
            }
            // 5. Parar refresh se offline
            return
        }

        showLoading()
        performAuthCheck()
    }

    private fun hasInternetConnection(): Boolean {
        val context = context ?: return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasOfflineProfileData(): Boolean {
        return sharedPreferences.getBoolean(PROFILE_HAS_DATA_KEY, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun performAuthCheck() {
        val authWebView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

        authWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function() { " +
                            "return document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                            "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(2) > div.card-body > table') !== null; " +
                            "})();"
                ) { value ->
                    val isAuthenticated = value == "true"
                    if (isAuthenticated) {
                        loadProfilePage()
                    } else {
                        loadOfflineProfileData()
                    }
                    authWebView.destroy()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                loadOfflineProfileData()
                authWebView.destroy()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                loadOfflineProfileData()
                authWebView.destroy()
            }
        }
        authWebView.loadUrl(AUTH_CHECK_URL)
    }

    private fun loadOfflineProfileData() {
        if (hasOfflineProfileData()) {
            loadProfileData()
            showContent()
        } else {
            showError()
        }
        // 5. Parar refresh ao carregar dados offline
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadProfilePage() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            const desktopData = extractDesktopData();
                            if (desktopData) return JSON.stringify(desktopData);
                            
                            const mobileData = extractMobileData();
                            if (mobileData) return JSON.stringify(mobileData);
                            
                            return JSON.stringify(extractFallbackData());
                            
                            function extractDesktopData() {
                                const container = document.querySelector('.popover-body .mt-2');
                                if (!container) return null;
                                
                                const items = container.querySelectorAll('p');
                                const labels = ["Aluno", "Matrícula", "Unidade", "Período", "Sala", "Grau", "Série/Ano", "Nº chamada"];
                                const result = {};
                                
                                items.forEach((item, index) => {
                                    if (index < labels.length) {
                                        const text = item.textContent.trim();
                                        const value = text.replace(/^[^a-zA-Z0-9]*/, '').trim();
                                        result[labels[index]] = value;
                                    }
                                });
                                return result;
                            }
                            
                            function extractMobileData() {
                                const container = document.querySelector('.navbar-collapse.d-lg-none.show');
                                if (!container) return null;
                                
                                const items = container.querySelectorAll('li span.d-block');
                                const result = {};
                                
                                items.forEach(item => {
                                    const text = item.textContent.trim();
                                    const colonIndex = text.indexOf(':');
                                    if (colonIndex !== -1) {
                                        const label = text.substring(0, colonIndex).replace(':', '').trim();
                                        const value = text.substring(colonIndex + 1).trim();
                                        result[label] = value;
                                    }
                                });
                                return result;
                            }
                            
                            function extractFallbackData() {
                                const items = document.querySelectorAll('.navbar-nav.ml-auto.mt-2.mt-lg-0.d-flex.d-lg-none > li.nav-item');
                                const result = {};
                                const labels = ["Aluno", "Matrícula", "Unidade", "Período", "Sala", "Grau", "Série/Ano", "Nº chamada"];
                                
                                items.forEach((item, index) => {
                                    if (index < labels.length) {
                                        const text = item.textContent.trim();
                                        const colonIndex = text.indexOf(':');
                                        if (colonIndex !== -1) {
                                            const value = text.substring(colonIndex + 1).trim();
                                            result[labels[index]] = value;
                                        }
                                    }
                                });
                                return result;
                            }
                        } catch (e) {
                            return JSON.stringify({ error: "JS_ERROR: " + e.message });
                        }
                    })();
                    """.trimIndent()
                ) { result ->
                    processExtractedData(result)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                loadOfflineProfileData()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                loadOfflineProfileData()
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl("https://areaexclusiva.colegioetapa.com.br/profile")
    }

    private fun processExtractedData(rawResult: String) {
        try {
            var jsonStr = rawResult
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\t", "")

            if (jsonStr == "null" || jsonStr.isEmpty()) {
                loadOfflineProfileData()
                return
            }

            if (!jsonStr.startsWith("{") || !jsonStr.endsWith("}")) {
                jsonStr = "{$jsonStr}"
            }

            val data = JSONObject(jsonStr)

            if (data.has("error")) {
                val errorMsg = data.getString("error")
                Log.e("MoreFragment", "Erro JavaScript: $errorMsg")
                loadOfflineProfileData()
                return
            }

            if (isValidProfileData(data)) {
                saveProfileDataOffline(data)
                updateProfileViews(data)
                showContent()
                fetchProfileImage()
            } else {
                loadOfflineProfileData()
            }

        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro no parsing: $rawResult", e)
            loadOfflineProfileData()
        } finally {
            // 5. Parar refresh após processar os dados
        }
    }

    private fun isValidProfileData(data: JSONObject): Boolean {
        val essentialFields = listOf("Aluno", "Matrícula", "Unidade")
        var validFields = 0

        essentialFields.forEach { field ->
            if (data.has(field) && !data.getString(field).isNullOrBlank()) {
                validFields++
            }
        }

        return validFields >= 2
    }

    private fun saveProfileDataOffline(profileData: JSONObject) {
        try {
            sharedPreferences.edit {
                putString(PROFILE_DATA_KEY, profileData.toString())
                putBoolean(PROFILE_HAS_DATA_KEY, true)
            }
            Log.d("MoreFragment", "Dados do perfil salvos offline com sucesso")
        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro ao salvar dados offline", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateProfileViews(profileData: JSONObject) {
        try {
            val nomeAluno = profileData.optString("Aluno", "Faça login para exibir os dados")
            val matricula = profileData.optString("Matrícula", "--")
            val sala = profileData.optString("Sala", "--")
            val numero = profileData.optString("Nº chamada", "--")
            tvStudentName.text = nomeAluno
            tvStudentRegistration.text = "Matrícula: $matricula"
            tvStudentClass.text = "Sala: $sala"
            tvStudentNumber.text = "Nº chamada: $numero"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadProfileData() {
        try {
            if (!hasOfflineProfileData()) {
                return
            }
            val jsonString = sharedPreferences.getString(PROFILE_DATA_KEY, null)
            if (jsonString != null) {
                val profileData = JSONObject(jsonString)
                updateProfileViews(profileData)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadProfileImage() {
        val savedImagePath = sharedPreferences.getString(PROFILE_IMAGE_KEY, null)
        if (savedImagePath != null) {
            val file = File(savedImagePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ivProfilePhoto.setImageBitmap(bitmap)
                return
            }
        }

        fetchProfileImage()
    }

    private fun fetchProfileImage() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://areaexclusiva.colegioetapa.com.br") ?: ""

                if (cookies.isEmpty()) {
                    Log.d("MoreFragment", "Cookies não encontrados")
                    return@launch
                }

                val doc = Jsoup.connect("https://areaexclusiva.colegioetapa.com.br/profile")
                    .header("Cookie", cookies)
                    .get()

                val imgElement = doc.selectFirst("div.d-flex.justify-content-center img.rounded-circle")
                val imgUrl = imgElement?.attr("src") ?: ""

                if (imgUrl.isNotEmpty()) {
                    Log.d("MoreFragment", "Imagem encontrada: $imgUrl")

                    val bitmap = downloadImage(imgUrl, cookies)

                    if (bitmap != null) {
                        val savedPath = saveImageToCache(bitmap)

                        withContext(Dispatchers.Main) {
                            ivProfilePhoto.setImageBitmap(bitmap)
                        }

                        sharedPreferences.edit { putString(PROFILE_IMAGE_KEY, savedPath)}
                    }
                }
            } catch (e: Exception) {
                Log.e("MoreFragment", "Erro ao carregar imagem do perfil", e)
            }
        }
    }

    private fun downloadImage(url: String, cookies: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Cookie", cookies)
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                BitmapFactory.decodeStream(inputStream)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro ao baixar imagem", e)
            null
        }
    }

    private fun saveImageToCache(bitmap: Bitmap): String {
        return try {
            val cacheDir = requireContext().cacheDir
            val file = File(cacheDir, "profile_image.jpg")
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e("MoreFragment", "Erro ao salvar imagem", e)
            ""
        }
    }
}