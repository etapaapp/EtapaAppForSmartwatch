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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
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
import org.jsoup.nodes.Document
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MaterialFragment : Fragment() {

    private companion object {
        const val TAG = "MaterialFragment"
        const val URL_BASE = "https://areaexclusiva.colegioetapa.com.br/material-complementar"
        const val ELEMENTO_CRITICO_SELECTOR =
            "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3"
    }

    private lateinit var recyclerMaterial: RecyclerView
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var adapter: MaterialAdapter

    private var elementoCriticoPresente = false
    private val navigationStack = mutableListOf<MaterialNode>()
    private var rootNode: MaterialNode? = null

    // Para acompanhar o download e notificar status
    private var downloadID: Long = -1

    private val downloadReceiver = object : BroadcastReceiver() {
        @SuppressLint("Range")
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadID) {
                val query = DownloadManager.Query().setFilterById(id)
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                        }
                        DownloadManager.STATUS_FAILED -> {
                        }
                    }
                }
                cursor.close()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_material, container, false)

        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)
        recyclerMaterial = view.findViewById(R.id.recyclerMaterial)
        progressBar = view.findViewById(R.id.progress_circular)

        recyclerMaterial.layoutManager = LinearLayoutManager(requireContext())
        adapter = MaterialAdapter(emptyList()) { item -> onItemClick(item) }
        recyclerMaterial.adapter = adapter

        btnTentarNovamente.setOnClickListener {
            navigateToHomeFragment()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigationStack.size > 1) {
                    navigationStack.removeAt(navigationStack.size - 1)
                    updateAdapterFromCurrentNode()
                } else {
                    navigateToHomeFragment()
                    isEnabled = false
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        if (hasInternetConnection()) {
            verificarElementoCritico()
        } else {
            showNoInternetUI()
        }
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

    private fun navigateToHomeFragment() {
        (activity as? MainActivity)?.navigateToHome()
    }

    private fun verificarElementoCritico() {
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
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
                        Log.e(TAG, "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        rootNode = parseMaterialData(doc)
                        navigationStack.clear()
                        navigationStack.add(rootNode!!)
                        updateAdapterFromCurrentNode()
                    } else {
                        showNoInternetUI()
                    }
                } else {
                    showNoInternetUI()
                }
            } catch (_: Exception) {
                progressBar.visibility = View.GONE
                showNoInternetUI()
            }
        }
    }

    private fun parseMaterialData(doc: Document): MaterialNode {
        val rootNode = MaterialNode("Raiz", MaterialNodeType.ROOT, mutableListOf())

        // Cada card representa um assunto
        val cards = doc.select("div.card")
        for (card in cards) {
            val header = card.selectFirst("div.card-header")?.text() ?: continue
            val assuntoNode = MaterialNode(header, MaterialNodeType.ASSUNTO, mutableListOf())

            // Dentro do card, subcategorias com tabelas
            val subcategorias = card.select("div.table-responsive")
            for (tableWrapper in subcategorias) {
                val caption = tableWrapper.selectFirst("tr.table-caption")?.text() ?: "Outros"
                val subcategoriaNode = MaterialNode(caption, MaterialNodeType.SUBCATEGORIA, mutableListOf())

                val rows = tableWrapper.select("tbody > tr")
                for (row in rows) {
                    val cells = row.select("td")
                    if (cells.size < 5) continue

                    val nome = cells[0].text()
                    val dataEnvio = cells[1].text()
                    val linkElement = cells[3].selectFirst("a")
                    val rawHref = linkElement?.attr("href") ?: ""
                    if (rawHref.isBlank()) continue

                    // Se href for relativo, transforma em URL absoluta
                    val absoluteLink = if (rawHref.startsWith("http", ignoreCase = true)) {
                        rawHref
                    } else {
                        "https://areaexclusiva.colegioetapa.com.br$rawHref"
                    }

                    val tipo = cells[4].text()

                    if (nome.isNotEmpty()) {
                        subcategoriaNode.children.add(
                            MaterialNode(
                                nome,
                                MaterialNodeType.ARQUIVO,
                                mutableListOf(),
                                absoluteLink,
                                dataEnvio,
                                tipo
                            )
                        )
                    }
                }
                assuntoNode.children.add(subcategoriaNode)
            }

            rootNode.children.add(assuntoNode)
        }

        return rootNode
    }

    private fun updateAdapterFromCurrentNode() {
        val currentNode = navigationStack.last()
        adapter.updateData(currentNode.children.map {
            MaterialItem(it.name, it.type, it.link, it.dataEnvio, it.tipo)
        })
    }

    private fun onItemClick(item: MaterialItem) {
        val currentNode = navigationStack.last()
        val clickedNode = currentNode.children.find { it.name == item.name } ?: return

        when (clickedNode.type) {
            MaterialNodeType.ASSUNTO, MaterialNodeType.SUBCATEGORIA -> {
                navigationStack.add(clickedNode)
                updateAdapterFromCurrentNode()
            }
            MaterialNodeType.ARQUIVO -> {
                // Quando clicar num arquivo, já temos link absoluto
                val url = clickedNode.link
                val fileName = extractFileNameFromUrl(url)
                val title = "Material: ${clickedNode.name}"
                downloadArquivo(url, fileName, title)
            }
            MaterialNodeType.ROOT -> {
                // Não faz nada aqui
            }
        }
    }

    private fun extractFileNameFromUrl(url: String): String {
        return try {
            url.toUri().lastPathSegment ?: "downloaded_file"
        } catch (_: Exception) {
            "downloaded_file"
        }
    }

    /**
     * Faz pré-resolução de todos os redirecionamentos HTTP, usando os mesmos cookies de sessão,
     * e retorna o URL final absoluto. Se não houver redirecionamento, retorna `urlOriginal`.
     */
    private suspend fun resolveRedirects(
        urlOriginal: String,
        sessionCookies: String?
    ): String = withContext(Dispatchers.IO) {
        try {
            var currentUrl = urlOriginal
            var redirectCount = 0
            while (redirectCount < 10) {
                val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "HEAD"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
                    if (!sessionCookies.isNullOrEmpty()) {
                        setRequestProperty("Cookie", sessionCookies)
                    }
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    // Pega o header "Location" para seguir o redirect
                    val next = connection.getHeaderField("Location") ?: break
                    // Se for URL relativa, converte para absoluta
                    currentUrl = if (next.startsWith("http", ignoreCase = true)) {
                        next
                    } else {
                        // base no currentUrl
                        val base = URL(currentUrl)
                        URL(base, next).toString()
                    }
                    redirectCount++
                    connection.disconnect()
                    continue
                }
                // Não é mais redirecionamento
                connection.disconnect()
                break
            }
            currentUrl
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao resolver redirecionamentos para $urlOriginal", e)
            urlOriginal
        }
    }

    private fun downloadArquivo(url: String, nomeArquivo: String, titulo: String) {
        if (!hasInternetConnection()) {
            Toast.makeText(requireContext(), "Sem conexão para download", Toast.LENGTH_SHORT).show()
            return
        }

        // Para exibir uma mensagem de "preparando download"

        // Captura cookies de sessão do domínio base
        val cookieManager = CookieManager.getInstance()
        val sessionCookies = cookieManager.getCookie(URL_BASE)

        // Lança uma corrotina para pré-resolver redirects antes de enfileirar
        CoroutineScope(Dispatchers.Main).launch {
            // 1) Resolve redirecionamentos e obtém URL final
            val finalUrl = resolveRedirects(url, sessionCookies)

            // 2) Enfileira o DownloadManager com o link final
            try {
                val downloadManager =
                    requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

                // Sincroniza cookie para o host final (caso mude de domínio)
                val hostFinal = try {
                    finalUrl.toUri().host ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (!sessionCookies.isNullOrEmpty() && hostFinal.isNotBlank()) {
                    cookieManager.setCookie(hostFinal, sessionCookies)
                }

                val request = DownloadManager.Request(finalUrl.toUri())
                    .setTitle(titulo)
                    .setDescription("Baixando arquivo")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nomeArquivo)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .addRequestHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")

                // Adiciona cookie pelo header para garantir
                if (!sessionCookies.isNullOrEmpty()) {
                    request.addRequestHeader("Cookie", sessionCookies)
                }

                // Remove arquivo antigo com mesmo nome
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    nomeArquivo
                )
                if (file.exists()) {
                    file.delete()
                }

                downloadID = downloadManager.enqueue(request)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar DownloadManager para $url", e)
            }
        }
    }

    private fun hasInternetConnection(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            ?: return false

        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showNoInternetUI() {
        recyclerMaterial.visibility = View.GONE
        layoutSemInternet.visibility = View.VISIBLE
    }

    // --- Estruturas de dados internas ---
    private data class MaterialNode(
        val name: String,
        val type: MaterialNodeType,
        val children: MutableList<MaterialNode> = mutableListOf(),
        val link: String = "",
        val dataEnvio: String = "",
        val tipo: String = ""
    )

    private enum class MaterialNodeType {
        ROOT, ASSUNTO, SUBCATEGORIA, ARQUIVO
    }

    private data class MaterialItem(
        val name: String,
        val type: MaterialNodeType,
        val link: String = "",
        val dataEnvio: String = "",
        val tipo: String = ""
    )

    private inner class MaterialAdapter(
        private var items: List<MaterialItem>,
        private val listener: (MaterialItem) -> Unit
    ) : RecyclerView.Adapter<MaterialAdapter.ViewHolder>() {

        private var allItems = items.toList()

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.item_icon)
            val text: TextView = itemView.findViewById(R.id.item_text)
            init {
                itemView.setOnClickListener {
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        listener(items[adapterPosition])
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = when (viewType) {
                R.layout.item_material_file -> R.layout.item_material_file
                else -> R.layout.item_material_folder
            }
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item.name
            val iconRes = when (item.type) {
                MaterialNodeType.ARQUIVO -> R.drawable.ic_file
                else -> R.drawable.ic_folder
            }
            holder.icon.setImageResource(iconRes)
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position].type == MaterialNodeType.ARQUIVO)
                R.layout.item_material_file
            else
                R.layout.item_material_folder
        }

        override fun getItemCount(): Int = items.size

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<MaterialItem>) {
            items = newItems
            allItems = newItems
            notifyDataSetChanged()
        }

    }
}