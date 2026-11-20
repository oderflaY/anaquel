package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

// Fotos de productos de la red: se bajan una vez, se decodifican fuera del hilo
// de la pantalla y se guardan ya decodificadas (para que el feed no se trabe).

/** Cómo bajar los bytes de una foto ("/media/…"). Lo pone App.kt. */
val LocalBajarFoto = staticCompositionLocalOf<(suspend (String) -> ByteArray)?> { null }

private object CacheFotos {
    private const val MAX = 48
    private val mapa = LinkedHashMap<String, ImageBitmap>()
    fun leer(k: String): ImageBitmap? = mapa[k]?.also { mapa.remove(k); mapa[k] = it }
    fun guardar(k: String, v: ImageBitmap) {
        mapa[k] = v
        while (mapa.size > MAX) mapa.remove(mapa.keys.first())
    }
}

/**
 * Foto de la red con aparición suave. Mientras carga (o si no hay foto) se
 * muestra [respaldo], normalmente un ícono.
 */
@Composable
fun FotoRed(
    ruta: String?, descripcion: String?, modifier: Modifier = Modifier,
    escala: ContentScale = ContentScale.Fit, respaldo: @Composable () -> Unit = {},
) {
    val bajar = LocalBajarFoto.current
    var img by remember(ruta) { mutableStateOf(ruta?.let { CacheFotos.leer(it) }) }
    LaunchedEffect(ruta) {
        if (ruta.isNullOrBlank() || img != null || bajar == null) return@LaunchedEffect
        img = runCatching {
            val bytes = bajar(ruta)
            withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
        }.getOrNull()?.also { CacheFotos.guardar(ruta, it) }
    }
    val a by animateFloatAsState(if (img != null) 1f else 0f, tween(280))
    Box(modifier, contentAlignment = Alignment.Center) {
        if (img == null || a < 1f) Box(Modifier.graphicsLayer { alpha = 1f - a }) { respaldo() }
        img?.let { Image(it, descripcion, Modifier.matchParentSize().graphicsLayer { alpha = a }, contentScale = escala) }
    }
}
