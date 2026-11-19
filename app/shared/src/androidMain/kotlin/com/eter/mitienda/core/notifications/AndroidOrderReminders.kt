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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eter.mitienda.data.local.nowMillis
import com.eter.mitienda.di.AppContainerProvider
import com.eter.mitienda.domain.model.Order
import java.util.concurrent.TimeUnit

/**
 * Un trabajo de WorkManager por aviso. WorkManager los guarda en su propia BD,
 * así que sobreviven a reinicios del teléfono y a que se cierre la app.
 *
 * El texto NO se fija al programar: el Worker relee el pedido al dispararse,
 * de modo que muestra datos actuales y se calla si ya se entregó.
 */
class AndroidOrderReminderScheduler(private val context: Context) : OrderReminderScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    override fun schedule(order: Order) {
        cancel(order.id)
        val now = nowMillis()
        OrderReminderPlan.plan(order, now).forEach { reminder ->
            val request = OneTimeWorkRequestBuilder<OrderReminderWorker>()
                .setInitialDelay(reminder.fireAtMillis - now, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ORDER_ID to order.id, KEY_KIND to reminder.kind.name))
                .addTag(TAG_ALL)
                .addTag(tagFor(order.id))
                .build()
            workManager.enqueueUniqueWork("order-${order.id}-${reminder.kind}", ExistingWorkPolicy.REPLACE, request)
        }
    }

    override fun cancel(orderId: Long) {
        workManager.cancelAllWorkByTag(tagFor(orderId))
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG_ALL)
    }

    internal companion object {
        const val KEY_ORDER_ID = "orderId"
        const val KEY_KIND = "kind"
        const val TAG_ALL = "order-reminder"
        fun tagFor(orderId: Long) = "order-reminder-$orderId"
    }
}

class OrderReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val orderId = inputData.getLong(AndroidOrderReminderScheduler.KEY_ORDER_ID, -1)
        val kind = runCatching {
            ReminderKind.valueOf(inputData.getString(AndroidOrderReminderScheduler.KEY_KIND).orEmpty())
        }.getOrNull() ?: return Result.success()

        val container = (applicationContext as? AppContainerProvider)?.container ?: return Result.success()
        val order = container.orderRepository.getOrder(orderId).getOrNull() ?: return Result.success()
        if (order.status.isOpen) OrderNotifications.show(applicationContext, order, kind)
        return Result.success()
    }
}

internal object OrderNotifications {
    private const val CHANNEL_ID = "pedidos"

    fun show(context: Context, order: Order, kind: ReminderKind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // sin permiso: el aviso se ve igual dentro de la app (sección Pedidos)
        }
        ensureChannel(context)

        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, order.id.toInt(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val body = OrderReminderPlan.body(order)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(OrderReminderPlan.title(order, kind))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // Un id por pedido y tipo: el aviso del día reemplaza al de la víspera.
        NotificationManagerCompat.from(context).notify((order.id * 10 + kind.ordinal).toInt(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Entregas de pedidos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisos el día anterior y el día de entrega de pedidos por encargo"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
