package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class DetalhesProvaFragment : Fragment() {

    companion object {
        const val ARG_URL = "url"
        const val PREFS = "detalhes_prova_prefs"
        const val ELEMENTO_CRITICO_SELECTOR = "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0"
        fun newInstance(url: String) = DetalhesProvaFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, url)
            }
        }
    }

    private lateinit var progressBar: View
    private lateinit var telaOffline: View
    private lateinit var txtSemDados: TextView
    private lateinit var recyclerQuestoes: RecyclerView
    private lateinit var btnTentarNovamente: MaterialButton
    private lateinit var contentLayout: View

    private lateinit var txtCodigo: TextView
    private lateinit var txtTipo: TextView
    private lateinit var txtConjunto: TextView
    private lateinit var txtNota: TextView

    private var elementoCriticoPresente = false
    private var url: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            url = it.getString(ARG_URL)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_detalhes_prova, container, false)

        progressBar = root.findViewById(R.id.progressBar)
        telaOffline = root.findViewById(R.id.telaOffline)
        txtSemDados = root.findViewById(R.id.txtSemDados)
        recyclerQuestoes = root.findViewById(R.id.recyclerQuestoes)
        btnTentarNovamente = root.findViewById(R.id.btn_tentar_novamente)
        contentLayout = root.findViewById(R.id.contentLayout)

        txtCodigo = root.findViewById(R.id.txtCodigo)
        txtTipo = root.findViewById(R.id.txtTipo)
        txtConjunto = root.findViewById(R.id.txtConjunto)
        txtNota = root.findViewById(R.id.txtNota)

        recyclerQuestoes.layoutManager = LinearLayoutManager(requireContext())
        recyclerQuestoes.adapter = QuestoesAdapter(emptyList())

        btnTentarNovamente.setOnClickListener {
            carregarDetalhes()
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requireActivity().supportFragmentManager.popBackStack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        carregarDetalhes()
    }

    private fun carregarDetalhes() {
        if (url.isNullOrEmpty()) {
            exibirSemDados()
            return
        }

        if (!isOnline()) {
            exibirTelaOffline()
            return
        }

        verificarElementoCritico()
    }

    private fun verificarElementoCritico() {
        CoroutineScope(Dispatchers.Main).launch {
            exibirCarregando()

            try {
                val doc = withContext(Dispatchers.IO) {
                    try {
                        val cookieHeader = CookieManager.getInstance().getCookie(url)
                        Jsoup.connect(url!!)
                            .header("Cookie", cookieHeader)
                            .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
                            .timeout(15000)
                            .get()
                    } catch (e: Exception) {
                        Log.e("DetalhesProva", "Erro na conexão", e)
                        null
                    }
                }

                progressBar.visibility = View.GONE

                if (doc != null) {
                    elementoCriticoPresente = doc.selectFirst(ELEMENTO_CRITICO_SELECTOR) != null

                    if (elementoCriticoPresente) {
                        parseDocument(doc)
                        exibirConteudoOnline()
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

    private fun parseDocument(doc: org.jsoup.nodes.Document) {
        val tabelaDetalhes = doc.selectFirst(
            "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div.card.mb-5.bg-transparent.border-0 > table"
        )
        if (tabelaDetalhes != null) {
            val linhas = tabelaDetalhes.select("tbody tr")
            if (linhas.size >= 2) {
                val celulas = linhas[1].select("td")
                if (celulas.size >= 4) {
                    val codigo = celulas[0].text()
                    val conjunto = celulas[1].text()
                    val tipo = celulas[2].text()
                    val nota = celulas[3].text()

                    txtCodigo.text = codigo
                    txtTipo.text = tipo
                    txtConjunto.text = getString(R.string.conjunto_format, conjunto)
                    txtNota.text = nota
                }
            }
        }

        // Tabela 2: Questões
        val tabelaQuestoes = doc.selectFirst(
            "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(2) > table"
        )
        val questoes = mutableListOf<Questao>()
        if (tabelaQuestoes != null) {
            val linhas = tabelaQuestoes.select("tbody tr")
            for (linha in linhas) {
                val celulas = linha.select("td")
                if (celulas.size >= 5) {
                    val numero = celulas[0].text()

                    // Processa múltiplas linhas usando <br>
                    val assunto = celulas[1].html().replace("<br>", "\n").trim()
                    val topico = celulas[2].html().replace("<br>", "\n").trim()
                    val subtopico = celulas[3].html().replace("<br>", "\n").trim()

                    val dificuldade = celulas[4].text().trim()

                    questoes.add(Questao(numero, assunto, topico, subtopico, dificuldade))
                }
            }
        }

        if (questoes.isNotEmpty()) {
            recyclerQuestoes.visibility = View.VISIBLE
            (recyclerQuestoes.adapter as QuestoesAdapter).updateData(questoes)
        } else {
            recyclerQuestoes.visibility = View.GONE
        }
    }

    private fun exibirCarregando() {
        progressBar.visibility = View.VISIBLE
        recyclerQuestoes.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirConteudoOnline() {
        progressBar.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        telaOffline.visibility = View.GONE
    }

    private fun exibirTelaOffline() {
        telaOffline.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        txtSemDados.visibility = View.GONE
        recyclerQuestoes.visibility = View.GONE
    }

    private fun exibirSemDados() {
        txtSemDados.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        telaOffline.visibility = View.GONE
        recyclerQuestoes.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val network: Network? = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
            return networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    private data class Questao(
        val numero: String,
        val assunto: String,
        val topico: String,
        val subtopico: String,
        val dificuldade: String
    )

    private inner class QuestoesAdapter(
        private var items: List<Questao>
    ) : RecyclerView.Adapter<QuestoesAdapter.ViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<Questao>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_questao, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtTopico.text = item.topico.replace(", ", "\n")
            holder.txtSubTopico.text = item.subtopico.replace(", ", "\n")
            holder.txtNumero.text = getString(R.string.questao_format, item.numero)
            holder.txtAssunto.text = item.assunto
            holder.txtTopico.text = item.topico
            holder.txtSubTopico.text = item.subtopico
            holder.txtDificuldade.text = item.dificuldade

            // Configurar as estrelas
            holder.containerEstrelas.removeAllViews()
            val dificuldade = item.dificuldade.lowercase()
            val estrelas = when {
                dificuldade.contains("muito difícil") -> listOf(R.drawable.ic_star_half, R.drawable.ic_star_empty, R.drawable.ic_star_empty, R.drawable.ic_star_empty)
                dificuldade.contains("difícil") -> listOf(R.drawable.ic_star_full, R.drawable.ic_star_half, R.drawable.ic_star_empty, R.drawable.ic_star_empty)
                dificuldade.contains("médio") -> listOf(R.drawable.ic_star_full, R.drawable.ic_star_full, R.drawable.ic_star_empty, R.drawable.ic_star_empty)
                dificuldade.contains("fácil") -> listOf(R.drawable.ic_star_full, R.drawable.ic_star_full, R.drawable.ic_star_full, R.drawable.ic_star_empty)
                dificuldade.contains("muito fácil") -> listOf(R.drawable.ic_star_full, R.drawable.ic_star_full, R.drawable.ic_star_full, R.drawable.ic_star_full)
                else -> listOf(R.drawable.ic_star_empty, R.drawable.ic_star_empty, R.drawable.ic_star_empty, R.drawable.ic_star_empty)
            }

            for (estrela in estrelas) {
                val imageView = ImageView(requireContext())
                imageView.setImageResource(estrela)
                imageView.layoutParams = LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.star_size),
                    resources.getDimensionPixelSize(R.dimen.star_size)
                ).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.star_spacing)
                }
                holder.containerEstrelas.addView(imageView)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val txtNumero: TextView = itemView.findViewById(R.id.txtNumero)
            val txtAssunto: TextView = itemView.findViewById(R.id.txtAssunto)
            val txtTopico: TextView = itemView.findViewById(R.id.txtTopico)
            val txtSubTopico: TextView = itemView.findViewById(R.id.txtSubTopico)
            val txtDificuldade: TextView = itemView.findViewById(R.id.txtDificuldade)
            val containerEstrelas: LinearLayout = itemView.findViewById(R.id.containerEstrelas)
        }
    }
}