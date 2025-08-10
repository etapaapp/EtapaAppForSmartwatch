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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
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
        setupUI(view)
    }

    private fun setupUI(view: View) {
        val btnCheck = view.findViewById<Button>(R.id.btn_check_update)
        val btnClear = view.findViewById<Button>(R.id.btn_clear_data)
        val btnClearPassword = view.findViewById<Button>(R.id.btn_clear_password)
        val btnGithub = view.findViewById<Button>(R.id.btn_github)

        btnGithub.setOnClickListener { openUrl("https://github.com/etapaapp/") }
        btnCheck.setOnClickListener { checkUpdate() }
        btnClear.setOnClickListener {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            clearAllCacheData()
            Toast.makeText(requireContext(), "Dados apagados!", Toast.LENGTH_SHORT).show()
        }
        btnClearPassword.setOnClickListener {
            clearAutoFill()
            Toast.makeText(
                requireContext(),
                "Credenciais apagadas!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun clearAllCacheData() {
        listOf(
            "horarios_prefs",
            "calendario_prefs",
            "materia_cache",
            "notas_prefs",
            "HomeFragmentCache",
            "provas_prefs",
            "redacao_detalhes_prefs",
            "cache_html_redacao_detalhes",
            "redacoes_prefs",
            "cache_html_redacoes",
            "material_prefs",
            "cache_html_material",
            "KEY_FILTRO",
            "graficos_prefs",
            "cache_html_graficos",
            "boletim_prefs",
            "cache_html_boletim",
            "redacao_semanal_prefs",
            "cache_html_redacao_semanal",
            "detalhes_prefs",
            "cache_html_horarios",
            "cache_alert_message",
            "cache_html_detalhes",
            "profile_preferences",
            "cache_html_provas"
        ).forEach { clearSharedPreferences(it) }
    }

    private fun clearAutoFill() {
        clearSharedPreferences("autofill_prefs")
    }

    private fun clearSharedPreferences(name: String) {
        requireContext().getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit { clear() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(tag, "Erro ao abrir URL", e)
        }
    }

    private fun checkUpdate() = coroutineScope.launch {
        try {
            val (json, responseCode) = withContext(Dispatchers.IO) {
                val url = URL("https://api.github.com/repos/etapaapp/EtapaAppForSmartwatch/releases/latest")
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
        requireActivity().runOnUiThread {
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
        requireActivity().runOnUiThread {
            AlertDialog.Builder(requireContext())
                .setTitle("Atualização")
                .setMessage("Baixar versão mais recente?")
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
                apkFile?.let(::showInstallDialog) ?: showError("Falha ao baixar.")
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
            val outputDir = File(downloadsDir, "EtapaAppForSmartwatch").apply {
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
        requireActivity().runOnUiThread {
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
                        .setTitle("Download OK")
                        .setMessage("Instalar agora?")
                        .setPositiveButton("Instalar") { _, _ -> startActivity(installIntent) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    showError("Instalador não encontrado")
                }
            } catch (e: Exception) {
                Log.e(tag, "Erro na instalação", e)
                showError("Erro: ${e.message}")
            }
        }
    }

    private fun showMessage() {
        requireActivity().runOnUiThread {
            AlertDialog.Builder(requireContext())
                .setMessage("Já está atualizado")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showError(msg: String) {
        requireActivity().runOnUiThread {
            AlertDialog.Builder(requireContext())
                .setTitle("Erro")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        coroutineScope.cancel()
        progressBar = null
    }
}