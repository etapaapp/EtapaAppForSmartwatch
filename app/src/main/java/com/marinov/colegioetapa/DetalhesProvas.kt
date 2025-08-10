package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import androidx.core.content.edit

class DetalhesProvas : Fragment() {

    private companion object {
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/detalhes"
        const val PREFS = "detalhes_prefs"
        const val KEY_CACHE = "cache_html_detalhes"
        const val ELEMENTO_CRITICO_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0"
    }

    private lateinit var recyclerDetalhes: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var telaOffline: View
    private lateinit var txtSemProvas: TextView
    private lateinit var txtSemDados: TextView
    private lateinit var spinnerConjunto: Spinner
    private lateinit var spinnerMateria: Spinner
    private lateinit var btnBuscar: MaterialButton
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var adapter: DetalhesAdapter
    private lateinit var contentLayout: View

    private var conjuntoSelecionado = ""
    private var materiaSelecionada = ""
    private var elementoCriticoPresente = false
    private val materiasMap = mapOf(
        "Biologia" to "b",
        "Computação" to "cp",
        "Ed. Artística" to "ea",
        "Física" to "f",
        "Geografia" to "g",
        "História" to "h",
        "Inglês" to "i",
        "Lab. Informática" to "li",
        "Lab. Biologia" to "lb",
        "Link Tema" to "lt",
        "Matemática" to "m",
        "Compl. Matemática" to "cm",
        "Líng. Port. / Lit." to "p",
        "Prova Geral" to "pg",
        "Prática Redacional" to "pr",
        "Química" to "q",
        "Simulado" to "si",
        "Temas Diversos" to "t",
        "Tópicos Especiais" to "te"
    )

    private val tiposProva = listOf(
        "Prova Normal" to 1,
        "Recuperação 1" to 3,
        "Recuperação 2" to 5
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_detalhes_provas, container, false)

        recyclerDetalhes = root.findViewById(R.id.recyclerDetalhes)
        progressBar = root.findViewById(R.id.progressBar)
        telaOffline = root.findViewById(R.id.telaOffline)
        txtSemProvas = root.findViewById(R.id.txtSemProvas)
        txtSemDados = root.findViewById(R.id.txtSemDados)
        spinnerConjunto = root.findViewById(R.id.spinnerConjunto)
        spinnerMateria = root.findViewById(R.id.spinnerMateria)
        btnBuscar = root.findViewById(R.id.btnBuscar)
        btnTentarNovamente = root.findViewById(R.id.btn_tentar_novamente)
        contentLayout = root.findViewById(R.id.contentLayout)

        setupRecyclerView()
        configurarSpinners()

        btnBuscar.setOnClickListener {
            if (conjuntoSelecionado.isNotEmpty() && materiaSelecionada.isNotEmpty()) {
                buscarDetalhes()
            } else {
                exibirInstrucao()
            }
        }

        btnTentarNovamente.setOnClickListener {
            voltarParaHome()
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                voltarParaHome()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        verificarElementoCritico()
    }

    private fun voltarParaHome() {
        (activity as? MainActivity)?.navigateToHome()
    }

