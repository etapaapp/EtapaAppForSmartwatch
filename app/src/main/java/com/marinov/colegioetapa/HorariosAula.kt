package com.marinov.colegioetapa

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Calendar

class HorariosAula : Fragment(), LoginStateListener {

    private companion object {
        const val URL_HORARIOS = "https://areaexclusiva.colegioetapa.com.br/horarios/aulas"
        const val PREFS = "horarios_prefs"
        const val KEY_HTML = "cache_html_horarios"
        const val KEY_ALERT = "cache_alert_message"
        const val ALERT_SELECTOR = "div.alert.alert-info.alert-font.text-center.m-0"
        const val TABLE_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > " +
                "div > div.card-body > table"
    }

    private lateinit var barOffline: LinearLayout
    private lateinit var messageContainer: LinearLayout
    private lateinit var tvMessage: TextView
    private lateinit var scrollContainer: androidx.core.widget.NestedScrollView
    private lateinit var classesContainer: LinearLayout
    private lateinit var daySelector: LinearLayout
    private lateinit var cache: CacheHelper

    // Dados das aulas organizados por dia
    private val weekData = mutableMapOf<Int, MutableList<ClassInfo>>()
    private var currentDay = 1 // Segunda = 1, Terça = 2, etc.

    // Botões dos dias
    private val dayButtons = mutableListOf<MaterialButton>()
    private val dayNames = listOf("Segunda", "Terça", "Quarta", "Quinta", "Sexta")
    private val dayAbbrev = listOf("SEG", "TER", "QUA", "QUI", "SEX")

    data class ClassInfo(
        val time: String,
        val teacher: String,
        val isSpecial: Boolean = false // Para provas, intervalos, etc.
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_horarios, container, false)

        barOffline = root.findViewById(R.id.barOffline)
        messageContainer = root.findViewById(R.id.messageContainer)
        tvMessage = root.findViewById(R.id.tvMessage)
        scrollContainer = root.findViewById(R.id.scrollContainer)
        classesContainer = root.findViewById(R.id.classesContainer)
        daySelector = root.findViewById(R.id.daySelector)

        // Seleção automática do dia da semana atual
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // SUNDAY = 1, MONDAY = 2, ...

