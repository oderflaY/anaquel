package com.eter.mitienda.core.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private class AndroidNotificationPermissionState(private val context: Context) : NotificationPermissionState {
    override var granted by mutableStateOf(check())
    var launch: () -> Unit = {}
    /** Tras un rechazo definitivo el diálogo ya no aparece: hay que ir a Ajustes. */
    var blocked = false

    override fun request() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !blocked) {
            launch()
        } else {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun refresh() {
        granted = check()
    }

    private fun check() = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

@Composable
actual fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember(context) { AndroidNotificationPermissionState(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        state.refresh()
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state.blocked = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        }
    }
    state.launch = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}
