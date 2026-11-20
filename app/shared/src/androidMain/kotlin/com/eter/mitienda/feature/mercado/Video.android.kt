package com.eter.mitienda.feature.mercado

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

actual val SUBIR_VIDEO_DISPONIBLE: Boolean = true

@OptIn(UnstableApi::class)
@Composable
actual fun ReproductorVideo(url: String, token: String?, reproducir: Boolean, silencio: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val jugador = remember(url) {
        // Por red con la sesión; en el modo demo el video viene empaquetado en la app.
        val fuente = if (url.startsWith("http")) DefaultHttpDataSource.Factory().apply {
            token?.let { setDefaultRequestProperties(mapOf("Authorization" to "Bearer $it")) }
        } else androidx.media3.datasource.DefaultDataSource.Factory(ctx)
        ExoPlayer.Builder(ctx).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(fuente).createMediaSource(MediaItem.fromUri(Uri.parse(url))))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }
    LaunchedEffect(reproducir) { jugador.playWhenReady = reproducir }
    LaunchedEffect(silencio) { jugador.volume = if (silencio) 0f else 1f }
    DisposableEffect(jugador) { onDispose { jugador.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = jugador
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        modifier = modifier,
    )
}

@Composable
actual fun rememberSelectorVideo(alElegir: (ByteArray?, String?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val tam = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        if (tam > 50L * 1024 * 1024) { alElegir(null, "El video pesa más de 50 MB"); return@rememberLauncherForActivityResult }
        val datos = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (datos == null) alElegir(null, "No se pudo leer el video") else alElegir(datos, null)
    }
    return { lanzador.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
}
