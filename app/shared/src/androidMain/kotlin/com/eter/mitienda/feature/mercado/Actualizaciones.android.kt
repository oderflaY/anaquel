package com.eter.mitienda.feature.mercado

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun versionInstalada(): String = runCatching {
    val ctx = ContextoMercado.app
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
}.getOrNull() ?: "0"

actual suspend fun instalarApk(apk: ByteArray): String? {
    val ctx = ContextoMercado.app
    val archivo = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "actualizacion").apply { mkdirs() }
        File(dir, "anaquel-mercado.apk").apply { writeBytes(apk) }
    }
    // Revisión previa: que sea esta misma app (el sistema además exige la misma firma).
    val info = ctx.packageManager.getPackageArchiveInfo(archivo.path, 0)
    if (info?.packageName != ctx.packageName) {
        archivo.delete()
        return "El archivo descargado no es Anaquel Mercado. No se instaló."
    }
    return withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return@withContext "Activa «Permitir de esta fuente» para Anaquel Mercado y vuelve a tocar «Actualizar»."
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", archivo)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    }
}
