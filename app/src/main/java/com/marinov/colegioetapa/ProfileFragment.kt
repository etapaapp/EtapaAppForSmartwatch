package com.marinov.colegioetapa
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
class ProfileFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var profileContainer: LinearLayout
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var profileCard: MaterialCardView
    private lateinit var ivProfilePhoto: ImageView
    private val handler = Handler(Looper.getMainLooper())
    private val AUTH_CHECK_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
    private lateinit var sharedPreferences: SharedPreferences
    private companion object {
        private const val PROFILE_PREFS = "profile_preferences"
        private const val PROFILE_DATA_KEY = "profile_data"
        private const val PROFILE_LAST_UPDATE_KEY = "profile_last_update"
        private const val PROFILE_HAS_DATA_KEY = "profile_has_data"
        private const val PROFILE_IMAGE_KEY = "profile_image_path"
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = view.findViewById(R.id.webView)
        profileContainer = view.findViewById(R.id.profileContainer)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        profileCard = view.findViewById(R.id.profileCard)
        ivProfilePhoto = view.findViewById(R.id.iv_profile_photo)
        initializeSharedPreferences()
        setupWebView()
        btnTentarNovamente.setOnClickListener {
            navigateToHomeFragment()
        }
        // Adiciona callback para botão voltar
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateToHomeFragment()
                }
            }
        )
        // Primeiro: carregar dados do cache (se existirem)
        if (hasOfflineProfileData()) {
            val savedData = getOfflineProfileData()
            if (savedData != null) {
                displayProfileData(savedData)
                loadProfileImage() // Carrega a imagem do perfil do cache
                profileCard.visibility = View.VISIBLE
                layoutSemInternet.visibility = View.GONE
            }
        }
        // Segundo: verificar internet e autenticação para atualizar os dados
        checkInternetAndAuthentication()
    }
    private fun initializeSharedPreferences() {
        sharedPreferences = requireContext().getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
    }
    private fun checkInternetAndAuthentication() {
        if (!hasInternetConnection()) {
            // Se não tem internet, e não temos dados offline, mostra tela de erro
            if (!hasOfflineProfileData()) {
                showNoInternetUI()
            }
            return
        }
        // Tem internet, então tenta autenticar e buscar dados atualizados
        layoutSemInternet.visibility = View.GONE
        profileCard.visibility = View.GONE
        performAuthCheck()
    }
    private fun loadOfflineProfileData() {
        if (hasOfflineProfileData()) {
            val savedData = getOfflineProfileData()
            if (savedData != null) {
                displayProfileData(savedData)
                loadProfileImage() // Carrega a imagem do perfil do cache e tenta atualizar se houver internet
                profileCard.visibility = View.VISIBLE
                layoutSemInternet.visibility = View.GONE
                return
            }
        }
        showNoInternetUI()
    }
    private fun hasOfflineProfileData(): Boolean {
        return sharedPreferences.getBoolean(PROFILE_HAS_DATA_KEY, false)
    }
    private fun getOfflineProfileData(): JSONObject? {
        return try {
            val jsonString = sharedPreferences.getString(PROFILE_DATA_KEY, null)
            if (jsonString != null) {
                JSONObject(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Erro ao carregar dados offline", e)
            null
        }
    }
    private fun saveProfileDataOffline(profileData: JSONObject) {
        try {
            sharedPreferences.edit {
                putString(PROFILE_DATA_KEY, profileData.toString())
                putLong(PROFILE_LAST_UPDATE_KEY, System.currentTimeMillis())
                putBoolean(PROFILE_HAS_DATA_KEY, true)
            }
            Log.d("ProfileFragment", "Dados do perfil salvos offline com sucesso")
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Erro ao salvar dados offline", e)
        }
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
                        // Não autenticado, mas temos dados offline? Mantém os dados offline.
                        if (!hasOfflineProfileData()) {
                            showNoInternetUI()
                        }
                    }
                    authWebView.destroy()
                }
            }
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Se falhar, mas temos dados offline, não faz nada (já estão sendo exibidos)
                if (!hasOfflineProfileData()) {
                    showNoInternetUI()
                }
                authWebView.destroy()
            }
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (!hasOfflineProfileData()) {
                    showNoInternetUI()
                }
                authWebView.destroy()
            }
        }
        authWebView.loadUrl(AUTH_CHECK_URL)
    }
    private fun showNoInternetUI() {
        handler.post {
            profileCard.visibility = View.GONE
            layoutSemInternet.visibility = View.VISIBLE
        }
    }
    private fun hasInternetConnection(): Boolean {
        val context = context ?: return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.javaScriptCanOpenWindowsAutomatically = true
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
                // Já temos os dados offline? Não faz nada, pois já estão exibidos.
                if (!hasOfflineProfileData()) {
                    loadOfflineProfileData()
                }
            }
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (!hasOfflineProfileData()) {
                    loadOfflineProfileData()
                }
            }
        }
    }
    private fun processExtractedData(rawResult: String) {
        try {
            var jsonStr = rawResult
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\t", "")
            if (jsonStr == "null" || jsonStr.isEmpty()) {
                // Se não extraiu dados, mas temos cache, não faz nada (mantém cache)
                if (!hasOfflineProfileData()) {
                    loadOfflineProfileData()
                }
                return
            }
            if (!jsonStr.startsWith("{") || !jsonStr.endsWith("}")) {
                jsonStr = "{$jsonStr}"
            }
            val data = JSONObject(jsonStr)
            if (data.has("error")) {
                val errorMsg = data.getString("error")
                Log.e("ProfileFragment", "Erro JavaScript: $errorMsg")
                // Se tem cache, não faz nada. Senão, mostra tela de erro.
                if (!hasOfflineProfileData()) {
                    loadOfflineProfileData()
                }
                return
            }
            if (isValidProfileData(data)) {
                saveProfileDataOffline(data)
                // Atualiza a UI com os novos dados
                displayProfileData(data)
                loadProfileImage() // Carrega a imagem do perfil (primeiro do cache, depois tenta atualizar)
                profileCard.visibility = View.VISIBLE
                layoutSemInternet.visibility = View.GONE
            } else {
                // Dados inválidos, mas temos cache? Mantém o cache.
                if (!hasOfflineProfileData()) {
                    loadOfflineProfileData()
                }
            }
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Erro no parsing: $rawResult", e)
            if (!hasOfflineProfileData()) {
                loadOfflineProfileData()
            }
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
    private fun loadProfilePage() {
        val url = "https://areaexclusiva.colegioetapa.com.br/profile"
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        CookieManager.getInstance().flush()
        webView.loadUrl(url)
    }
    private fun displayProfileData(profileData: JSONObject) {
        profileContainer.removeAllViews()
        val fields = listOf(
            "Aluno", "Matrícula", "Unidade",
            "Período", "Sala", "Grau", "Série/Ano", "Nº chamada"
        )
        var displayedFields = 0
        fields.forEach { field ->
            if (profileData.has(field) && !profileData.getString(field).isNullOrBlank()) {
                addProfileItem(field, profileData.getString(field))
                displayedFields++
            }
        }
        if (displayedFields == 0) {
            showError()
        }
    }
    @SuppressLint("SetTextI18n")
    private fun addProfileItem(label: String, value: String) {
        val itemView = layoutInflater.inflate(R.layout.item_profile, profileContainer, false)
        val labelView: TextView = itemView.findViewById(R.id.itemLabel)
        val valueView: TextView = itemView.findViewById(R.id.itemValue)
        labelView.text = "$label:"
        valueView.text = value
        profileContainer.addView(itemView)
    }
    private fun loadProfileImage() {
        // Tenta carregar a imagem salva localmente
        val savedImagePath = sharedPreferences.getString(PROFILE_IMAGE_KEY, null)
        if (savedImagePath != null) {
            val file = File(savedImagePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ivProfilePhoto.setImageBitmap(bitmap)
                // Se tivermos internet, vamos tentar atualizar a imagem em segundo plano
                if (hasInternetConnection()) {
                    fetchProfileImage() // Atualiza a imagem se necessário
                }
                return
            }
        }
        // Se não houver imagem no cache, e tivermos internet, tenta buscar
        if (hasInternetConnection()) {
            fetchProfileImage()
        }
    }
    private fun fetchProfileImage() {
        // Usar o lifecycleScope da view para garantir cancelamento
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Verifica se o fragment ainda está anexado
                if (!isAdded) {
                    return@launch
                }
                // Obtém os cookies do WebView
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://areaexclusiva.colegioetapa.com.br") ?: ""
                if (cookies.isEmpty()) {
                    Log.d("ProfileFragment", "Cookies não encontrados")
                    return@launch
                }
                // Faz o scraping da página de perfil
                val doc = Jsoup.connect("https://areaexclusiva.colegioetapa.com.br/profile")
                    .header("Cookie", cookies)
                    .get()
                // Encontra a imagem do perfil
                val imgElement = doc.selectFirst("div.d-flex.justify-content-center img.rounded-circle")
                val imgUrl = imgElement?.attr("src") ?: ""
                if (imgUrl.isNotEmpty()) {
                    Log.d("ProfileFragment", "Imagem encontrada: $imgUrl")
                    // Baixa a imagem
                    val bitmap = downloadImage(imgUrl, cookies)
                    if (bitmap != null) {
                        // Salva a imagem localmente
                        val savedPath = saveImageToCache(bitmap)
                        // Atualiza a UI, mas verifica se o fragment ainda está ativo
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                ivProfilePhoto.setImageBitmap(bitmap)
                            }
                        }
                        // Salva o caminho da imagem nas preferências
                        sharedPreferences.edit { putString(PROFILE_IMAGE_KEY, savedPath) }
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Erro ao carregar imagem do perfil", e)
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
            Log.e("ProfileFragment", "Erro ao baixar imagem", e)
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
            Log.e("ProfileFragment", "Erro ao salvar imagem", e)
            ""
        }
    }
    private fun showError() {
        handler.post {
            profileCard.visibility = View.GONE
        }
    }
    private fun navigateToHomeFragment() {
        (activity as? MainActivity)?.navigateToHome()
    }
    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}