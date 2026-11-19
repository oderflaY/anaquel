package com.eter.mitienda.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Decodifica una imagen guardada en el almacenamiento local, reducida a un
 * lado máximo de aproximadamente [maxSizePx].
 *
 * Contrato: NUNCA lanza. Devuelve `null` si el archivo no existe (p. ej. se
 * borró), está corrupto o no hay memoria.
 */
internal expect fun decodeLocalImage(path: String, maxSizePx: Int): ImageBitmap?

/** Estado de carga de una imagen local, para distinguir "sin foto" de "foto perdida". */
sealed interface LocalImageState {
    /** El producto no tiene foto. */
    data object Empty : LocalImageState
    data object Loading : LocalImageState
    data class Loaded(val bitmap: ImageBitmap) : LocalImageState
    /** Tenía ruta pero el archivo ya no existe o está dañado. */
    data object Missing : LocalImageState
}

/**
 * Traduce la ruta guardada en SQLite (relativa) a ruta absoluta. Lo provee
 * App con el ImageStorage de la plataforma; por defecto deja la ruta igual.
 */
val LocalImagePathResolver = staticCompositionLocalOf<(String) -> String> { { it } }

/**
 * Caché LRU de miniaturas en memoria. Al hacer scroll en la lista, una tarjeta
 * que vuelve a aparecer no decodifica su foto otra vez. Solo se usa desde el
 * hilo principal. Las fotos guardadas tienen nombre único (UUID), así que una
 * entrada nunca queda desactualizada: si la foto cambia, cambia la clave.
 */
private object ImageMemoryCache {
    private const val MAX_ENTRIES = 60
    private val entries = LinkedHashMap<String, ImageBitmap>()

    fun get(key: String): ImageBitmap? =
        entries.remove(key)?.also { entries[key] = it } // re-inserta: queda como el más reciente

    fun put(key: String, bitmap: ImageBitmap) {
        entries.remove(key)
        entries[key] = bitmap
        if (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }
}

/** Carga [path] fuera del hilo principal, con caché en memoria. */
@Composable
fun rememberLocalImage(path: String?, maxSizePx: Int = 512): LocalImageState {
    val resolve = LocalImagePathResolver.current
    val cacheKey = path?.let { "$it@$maxSizePx" }
    val cached = cacheKey?.let(ImageMemoryCache::get)

    val state by produceState(
        initialValue = when {
            path.isNullOrBlank() -> LocalImageState.Empty
            cached != null -> LocalImageState.Loaded(cached)
            else -> LocalImageState.Loading
        },
        path,
        maxSizePx,
    ) {
        if (path.isNullOrBlank() || cacheKey == null) {
            value = LocalImageState.Empty
            return@produceState
        }
        if (value is LocalImageState.Loaded) return@produceState
        val bitmap = withContext(Dispatchers.IO) { decodeLocalImage(resolve(path), maxSizePx) }
        value = if (bitmap != null) {
            ImageMemoryCache.put(cacheKey, bitmap)
            LocalImageState.Loaded(bitmap)
        } else {
            LocalImageState.Missing
        }
    }
    return state
}
