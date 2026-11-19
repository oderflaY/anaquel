package com.eter.mitienda.core.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private class IosNotificationPermissionState : NotificationPermissionState {
    override var granted by mutableStateOf(false)

    init {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val ok = settings?.authorizationStatus == UNAuthorizationStatusAuthorized
            dispatch_async(dispatch_get_main_queue()) { granted = ok }
        }
    }

    override fun request() {
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { ok, _ ->
            dispatch_async(dispatch_get_main_queue()) { granted = ok }
        }
    }
}

@Composable
actual fun rememberNotificationPermissionState(): NotificationPermissionState =
    remember { IosNotificationPermissionState() }
