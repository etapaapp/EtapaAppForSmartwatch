package com.marinov.colegioetapa

import android.content.Context
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Calendar

class HomeFragment : Fragment() {

    private var layoutSemInternet: LinearLayout? = null
    private var btnTentarNovamente: MaterialButton? = null
    private var loadingContainer: View? = null
    private var contentContainer: View? = null
    private var txtStuckHint: TextView? = null
    private var topLoadingBar: View? = null
    private var recentGradesContainer: LinearLayout? = null

    private var shouldBlockNavigation = false
    private var isFragmentDestroyed = false
    private var hasBeenVisible = false
    private var isDataLoaded = false

    private var recentGradesCache: List<NotaRecente> = emptyList()

    private val handler = Handler(Looper.getMainLooper())

    data class ProvaCalendario(val data: Calendar, val codigo: String, val conjunto: Int)
    data class Nota(val codigo: String, val conjunto: Int, val valor: String)
    data class NotaRecente(val codigo: String, val conjunto: String, val nota: String, val data: Calendar)

    private companion object {
        const val PREFS_NAME = "HomeFragmentCache"
        const val KEY_RECENT_GRADES = "recent_grades"
        const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
        const val HOME_URL = "https://areaexclusiva.colegioetapa.com.br/home"
        const val NOTAS_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val CALENDARIO_URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/datas"
        const val LOGIN_URL = "https://areaexclusiva.colegioetapa.com.br"
        const val MAX_RECENT_GRADES = 3
        const val MESES = 12
        const val TAG = "HomeFragment"
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context !is WebViewFragment.LoginSuccessListener) {
            throw RuntimeException("$context deve implementar LoginSuccessListener")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home_watch, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isFragmentDestroyed = false
        shouldBlockNavigation = false
        initializeViews(view)
        setupListeners()
        checkInternetAndLoadData()
    }

    override fun onPause() {
        super.onPause()
        shouldBlockNavigation = true
    }

