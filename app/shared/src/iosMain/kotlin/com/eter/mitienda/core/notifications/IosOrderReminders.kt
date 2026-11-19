package com.eter.mitienda.core.notifications

import com.eter.mitienda.data.local.nowMillis
import com.eter.mitienda.domain.model.Order
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS no deja ejecutar código al dispararse un aviso local, así que el texto se
 * fija al programar; por eso cada cambio del pedido vuelve a programarlo.
 */
class IosOrderReminderScheduler : OrderReminderScheduler {

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun schedule(order: Order) {
        cancel(order.id)
        val now = nowMillis()
        OrderReminderPlan.plan(order, now).forEach { reminder ->
            val content = UNMutableNotificationContent().apply {
                setTitle(OrderReminderPlan.title(order, reminder.kind))
                setBody(OrderReminderPlan.body(order))
                setSound(UNNotificationSound.defaultSound)
            }
            val seconds = (reminder.fireAtMillis - now) / 1000.0
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
            center.addNotificationRequest(
                UNNotificationRequest.requestWithIdentifier(id(order.id, reminder.kind), content, trigger),
                withCompletionHandler = null,
            )
        }
    }

    override fun cancel(orderId: Long) {
        center.removePendingNotificationRequestsWithIdentifiers(ReminderKind.entries.map { id(orderId, it) })
    }

    override fun cancelAll() {
        center.removeAllPendingNotificationRequests()
    }

    private fun id(orderId: Long, kind: ReminderKind) = "order-$orderId-$kind"
}