    private fun verificarElementoCritico() {
        if (!isOnline()) {
            exibirTelaOffline()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            exibirCarregando()

            try {
                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(URL_BASE)
                        Jsoup.connect(URL_BASE)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("DetalhesProvas", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        exibirInstrucao()
                    } else {
                        exibirTelaOffline()
                    }
                } else {
                    exibirTelaOffline()
                }
            } catch (_: Exception) {
                progressBar.visibility = View.GONE
                exibirTelaOffline()
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerDetalhes.layoutManager = LinearLayoutManager(requireContext())
        adapter = DetalhesAdapter(emptyList())
        recyclerDetalhes.adapter = adapter
    }

    private fun configurarSpinners() {
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.conjuntos_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerConjunto.adapter = adapter
        }

        spinnerConjunto.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                conjuntoSelecionado = if (position > 0) position.toString() else ""
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                conjuntoSelecionado = ""
            }
        }

        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.materias_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerMateria.adapter = adapter
        }

        spinnerMateria.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                materiaSelecionada = if (position > 0) {
                    val materia = parent?.getItemAtPosition(position).toString()
                    materiasMap[materia] ?: ""
                } else ""
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                materiaSelecionada = ""
            }
        }

        spinnerConjunto.setSelection(0)
        spinnerMateria.setSelection(0)
    }

    private fun buscarDetalhes() {
        if (!isOnline() || !elementoCriticoPresente) {
            exibirTelaOffline()
            return
        }

        val url = "$URL_BASE?conjunto=$conjuntoSelecionado&materia=$materiaSelecionada"
        fetchDetalhes(url)
    }

    private fun fetchDetalhes(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                exibirCarregando()

                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(url)
                        Jsoup.connect(url)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("DetalhesProvas", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    if (doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) == null) {
                        elementoCriticoPresente = false
                        exibirTelaOffline()
                        return@launch
                    }

                    val tableSelector = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div.card.my-5.bg-transparent.text-white.border-0 > table"
                    val table = doc.selectFirst(tableSelector)

                    if (table != null) {
                        if (contemMensagemSemDados(table)) {
                            exibirMensagemSemProvas()
                        } else {
                            salvarCache(table.outerHtml())
                            parseAndDisplayTable(table)
                            exibirConteudoOnline()
                        }
                    } else {
                        val alerta = doc.selectFirst("div.alert.alert-primary")
                        if (alerta != null && alerta.text().contains("selecione", true)) {
                            exibirInstrucao()
                        } else {
                            exibirMensagemSemProvas()
                        }
                    }
                } else {
                    carregarCache()
                }
            } catch (_: Exception) {
                progressBar.visibility = View.GONE
                carregarCache()
            }
        }
    }

    private fun contemMensagemSemDados(table: Element): Boolean {
        return table.select("td.alert-danger, div.alert-danger").any { element ->
            element.text().contains("Nenhum dado encontrado", ignoreCase = true)
        }
    }

    private fun parseAndDisplayTable(table: Element) {
        val items = mutableListOf<DetalheItem>()
        val rows = table.select("tbody > tr")

        for (tr in rows) {
            val cells = tr.children()
            if (cells.size < 7) continue

            if (tr.selectFirst("td.alert-danger, div.alert-danger") != null) continue

            val codigo = cells[0].text()

            for ((tipoProva, colunaInicial) in tiposProva) {
                val colunaDetalhe = colunaInicial + 1

                val nota = cells[colunaInicial].text()
                val detalhe = cells[colunaDetalhe]

                if (nota != "-") {
                    val button = detalhe.selectFirst("button")
                    if (button != null) {
                        val onclick = button.attr("onclick")
                        val url = onclick.substringAfter("'").substringBefore("'")
                        items.add(DetalheItem(codigo, tipoProva, nota, url))
                    }
                }
            }
        }

        if (items.isEmpty()) {
            exibirMensagemSemProvas()
        } else {
            adapter.updateData(items)
            exibirConteudoOnline()
        }
    }

    private fun salvarCache(html: String) {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_CACHE, html)
        }
    }

    private fun carregarCache() {
        val html = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CACHE, null)

        if (html != null) {
            val table = Jsoup.parse(html).selectFirst("table")
            if (table != null) {
                parseAndDisplayTable(table)
                return
            }
        }
        exibirSemDados()
    }

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        recyclerDetalhes.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        recyclerDetalhes.visibility = View.VISIBLE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        telaOffline.visibility = View.VISIBLE
        recyclerDetalhes.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        progressBar.visibility = View.GONE
        habilitarInterface(false)
    }

    @SuppressLint("SetTextI18n")
    private fun exibirMensagemSemProvas() {
        recyclerDetalhes.visibility = View.GONE
        txtSemProvas.text = "Nenhum cadastro de prova encontrado."
        txtSemProvas.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun exibirInstrucao() {
        recyclerDetalhes.visibility = View.GONE
        txtSemProvas.text = "Selecione o conjunto e a matéria."
        txtSemProvas.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirSemDados() {
        recyclerDetalhes.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.VISIBLE
        telaOffline.visibility = View.GONE
    }

    private fun habilitarInterface(habilitar: Boolean) {
        contentLayout.alpha = if (habilitar) 1f else 0.3f
        spinnerConjunto.isEnabled = habilitar
        spinnerMateria.isEnabled = habilitar
        btnBuscar.isEnabled = habilitar
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val netInfo: NetworkInfo? = cm.activeNetworkInfo
            netInfo != null && netInfo.isConnected
        } catch (_: Exception) {
            false
        }
    }

    private inner class DetalhesAdapter(
        private var items: List<DetalheItem>
    ) : RecyclerView.Adapter<DetalhesAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<DetalheItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_detalhe_prova, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtCodigo.text = item.codigo
            holder.txtTipo.text = item.tipo
            holder.txtNota.text = "Nota: ${item.nota}"
            holder.btnDetalhes.setOnClickListener {
                (activity as? MainActivity)?.abrirDetalhesProva(item.link)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val txtCodigo: TextView = itemView.findViewById(R.id.txt_codigo)
            val txtTipo: TextView = itemView.findViewById(R.id.txt_tipo)
            val txtNota: TextView = itemView.findViewById(R.id.txt_nota)
            val btnDetalhes: MaterialButton = itemView.findViewById(R.id.btn_detalhes)
        }
    }

    private data class DetalheItem(
        val codigo: String,
        val tipo: String,
        val nota: String,
        val link: String
    )
}