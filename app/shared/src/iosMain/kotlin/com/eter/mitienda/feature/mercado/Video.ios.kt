package com.eter.mitienda.feature.mercado

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView

// Escrito para iOS pero no compilado en esta máquina (Windows no compila objetivos de Apple); revisar en Xcode.

actual val SUBIR_VIDEO_DISPONIBLE: Boolean = false

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ReproductorVideo(url: String, token: String?, reproducir: Boolean, silencio: Boolean, modifier: Modifier) {
    val jugador = remember(url) {
        val opciones = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("Authorization" to "Bearer ${token.orEmpty()}"))
        val asset = AVURLAsset(uRL = NSURL.URLWithString(url)!!, options = opciones)
        AVPlayer(playerItem = AVPlayerItem(asset = asset))
    }
    LaunchedEffect(reproducir) { if (reproducir) jugador.play() else jugador.pause() }
    LaunchedEffect(silencio) { jugador.muted = silencio }
    DisposableEffect(jugador) { onDispose { jugador.pause() } }
    val capa = remember(jugador) { AVPlayerLayer.playerLayerWithPlayer(jugador).apply { videoGravity = AVLayerVideoGravityResizeAspect } }
    UIKitView(
        factory = { UIView().apply { layer.addSublayer(capa) } },
        update = { v ->
            CATransaction.begin(); CATransaction.setDisableActions(true)
            capa.frame = v.bounds
            CATransaction.commit()
        },
        modifier = modifier,
    )
}

@Composable
actual fun rememberSelectorVideo(alElegir: (ByteArray?, String?) -> Unit): () -> Unit =
    { alElegir(null, "Subir videos desde el iPhone llegará pronto; usa Android o la web.") }
