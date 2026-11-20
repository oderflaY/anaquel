package com.eter.mitienda.feature.mercado

import platform.Foundation.NSBundle

actual fun versionInstalada(): String =
    NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "0"

/** En iPhone no se instalan APK: las versiones nuevas llegan por la App Store. */
actual suspend fun instalarApk(apk: ByteArray): String? = "En iPhone las actualizaciones llegan por la App Store."
