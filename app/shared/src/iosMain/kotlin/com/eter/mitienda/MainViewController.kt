package com.eter.mitienda

import androidx.compose.ui.window.ComposeUIViewController
import androidx.sqlite.driver.NativeSQLiteDriver
import com.eter.mitienda.core.image.IosImageStorage
import com.eter.mitienda.core.notifications.IosOrderReminderScheduler
import com.eter.mitienda.data.sync.IosDatabaseFileStore
import com.eter.mitienda.data.sync.UnsupportedPeerSyncService
import com.eter.mitienda.di.AppContainer
import platform.UIKit.UIDevice

/** Una sola instancia por proceso (ver AppContainer). */
private val appContainer: AppContainer by lazy {
    AppContainer(
        driver = NativeSQLiteDriver(),
        databaseFiles = IosDatabaseFileStore(),
        imageStorage = IosImageStorage(),
        reminderScheduler = IosOrderReminderScheduler(),
        peerSyncFactory = { UnsupportedPeerSyncService(UIDevice.currentDevice.name) },
    )
}

fun MainViewController() = ComposeUIViewController { App(appContainer) }