        currentDay = when (dayOfWeek) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            else -> 1 // Default to Monday for Saturday/Sunday
        }

        val btnLogin: MaterialButton = root.findViewById(R.id.btnLogin)
        cache = CacheHelper(requireContext())

        btnLogin.setOnClickListener {
            (activity as? MainActivity)?.navigateToHome()
        }

        setupDaySelector()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                (activity as? MainActivity)?.navigateToHome()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        fetchHorarios()
    }

    override fun onLoginSuccess() {
        Log.d("HorariosAula", "Login detectado, recarregando horários.")
        fetchHorarios()
    }

    private fun setupDaySelector() {
        dayButtons.clear()
        daySelector.removeAllViews()

        dayAbbrev.forEachIndexed { index, dayAbbr ->
            val button = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = dayAbbr
                textSize = 10f
                isAllCaps = true
                minimumWidth = 0
                minWidth = 0

                // Tornar o botão circular
                val size = (48 * resources.displayMetrics.density).toInt()
                val params = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(6, 8, 6, 8)
                }
                layoutParams = params

                cornerRadius = size / 2
                insetTop = 0
                insetBottom = 0
                iconPadding = 0

                // Garantir que o padding interno seja adequado para texto
                setPadding(0, 0, 0, 0)

                setOnClickListener {
                    selectDay(index + 1)
                }
            }
            dayButtons.add(button)
            daySelector.addView(button)
        }

        selectDay(currentDay)
    }

    private fun selectDay(dayIndex: Int) {
        currentDay = dayIndex

        // Atualizar visual dos botões
        dayButtons.forEachIndexed { index, button ->
            if (index + 1 == dayIndex) {
                button.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.colorPrimary)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnPrimary))
                button.strokeWidth = 0
            } else {
                button.backgroundTintList = ContextCompat.getColorStateList(requireContext(), android.R.color.transparent)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))
                button.strokeColor = ContextCompat.getColorStateList(requireContext(), R.color.colorPrimary)
                button.strokeWidth = 2
            }
        }

        displayCurrentDay()
    }

    private fun displayCurrentDay() {
        if (!isAdded) return

        classesContainer.removeAllViews()
        val classes = weekData[currentDay] ?: emptyList()

        if (classes.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = "Não foram encontradas aulas para esse dia."
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                setPadding(16, 32, 16, 32)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnSurface))
            }
            classesContainer.addView(emptyView)
            return
        }

        classes.forEach { classInfo ->
            val classCard = createClassCard(classInfo)
            classesContainer.addView(classCard)
        }
    }

    private fun createClassCard(classInfo: ClassInfo): MaterialCardView {
        val card = MaterialCardView(requireContext()).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            layoutParams = params
            cardElevation = 4f
            radius = 12f
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
        }

        // Horário
        val timeView = TextView(requireContext()).apply {
            text = classInfo.time
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary))

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 16, 0)
            }
            layoutParams = params
            minWidth = (80 * resources.displayMetrics.density).toInt()
        }

        // Professor/Matéria
        val teacherView = TextView(requireContext()).apply {
            text = classInfo.teacher
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnSurface))

            val params = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            layoutParams = params

            if (classInfo.isSpecial) {
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                setBackgroundResource(R.drawable.bg_primary_rounded)
                setPadding(12, 8, 12, 8)
            }
        }

        container.addView(timeView)
        container.addView(teacherView)
        card.addView(container)

        return card
    }

    private fun fetchHorarios() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded) return@launch

            try {
                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(URL_HORARIOS)
                        Jsoup.connect(URL_HORARIOS)
                            .header("Cookie", cookieHeader ?: "")
                            .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("HorariosAula", "Erro ao conectar", e)
                        null
                    }
                }

                if (!isAdded) return@launch

                if (doc != null) {
                    val table = doc.selectFirst(TABLE_SELECTOR)

                    if (table != null) {
                        val dataRows = table.select("tbody > tr").filter { row ->
                            !row.select("div.alert-info").any() || row.select("td.table-field").any { cell ->
                                val text = cell.text().trim()
                                text != "-" && !text.contains("Intervalo", ignoreCase = true) &&
                                        !text.contains("não há aulas", ignoreCase = true)
                            }
                        }

                        if (dataRows.isNotEmpty()) {
                            cache.saveHtml(table.outerHtml())
                            cache.clearAlertMessage()
                            parseTableData(table)
                            hideOfflineBar()
                            showSchedule()
                        } else {
                            val alert = doc.selectFirst(ALERT_SELECTOR)
                            if (alert != null) {
                                cache.saveAlertMessage(alert.text())
                                cache.clearHtml()
                                showNoClassesMessage(alert.text())
                                hideOfflineBar()
                            } else {
                                showOfflineBar()
                                loadCachedData()
                            }
                        }
                    } else {
                        val alert = doc.selectFirst(ALERT_SELECTOR)
                        if (alert != null) {
                            cache.saveAlertMessage(alert.text())
                            cache.clearHtml()
                            showNoClassesMessage(alert.text())
                            hideOfflineBar()
                        } else {
                            showOfflineBar()
                            Log.e("HorariosAula", "Elementos não encontrados no site")
                            loadCachedData()
                        }
                    }
                } else {
                    showOfflineBar()
                    Log.e("HorariosAula", "Falha na conexão")
                    loadCachedData()
                }
            } catch (e: Exception) {
                Log.e("HorariosAula", "Erro inesperado", e)
                if (isAdded) {
                    showOfflineBar()
                    loadCachedData()
                }
            }
        }
    }

    private fun parseTableData(table: Element) {
        if (!isAdded) return

        weekData.clear()

        val rows = table.select("tbody > tr")

        for (tr in rows) {
            if (tr.select("div.alert-info").isNotEmpty()) continue

            val cells = tr.select("td, th")
            if (cells.size < 6) continue

            val timeCell = cells[0].text().trim()
            if (timeCell.isEmpty() || timeCell.equals("Horários", ignoreCase = true)) continue

            // Processar cada dia da semana (Segunda a Sexta = células 1 a 5)
            for (dayIndex in 1..5) {
                if (dayIndex < cells.size) {
                    val cellText = cells[dayIndex].text().trim()
                    val isSpecial = cells[dayIndex].hasClass("bg-primary") ||
                            cellText.contains("Prova", ignoreCase = true) ||
                            cellText.contains("Intervalo", ignoreCase = true)

                    if (cellText.isNotEmpty() && cellText != "-") {
                        val classInfo = ClassInfo(timeCell, cellText, isSpecial)

                        if (!weekData.containsKey(dayIndex)) {
                            weekData[dayIndex] = mutableListOf()
                        }
                        weekData[dayIndex]?.add(classInfo)
                    }
                }
            }
        }
    }

    private fun loadCachedData() {
        val html = cache.loadHtml()
        if (html != null) {
            try {
                val table = Jsoup.parse(html).selectFirst("table")
                if (table != null) {
                    parseTableData(table)
                    showSchedule()
                    return
                }
            } catch (e: Exception) {
                Log.e("HorariosAula", "Erro ao processar cache da tabela", e)
            }
        }

        val alertMessage = cache.loadAlertMessage()
        if (!alertMessage.isNullOrEmpty()) {
            showNoClassesMessage(alertMessage)
            return
        }
    }

    private fun showSchedule() {
        if (!isAdded) return
        hideMessage()
        scrollContainer.visibility = View.VISIBLE
        daySelector.visibility = View.VISIBLE
        displayCurrentDay()
    }

    private fun showNoClassesMessage(message: String) {
        if (!isAdded) return
        scrollContainer.visibility = View.GONE
        daySelector.visibility = View.GONE
        tvMessage.text = message
        messageContainer.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        if (!isAdded) return
        messageContainer.visibility = View.GONE
    }

    private fun showOfflineBar() {
        if (!isAdded) return
        barOffline.visibility = View.VISIBLE
    }

    private fun hideOfflineBar() {
        if (!isAdded) return
        barOffline.visibility = View.GONE
    }

    private inner class CacheHelper(context: Context) {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun saveHtml(html: String) {
            prefs.edit { putString(KEY_HTML, html) }
        }

        fun saveAlertMessage(message: String) {
            prefs.edit { putString(KEY_ALERT, message) }
        }

        fun clearHtml() {
            prefs.edit { remove(KEY_HTML) }
        }

        fun clearAlertMessage() {
            prefs.edit { remove(KEY_ALERT) }
        }

        fun loadHtml(): String? {
            return prefs.getString(KEY_HTML, null)
        }

        fun loadAlertMessage(): String? {
            return prefs.getString(KEY_ALERT, null)
        }
    }
}
