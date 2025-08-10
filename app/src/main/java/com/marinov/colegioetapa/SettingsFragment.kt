package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class SettingsFragment : Fragment() {
    private val tag = "SettingsFragment"
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressBar: ProgressBar? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners(view)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (activity as? MainActivity)?.navigateToHome()
                }
            }
        )
    }

    private fun setupClickListeners(view: View) {
        view.findViewById<View>(R.id.option_check_update).setOnClickListener {
            checkUpdate()
        }

        view.findViewById<View>(R.id.option_clear_data).setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            clearAllCacheData()
            Toast.makeText(requireContext(), "Dados limpos!", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.option_clear_password).setOnClickListener {
            clearAutoFill()
            Toast.makeText(requireContext(), "Credenciais limpas!", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.option_github).setOnClickListener {
            openUrl("https://github.com/etapaapp/")
        }
    }

    private fun clearAllCacheData() {
        listOf(
            "horarios_prefs", "calendario_prefs", "materia_cache", "notas_prefs",
            "HomeFragmentCache", "provas_prefs", "redacao_detalhes_prefs",
            "cache_html_redacao_detalhes", "redacoes_prefs", "cache_html_redacoes",
            "material_prefs", "cache_html_material", "KEY_FILTRO", "graficos_prefs",
            "cache_html_graficos", "boletim_prefs", "cache_html_boletim",
            "redacao_semanal_prefs", "cache_html_redacao_semanal", "detalhes_prefs",
            "cache_html_horarios", "cache_alert_message", "cache_html_detalhes",
            "profile_preferences", "cache_html_provas"
        ).forEach { clearSharedPreferences(it) }
    }

    private fun clearAutoFill() {
        clearSharedPreferences("autofill_prefs")
    }

    private fun clearSharedPreferences(name: String) {
        requireContext().getSharedPreferences(name, 0).edit { clear() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Log.e(tag, "Erro ao abrir URL", e)
        }
    }

    private fun checkUpdate() = coroutineScope.launch {
        try {
            val (json, responseCode) = withContext(Dispatchers.IO) {
                val url = URL("https://api.github.com/repos/etapaapp/EtapaApp/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "EtapaApp-Android")
                connection.connectTimeout = 10000
                connection.connect()

                try {
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        connection.inputStream.use { input ->
                            JSONObject(input.readText()) to connection.responseCode
                        }
                    } else {
                        null to connection.responseCode
                    }
                } finally {
                    connection.disconnect()
                }
            }

            if (json != null) {
                processReleaseData(json)
            } else {
                showError("Erro na conexão: Código $responseCode")
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro na verificação", e)
            showError("Erro: ${e.message}")
        }
    }

    private fun InputStream.readText(): String {
        return BufferedReader(InputStreamReader(this)).use { it.readText() }
    }

    private fun processReleaseData(release: JSONObject) {
        activity?.runOnUiThread {
            val latest = release.getString("tag_name")
            val current = BuildConfig.VERSION_NAME

            if (UpdateChecker.isVersionGreater(latest, current)) {
                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                apkUrl?.let { promptForUpdate(it) } ?: showError("APK não encontrado.")
            } else {
                showMessage()
            }
        }
    }

    private fun promptForUpdate(url: String) {
        activity?.runOnUiThread {
            AlertDialog.Builder(requireContext())
                .setTitle("Atualização Disponível")
                .setMessage("Deseja baixar e instalar a versão mais recente?")
                .setPositiveButton("Sim") { _, _ -> startManualDownload(url) }
                .setNegativeButton("Não", null)
                .show()
        }
    }

    private fun startManualDownload(apkUrl: String) {
        coroutineScope.launch {
            val progressDialog = createProgressDialog().apply { show() }
            try {
                val apkFile = withContext(Dispatchers.IO) { downloadApk(apkUrl) }
                progressDialog.dismiss()
                apkFile?.let(::showInstallDialog) ?: showError("Falha no download.")
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e(tag, "Erro no download", e)
                showError("Falha: ${e.message}")
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun createProgressDialog(): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_download_progress, null)
        progressBar = view.findViewById(R.id.progress_bar)
        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()
    }

    private suspend fun downloadApk(apkUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connect()

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outputDir = File(downloadsDir, "EtapaApp").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            val outputFile = File(outputDir, "app_release.apk")
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    var total: Long = 0
                    val fileLength = connection.contentLength.toLong()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        total += bytesRead
                        if (fileLength > 0) {
                            val progress = (total * 100 / fileLength).toInt()
                            withContext(Dispatchers.Main) {
                                progressBar?.progress = progress
                            }
                        }
                    }
                }
            }
            outputFile
        } catch (e: Exception) {
            Log.e(tag, "Erro no download", e)
            null
        }
    }

    private fun showInstallDialog(apkFile: File) {
        activity?.runOnUiThread {
            try {
                if (!apkFile.exists()) {
                    showError("APK não encontrado")
                    return@runOnUiThread
                }

                val apkUri = FileProvider.getUriForFile(
                    requireContext(),
                    "${BuildConfig.APPLICATION_ID}.provider",
                    apkFile
                )

                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (installIntent.resolveActivity(requireContext().packageManager) != null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Download concluído")
                        .setMessage("Deseja instalar a atualização agora?")
                        .setPositiveButton("Instalar") { _, _ -> startActivity(installIntent) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    showError("Não foi possível instalar")
                }
            } catch (e: Exception) {
                Log.e(tag, "Erro na instalação", e)
                showError("Erro: ${e.message}")
            }
        }
    }

    private fun showMessage() {
        activity?.runOnUiThread {
            AlertDialog.Builder(requireContext())
                .setMessage("Você já está na versão mais recente")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showError(msg: String) {
        activity?.runOnUiThread {
            AlertDialog.Builder(requireContext())
                .setTitle("Erro")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        progressBar = null
    }
}