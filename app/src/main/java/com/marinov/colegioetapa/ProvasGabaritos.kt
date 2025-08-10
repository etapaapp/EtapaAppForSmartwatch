package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
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
import java.io.File

class ProvasGabaritos : Fragment() {

    private companion object {
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/provas/provas-gabaritos"
        const val PREFS = "provas_prefs"
        const val KEY_CACHE = "cache_html_provas"
        // Seletor do elemento crítico que precisa existir na página
        const val ELEMENTO_CRITICO_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div.card.mb-5.bg-transparent.border-0 > div.card-body"
    }

    private lateinit var recyclerProvas: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var telaOffline: View
    private lateinit var txtSemProvas: TextView
    private lateinit var txtSemDados: TextView
    private lateinit var spinnerConjunto: Spinner
    private lateinit var spinnerMateria: Spinner
    private lateinit var btnBuscar: MaterialButton
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var adapter: ProvasAdapter
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_provas_gabaritos, container, false)

        // Inicializar views
        recyclerProvas = root.findViewById(R.id.recyclerProvas)
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
                buscarProvas()
            } else {
                // Exibir instrução se não tiver seleção completa
                exibirInstrucao()
            }
        }

        // Botão "Tentar Novamente" agora volta para o HomeFragment
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

        // Verificar elemento crítico ao abrir o fragmento
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
                            .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("ProvasGabaritos", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        // Elemento crítico encontrado - exibir estado inicial
                        exibirInstrucao()
                        habilitarInterface(true)
                    } else {
                        // Elemento crítico não encontrado - exibir tela offline
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
        recyclerProvas.layoutManager = LinearLayoutManager(requireContext())
        adapter = ProvasAdapter(emptyList())
        recyclerProvas.adapter = adapter
    }

    private fun configurarSpinners() {
        // Configurar spinner de conjunto
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

        // Configurar spinner de matéria
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

        // Definir seleção inicial como "--"
        spinnerConjunto.setSelection(0)
        spinnerMateria.setSelection(0)
    }

    private fun buscarProvas() {
        if (!isOnline() || !elementoCriticoPresente) {
            exibirTelaOffline()
            return
        }

        val url = "$URL_BASE?conjunto=$conjuntoSelecionado&materia=$materiaSelecionada"
        fetchProvas(url)
    }

    private fun fetchProvas(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                exibirCarregando()

                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(url)
                        Jsoup.connect(url)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("ProvasGabaritos", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    // Verificar novamente o elemento crítico durante a busca
                    if (doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) == null) {
                        elementoCriticoPresente = false
                        exibirTelaOffline()
                        return@launch
                    }

                    val table = doc.selectFirst("table.table-striped")
                    if (table != null) {
                        // Verificar se há mensagem de "Nenhum dado encontrado"
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
        // Verificar se a tabela contém a mensagem "Nenhum dado encontrado"
        return table.select("td.alert-danger, div.alert-danger").any { element ->
            element.text().contains("Nenhum dado encontrado", ignoreCase = true)
        }
    }

    private fun parseAndDisplayTable(table: Element) {
        val items = mutableListOf<ProvaItem>()
        val rows = table.select("tbody > tr")

        for (tr in rows) {
            val cells = tr.children()
            if (cells.size < 4) continue

            // Pular linha de mensagem de erro se existir
            if (tr.selectFirst("td.alert-danger, div.alert-danger") != null) {
                continue
            }

            val codigo = cells[0].text()
            val turma = cells[1].text()
            val linkProva = cells[2].selectFirst("a")?.attr("href") ?: ""
            val linkGabarito = cells[3].selectFirst("a")?.attr("href") ?: ""

            if (codigo.isNotEmpty() && turma.isNotEmpty()) {
                items.add(ProvaItem(codigo, turma, linkProva, linkGabarito))
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
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        recyclerProvas.visibility = View.VISIBLE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        telaOffline.visibility = View.VISIBLE
        recyclerProvas.visibility = View.GONE
        txtSemProvas.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        progressBar.visibility = View.GONE
        habilitarInterface(false)
    }

    private fun exibirMensagemSemProvas() {
        recyclerProvas.visibility = View.GONE
        txtSemProvas.setText(R.string.nenhuma_prova_encontrada)
        txtSemProvas.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirInstrucao() {
        recyclerProvas.visibility = View.GONE
        txtSemProvas.setText(R.string.selecione_conjunto_materia)
        txtSemProvas.visibility = View.VISIBLE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun exibirSemDados() {
        recyclerProvas.visibility = View.GONE
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

            val activeNetwork = cm.activeNetwork ?: return false
            val networkCapabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    private inner class ProvasAdapter(
        private var items: List<ProvaItem>
    ) : RecyclerView.Adapter<ProvasAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<ProvaItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_prova_gabarito, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtCodigo.text = item.codigo
            holder.txtTurma.text = item.turma

            holder.btnProva.setOnClickListener {
                downloadArquivo(
                    url = item.linkProva,
                    nomeArquivo = "prova_${item.codigo}_${item.turma}.pdf",
                    titulo = "Prova ${item.codigo} - Turma ${item.turma}"
                )
            }

            holder.btnGabarito.setOnClickListener {
                downloadArquivo(
                    url = item.linkGabarito,
                    nomeArquivo = "gabarito_${item.codigo}_${item.turma}.pdf",
                    titulo = "Gabarito ${item.codigo} - Turma ${item.turma}"
                )
            }
        }

        override fun getItemCount(): Int {
            return items.size
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val txtCodigo: TextView = itemView.findViewById(R.id.txt_codigo)
            val txtTurma: TextView = itemView.findViewById(R.id.txt_turma)
            val btnProva: MaterialButton = itemView.findViewById(R.id.btn_prova)
            val btnGabarito: MaterialButton = itemView.findViewById(R.id.btn_gabarito)
        }
    }

    // Sistema de download
    private var downloadID: Long = -1
    private val downloadReceiver = object : BroadcastReceiver() {
        @SuppressLint("Range")
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadID) {
                val query = DownloadManager.Query()
                query.setFilterById(id)
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor = dm.query(query)

                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Toast.makeText(context, "Download concluído", Toast.LENGTH_SHORT).show()
                        }
                        DownloadManager.STATUS_FAILED -> {
                            Toast.makeText(context, "Download falhou", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                cursor.close()
            }
        }
    }

    private fun downloadArquivo(url: String, nomeArquivo: String, titulo: String) {
        if (!isOnline()) {
            Toast.makeText(requireContext(), "Sem conexão para download", Toast.LENGTH_SHORT).show()
            return
        }

        val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = url.toUri()

        val request = DownloadManager.Request(uri)
            .setTitle(titulo)
            .setDescription("Baixando arquivo")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nomeArquivo)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        // Configurar cookies para autenticação
        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies != null) {
            request.addRequestHeader("Cookie", cookies)
        }

        // Verificar se o arquivo já existe
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            nomeArquivo
        )

        if (file.exists()) {
            file.delete()
        }

        downloadID = downloadManager.enqueue(request)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            requireContext(),
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(downloadReceiver)
    }

    private data class ProvaItem(
        val codigo: String,
        val turma: String,
        val linkProva: String,
        val linkGabarito: String
    )
}