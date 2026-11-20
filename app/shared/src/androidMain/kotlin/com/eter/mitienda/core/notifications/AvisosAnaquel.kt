package com.eter.mitienda.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eter.mitienda.di.AppContainerProvider
import java.util.concurrent.TimeUnit

/**
 * Notificaciones de Anaquel (mensajes de chat, tratos y avisos de la red).
 * Sin Firebase: WorkManager le pregunta al servidor cada 15 minutos qué hay
 * nuevo desde la última vez. Si no hay sesión no hace nada.
 */
object AvisosAnaquel {
    private const val TRABAJO = "anaquel-avisos"
    const val CANAL_CHATS = "anaquel-chats"
    const val CANAL_TRATOS = "anaquel-tratos"
    const val CANAL_RED = "anaquel-red"

    fun programar(context: Context) {
        crearCanales(context)
        val red = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            TRABAJO, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RevisarAvisosWorker>(15, TimeUnit.MINUTES).setConstraints(red).build(),
        )
    }

    /** Revisión inmediata (al salir de la app, para no esperar 15 min). */
    fun revisarYa(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$TRABAJO-ya", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RevisarAvisosWorker>().setInitialDelay(20, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    private fun crearCanales(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CANAL_CHATS, "Mensajes", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Mensajes y pedidos que te mandan otras tiendas"
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_TRATOS, "Tratos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Cuando aceptan, contraofertan o entregan un trato"
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_RED, "Red de comercio", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Encontramos lo que buscas o alguien quiere lo tuyo"
        })
    }

    fun mostrar(context: Context, id: Int, canal: String, titulo: String, texto: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val abrir = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = abrir?.let { PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(context, canal)
            .setSmallIcon(context.applicationInfo.icon)
            .setColor(0xFF0E7C5A.toInt())
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup("anaquel")
            .apply { pi?.let(::setContentIntent) }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}

class RevisarAvisosWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as? AppContainerProvider)?.container ?: return Result.success()
        val ajustes = c.ajustesDao
        val token = runCatching { ajustes.leer("anaquel.token") }.getOrNull() ?: return Result.success()
        val api = com.eter.mitienda.feature.anaquel.AnaquelApi().also { it.token = token }
        val aviso = runCatching { ajustes.leer(ULT_AVISO)?.toLong() }.getOrNull()
        val chat = runCatching { ajustes.leer(ULT_CHAT)?.toLong() }.getOrNull()
        val r = runCatching { api.notificaciones(aviso, chat) }.getOrElse { return Result.retry() }
        // La primera vez solo se guardan los ids: no avisamos de lo viejo.
        if (aviso != null && chat != null) {
            r.items.take(6).forEach { n ->
                val canal = when (n.tipo) {
                    "chat" -> AvisosAnaquel.CANAL_CHATS
                    "trato" -> AvisosAnaquel.CANAL_TRATOS
                    else -> AvisosAnaquel.CANAL_RED
                }
                AvisosAnaquel.mostrar(applicationContext, n.clave.hashCode(), canal, n.titulo, n.texto)
            }
        }
        ajustes.guardar(ULT_AVISO, r.ultAviso.toString())
        ajustes.guardar(ULT_CHAT, r.ultChat.toString())
        return Result.success()
    }

    companion object {
        const val ULT_AVISO = "anaquel.notif.aviso"
        const val ULT_CHAT = "anaquel.notif.chat"
    }
}
