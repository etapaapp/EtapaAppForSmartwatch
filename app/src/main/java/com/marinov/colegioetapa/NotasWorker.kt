package com.marinov.colegioetapa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class NotasWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "NotasWorker"
        private const val URL_NOTAS = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val PREFS = "notas_prefs"
        const val KEY_HTML = "cache_html"
        // A chave usada para passar o destino para a MainActivity
        const val EXTRA_DESTINATION = "destination"
    }

    data class Nota(
        val codigo: String,
        val conjunto1: String,
        val conjunto2: String,
        val conjunto3: String,
        val conjunto4: String
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker iniciado. Verificando notas...")

        return@withContext try {
            val cookieHeader = CookieManager.getInstance().getCookie(URL_NOTAS)
            val doc = Jsoup.connect(URL_NOTAS)
                .header("Cookie", cookieHeader)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Safari/605.1.15")
                .timeout(15000)
                .get()

            val table = doc.selectFirst(
                "#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                        "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > " +
                        "div:nth-child(2) > div.card-body > table"
            ) ?: run {
                Log.d(TAG, "Tabela de notas não encontrada.")
                return@withContext Result.success()
            }

            val novasNotas = parseTable(table)
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cacheHtml = prefs.getString(KEY_HTML, null)
            Log.d(TAG, "Cache HTML carregado: ${cacheHtml?.take(50)}...")
            val antigasNotas = cacheHtml?.let {
                Jsoup.parse(it).selectFirst("table")?.let { t -> parseTable(t) }
            } ?: emptySet()
            Log.d(TAG, "Notas no cache: ${antigasNotas.size} linhas")
            val diferencas = encontrarDiferencas(novasNotas, antigasNotas)

            if (diferencas.isNotEmpty()) {
                Log.d(TAG, "Novas notas detectadas: ${diferencas.size}")
                sendNotification(diferencas)
            } else {
                Log.d(TAG, "Nenhuma nova nota encontrada.")
            }

            prefs.edit { putString(KEY_HTML, table.outerHtml())
                Log.d(TAG, "Cache atualizado com sucesso!")}
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar notas", e)
            Result.retry()
        }
    }

    private fun parseTable(table: Element): Set<Nota> {
        val notas = mutableSetOf<Nota>()
        val rows = table.select("tbody > tr")

        for (tr in rows) {
            val cols = tr.children()
            if (cols.size < 6) {
                Log.d(TAG, "Ignorando linha com ${cols.size} colunas")
                continue
            }

            val codigo = cols[1].text().trim()
            val c1 = cols[2].text().trim()
            val c2 = cols[3].text().trim()
            val c3 = cols[4].text().trim()
            val c4 = cols[5].text().trim()

            notas.add(Nota(codigo, c1, c2, c3, c4))
        }
        return notas
    }
    private fun encontrarDiferencas(novas: Set<Nota>, antigas: Set<Nota>): Set<Nota> {
        val diferencas = mutableSetOf<Nota>()

        val mapaAntigas = antigas.associateBy { it.codigo }
        Log.d(TAG, "Códigos no cache antigo: ${mapaAntigas.keys}")
        val novasLinhas = novas.filterNot { mapaAntigas.containsKey(it.codigo) }
        Log.d(TAG, "Novos códigos detectados: ${novasLinhas.map { it.codigo }}")
        novasLinhas.forEach { nova ->
            val filtered = Nota(
                codigo = nova.codigo,
                conjunto1 = if (nova.conjunto1 != "--") nova.conjunto1 else "",
                conjunto2 = if (nova.conjunto2 != "--") nova.conjunto2 else "",
                conjunto3 = if (nova.conjunto3 != "--") nova.conjunto3 else "",
                conjunto4 = if (nova.conjunto4 != "--") nova.conjunto4 else ""
            )

            if (filtered.conjunto1.isNotEmpty() || filtered.conjunto2.isNotEmpty()
                || filtered.conjunto3.isNotEmpty() || filtered.conjunto4.isNotEmpty()) {
                diferencas.add(filtered)
            }
            Log.d(TAG, "Nova linha processada: ${nova.codigo}")
        }

        novas.forEach { nova ->
            val antiga = mapaAntigas[nova.codigo] ?: return@forEach
            Log.d(TAG, "Comparando linha: ${nova.codigo}")
            val mudancas = mutableListOf<Nota>()
            if (antiga.conjunto1 != nova.conjunto1 && nova.conjunto1 != "--") {
                mudancas.add(nova.copy(conjunto2 = "", conjunto3 = "", conjunto4 = ""))
            }
            if (antiga.conjunto2 != nova.conjunto2 && nova.conjunto2 != "--") {
                mudancas.add(nova.copy(conjunto1 = "", conjunto3 = "", conjunto4 = ""))
            }
            if (antiga.conjunto3 != nova.conjunto3 && nova.conjunto3 != "--") {
                mudancas.add(nova.copy(conjunto1 = "", conjunto2 = "", conjunto4 = ""))
            }
            if (antiga.conjunto4 != nova.conjunto4 && nova.conjunto4 != "--") {
                mudancas.add(nova.copy(conjunto1 = "", conjunto2 = "", conjunto3 = ""))
            }

            diferencas.addAll(mudancas)
        }
        Log.d(TAG, "Total de diferenças encontradas: ${diferencas.size}")
        return diferencas
    }

    private fun sendNotification(diffs: Set<Nota>) {
        val notificationText = buildString {
            diffs.forEach { nota ->
                if (nota.conjunto1.isNotEmpty()) append("${nota.codigo} (1o conjunto): ${nota.conjunto1}\n")
                if (nota.conjunto2.isNotEmpty()) append("${nota.codigo} (2o conjunto): ${nota.conjunto2}\n")
                if (nota.conjunto3.isNotEmpty()) append("${nota.codigo} (3o conjunto): ${nota.conjunto3}\n")
                if (nota.conjunto4.isNotEmpty()) append("${nota.codigo} (4o conjunto): ${nota.conjunto4}\n")
            }
        }.trim()

        // GARANTIA 1: O Intent é criado para abrir a MainActivity.
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // GARANTIA 2: Um "extra" é adicionado ao Intent para dizer à MainActivity
            // qual fragmento deve ser aberto. Neste caso, "notas".
            putExtra(EXTRA_DESTINATION, "notas")
        }

        // O PendingIntent encapsula o Intent para que o sistema possa executá-lo
        // em nome do seu app quando o usuário clicar na notificação.
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "notas_channel"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Novas notas disponíveis!")
            .setContentText("Clique para ver detalhes")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent) // O PendingIntent é associado à notificação.
            .setAutoCancel(true)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Atualizações de Notas", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