    override fun onResume() {
        super.onResume()
        shouldBlockNavigation = false
        if (hasBeenVisible && !isDataLoaded) {
            checkInternetAndLoadData()
        }
        hasBeenVisible = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isFragmentDestroyed = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun initializeViews(view: View) {
        loadingContainer = view.findViewById(R.id.loadingContainer)
        contentContainer = view.findViewById(R.id.contentContainer)
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        txtStuckHint = view.findViewById(R.id.txtStuckHint)
        topLoadingBar = view.findViewById(R.id.top_loading_bar)
        recentGradesContainer = view.findViewById(R.id.recentGradesContainer)
    }

    private fun setupListeners() {
        btnTentarNovamente?.setOnClickListener {
            isDataLoaded = false
            checkInternetAndLoadData()
        }
    }

    private fun checkInternetAndLoadData() {
        if (hasInternetConnection()) {
            if (!isDataLoaded) {
                val hasCache = loadCache()
                if (hasCache) {
                    showContentState()
                    updateUiWithCurrentData()
                    isDataLoaded = true
                } else {
                    showLoadingState()
                }
            }
            fetchDataInBackground()
        } else {
            showOfflineState()
        }
    }

    private fun fetchDataInBackground() {
        if (contentContainer?.visibility == View.VISIBLE) {
            topLoadingBar?.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Iniciando busca de dados...")
                val homeDoc = async(Dispatchers.IO) { fetchPageData(HOME_URL) }.await()
                if (isFragmentDestroyed) return@launch

                Log.d(TAG, "Verificando se a sessão é válida...")
                if (isValidSession(homeDoc)) {
                    Log.d(TAG, "Sessão válida - buscando dados completos")
                    val gradesDoc = async(Dispatchers.IO) { fetchPageData(NOTAS_URL) }
                    val calendarDocsDeferred = (1..MESES).map { mes ->
                        async(Dispatchers.IO) { fetchPageData("$CALENDARIO_URL_BASE?mes%5B%5D=$mes") }
                    }

                    val calendarDocs = try { awaitAll(*calendarDocsDeferred.toTypedArray()) }
                    catch (e: Exception) { emptyList() }

                    val allExams = parseAllCalendarData(calendarDocs)
                    val allGrades = parseAllGradesData(gradesDoc.await())
                    val recentGrades = findRecentGrades(allExams, allGrades)

                    saveRecentGradesCache(recentGrades)
                    recentGradesCache = recentGrades

                    withContext(Dispatchers.Main) {
                        if (isFragmentDestroyed) return@withContext
                        setupRecentGrades(recentGrades)
                    }

                    isDataLoaded = true
                } else {
                    Log.d(TAG, "Sessão inválida - redirecionando para login")
                    withContext(Dispatchers.Main) {
                        handleInvalidSession()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao buscar dados", e)
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) handleDataFetchError(e)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFragmentDestroyed) {
                        topLoadingBar?.visibility = View.GONE
                        if (isDataLoaded) {
                            showContentState()
                        }
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun fetchPageData(url: String): Document? {
        return try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            Log.d(TAG, "Fazendo requisição para: $url")
            Log.d(TAG, "Cookies: ${cookies?.take(100) ?: "nenhum"}")

            Jsoup.connect(url)
                .header("Cookie", cookies ?: "")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/536.36")
                .timeout(20000)
                .get()
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao buscar dados de $url: ${e.message}")
            null
        }
    }

    private fun parseAllCalendarData(docs: List<Document?>): List<ProvaCalendario> {
        val allExams = mutableListOf<ProvaCalendario>()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        docs.filterNotNull().forEach { doc ->
            val table = doc.selectFirst("table") ?: return@forEach
            val rows = table.select("tbody > tr")
            for (tr in rows) {
                val cells = tr.children()
                if (cells.size < 5) continue

                try {
                    val tipo = cells[2].text().lowercase()
                    if (tipo.contains("rec")) continue

                    val dataStr = cells[0].text().split(" ")[0]
                    val codigo = cells[1].ownText()
                    val conjuntoStr = cells[3].text().filter { it.isDigit() }

                    if (dataStr.contains('/') && conjuntoStr.isNotEmpty()) {
                        val dataParts = dataStr.split("/")
                        val day = dataParts[0].toInt()
                        val month = dataParts[1].toInt() - 1
                        val conjunto = conjuntoStr.toInt()

                        val calendar = Calendar.getInstance().apply {
                            set(currentYear, month, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        allExams.add(ProvaCalendario(calendar, codigo, conjunto))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao parsear linha do calendário: ${tr.text()}", e)
                }
            }
        }
        return allExams
    }

    private fun parseAllGradesData(doc: Document?): List<Nota> {
        if (doc == null) return emptyList()
        val allGrades = mutableListOf<Nota>()
        val table = doc.selectFirst("table") ?: return emptyList()

        val headers = table.select("thead th")
        val conjuntoMap = mutableMapOf<Int, Int>()
        headers.forEachIndexed { index, th ->
            if (index > 1) {
                val conjunto = th.text().filter { it.isDigit() }.toIntOrNull()
                if (conjunto != null) conjuntoMap[index] = conjunto
            }
        }

        val rows = table.select("tbody > tr")
        for (tr in rows) {
            val cols = tr.children()
            if (cols.size <= 1) continue
            val codigo = cols[1].text()

            cols.forEachIndexed { colIndex, td ->
                conjuntoMap[colIndex]?.let { conjunto ->
                    val nota = td.text()
                    if (nota.isNotEmpty()) {
                        allGrades.add(Nota(codigo, conjunto, nota))
                    }
                }
            }
        }
        return allGrades
    }

    private fun findRecentGrades(allExams: List<ProvaCalendario>, allGrades: List<Nota>): List<NotaRecente> {
        if (allExams.isEmpty() || allGrades.isEmpty()) return emptyList()

        val gradesMap = allGrades.associateBy { "${it.codigo}-${it.conjunto}" }
        val today = Calendar.getInstance()

        return allExams
            .filter { it.data.before(today) || it.data == today }
            .sortedByDescending { it.data }
            .mapNotNull { exam ->
                val key = "${exam.codigo}-${exam.conjunto}"
                gradesMap[key]?.let { nota ->
                    if (nota.valor != "--") {
                        NotaRecente(
                            exam.codigo,
                            exam.conjunto.toString(),
                            nota.valor,
                            exam.data
                        )
                    } else null
                }
            }
            .distinctBy { "${it.codigo}-${it.conjunto}" }
            .take(MAX_RECENT_GRADES)
    }

    private fun updateUiWithCurrentData() {
        if (isFragmentDestroyed) return
        setupRecentGrades(recentGradesCache)
    }

    private fun setupRecentGrades(grades: List<NotaRecente>) {
        if (isFragmentDestroyed || recentGradesContainer == null) return
        val context = context ?: return

        recentGradesContainer?.removeAllViews()

        if (grades.isEmpty()) {
            recentGradesContainer?.visibility = View.GONE
            return
        }

        recentGradesContainer?.visibility = View.VISIBLE

        for (grade in grades) {
            val gradeView = LayoutInflater.from(context)
                .inflate(R.layout.item_recent_grade_watch, recentGradesContainer, false)

            gradeView.findViewById<TextView>(R.id.tv_codigo).text = grade.codigo
            gradeView.findViewById<TextView>(R.id.tv_conjunto).text = "Conj. ${grade.conjunto}"
            gradeView.findViewById<TextView>(R.id.tv_nota).text = grade.nota

            recentGradesContainer?.addView(gradeView)
        }
    }

    fun onLoginSuccess() {
        Log.d(TAG, "Login bem-sucedido - forçando recarregamento")
        clearCache()
        isDataLoaded = false
        checkInternetAndLoadData()
    }

    private fun isValidSession(doc: Document?): Boolean {
        if (doc == null) {
            Log.d(TAG, "Documento nulo - sessão inválida")
            return false
        }

        // Verificar se existe o elemento característico da página home logada
        val homeCarousel = doc.getElementById("home_banners_carousel")
        val isValid = homeCarousel != null

        Log.d(TAG, "Verificação de sessão: ${if (isValid) "válida" else "inválida"}")
        if (!isValid) {
            Log.d(TAG, "Elementos encontrados: ${doc.select("body").first()?.tagName() ?: "nenhum"}")
        }

        return isValid
    }

    private fun handleInvalidSession() {
        if (isFragmentDestroyed || shouldBlockNavigation) {
            Log.d(TAG, "Navegação bloqueada - fragment destruído ou pausado")
            return
        }

        Log.d(TAG, "Limpando cache e redirecionando para login")
        clearCache()
        isDataLoaded = false
        navigateToWebView(LOGIN_URL)
    }

    private fun handleDataFetchError(e: Exception) {
        if (isFragmentDestroyed) return
        Log.e(TAG, "Erro ao buscar dados: ${e.message}", e)
        if (!isDataLoaded) {
            if (loadCache()) {
                showContentState()
                updateUiWithCurrentData()
            } else {
                showOfflineState()
            }
        }
    }

    private fun saveRecentGradesCache(grades: List<NotaRecente>) {
        if (isFragmentDestroyed) return
        val context = context ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            val gson = Gson()
            putString(KEY_RECENT_GRADES, gson.toJson(grades))
            putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
        }
    }

    private fun loadCache(): Boolean {
        if (isFragmentDestroyed) return false
        val context = context ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheTimestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        if (System.currentTimeMillis() - cacheTimestamp > 24 * 60 * 60 * 1000L) {
            clearCache()
            return false
        }
        val gson = Gson()
        val recentGradesJson = prefs.getString(KEY_RECENT_GRADES, null)

        if (recentGradesJson != null) {
            val recentGradesType = object : TypeToken<List<NotaRecente>>() {}.type
            recentGradesCache = gson.fromJson(recentGradesJson, recentGradesType)
            return true
        }
        return false
    }

    private fun clearCache() {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit {
            clear()
            apply()
        }
        recentGradesCache = emptyList()
    }

    private fun navigateToWebView(url: String) {
        if (shouldBlockNavigation || isFragmentDestroyed) {
            Log.d(TAG, "Navegação bloqueada - shouldBlock: $shouldBlockNavigation, destroyed: $isFragmentDestroyed")
            return
        }

        try {
            Log.d(TAG, "Navegando para WebView com URL: $url")
            val activity = activity as? MainActivity
            if (activity == null) {
                Log.e(TAG, "MainActivity não disponível")
                return
            }

            val fragment = WebViewFragment().apply {
                arguments = WebViewFragment.createArgs(url)
            }
            activity.openCustomFragment(fragment)
            Log.d(TAG, "Fragment WebView aberto com sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao navegar para WebView", e)
        }
    }

    private fun showLoadingState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.VISIBLE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.GONE
    }

    private fun showContentState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE
        layoutSemInternet?.visibility = View.GONE
    }

    private fun showOfflineState() {
        if (isFragmentDestroyed) return
        loadingContainer?.visibility = View.GONE
        contentContainer?.visibility = View.GONE
        layoutSemInternet?.visibility = View.VISIBLE
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}