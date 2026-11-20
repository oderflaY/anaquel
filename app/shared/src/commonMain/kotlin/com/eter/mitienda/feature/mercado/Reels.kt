package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/**
 * Reproductor de video de la plataforma (Media3 en Android, AVPlayer en iOS).
 * Manda el token en la cabecera Authorization: los videos solo se sirven con sesión.
 */
@Composable
expect fun ReproductorVideo(url: String, token: String?, reproducir: Boolean, silencio: Boolean, modifier: Modifier)

/** Elegir un video de la galería (bytes del MP4) o un error ya explicado. */
@Composable
expect fun rememberSelectorVideo(alElegir: (ByteArray?, String?) -> Unit): () -> Unit

/** Si esta plataforma puede subir videos desde la app. */
expect val SUBIR_VIDEO_DISPONIBLE: Boolean

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Reels(api: MercadoApi, ir: Ir) {
    val reels = remember { mutableStateListOf<Reel>() }
    var siguiente by remember { mutableStateOf<Long?>(null) }
    var cargando by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var vuelta by remember { mutableStateOf(0) }
    var silencio by remember { mutableStateOf(true) }
    LaunchedEffect(vuelta) {
        cargando = true; error = null
        runCatching { api.reels() }.onSuccess { reels.clear(); reels.addAll(it.items); siguiente = it.siguiente }.onFailure { error = it.message }
        cargando = false
    }
    val oscuro = Color(0xFF0D0C0B)
    when {
        cargando && reels.isEmpty() -> Cargando("Cargando videos…") {
            Box(Modifier.fillMaxSize().background(oscuro)) {
                Column(Modifier.align(Alignment.BottomStart).padding(E.x4), verticalArrangement = Arrangement.spacedBy(E.x2)) {
                    Box(Modifier.fillMaxWidth(.4f).padding(vertical = 8.dp_()).clip(RoundedCornerShape(8.dp_())).background(Color(0xFF29261F)))
                }
            }
        }
        error != null && reels.isEmpty() -> EstadoError(error!!) { vuelta++ }
        reels.isEmpty() -> EstadoVacio("Todavía no hay videos", "Cuando los negocios suban videos de sus productos aparecerán aquí.", "video") {
            Boton("Explorar productos", { ir.explorar() }, variante = Variante.Secundario)
        }
        else -> {
            val pager = rememberPagerState { reels.size }
            LaunchedEffect(pager.currentPage, siguiente) {
                val s = siguiente
                if (s != null && pager.currentPage >= reels.size - 2) runCatching { api.reels(s) }.onSuccess { reels.addAll(it.items); siguiente = it.siguiente }
            }
            VerticalPager(pager, Modifier.fillMaxSize().background(oscuro), key = { reels[it].id }) { i ->
                val r = reels[i]
                var pausado by remember { mutableStateOf(false) }
                val visible = pager.currentPage == i
                Box(Modifier.fillMaxSize().semantics { contentDescription = "Video ${i + 1} de ${reels.size}: ${r.vendedor.nombre}. ${r.descripcion}" }) {
                    ReproductorVideo(api.url(r.url), api.token, visible && !pausado, silencio, Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.TopEnd).padding(E.x3), verticalArrangement = Arrangement.spacedBy(E.x2)) {
                        Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .45f))) {
                            BotonIcono(if (pausado) "Reproducir" else "Pausar", if (pausado) "play" else "pausa", { pausado = !pausado }, tinte = Color.White)
                        }
                        Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .45f))) {
                            BotonIcono(if (silencio) "Activar sonido" else "Silenciar", if (silencio) "silencio" else "volumen", { silencio = !silencio }, tinte = Color.White)
                        }
                    }
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f))))
                            .padding(E.x4),
                        verticalArrangement = Arrangement.spacedBy(E.x2),
                    ) {
                        Clicable({ ir.vendedor(r.vendedor.slug) }) {
                            Text(r.vendedor.nombre, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                        Text(r.descripcion, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .9f))
                        r.publicacion?.let { p ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(E.radioControl)).background(Color.White.copy(alpha = .14f)).padding(E.x2),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x2)) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.titulo, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${pesos(p.precioC)} / ${unidad(p.unidad)}", style = MaterialTheme.typography.bodySmall, color = Color.White)
                                }
                                Boton("Ver producto", { ir.producto(p.id) }, chico = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Int.dp_() = androidx.compose.ui.unit.Dp(this.toFloat())
