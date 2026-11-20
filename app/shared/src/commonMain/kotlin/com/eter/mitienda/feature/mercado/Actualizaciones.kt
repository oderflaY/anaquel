package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Actualizaciones de «Anaquel Mercado» desde los Releases de GitHub del repo.
 *
 * Cada versión se publica como un release con etiqueta «mercado-vX.Y.Z», sus notas
 * y el APK adjunto (ver scripts/publicar-mercado.sh). Al abrir la app se revisa si
 * hay una versión mayor que la instalada: si la hay, sale una ventana con las notas
 * que se puede cerrar; el botón «Actualizar» queda siempre a la mano en «Cuenta» y
 * en el botón «＋». El APK solo se baja de github.com/<repo>/releases/download/ y
 * Android solo lo instala si está firmado con la misma llave que la app instalada.
 */

const val REPO_RELEASES = "oderflaY/anaquel"
const val PREFIJO_RELEASE = "mercado-v"

/** Versión instalada (versionName del paquete). */
expect fun versionInstalada(): String

/** Guarda el APK y abre el instalador del sistema. Devuelve un mensaje si algo impide instalar. */
expect suspend fun instalarApk(apk: ByteArray): String?

@Serializable private data class AssetGh(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
private data class ReleaseGh(
    @SerialName("tag_name") val tag: String, val name: String? = null, val body: String? = null,
    val draft: Boolean = false, val prerelease: Boolean = false, val assets: List<AssetGh> = emptyList(),
    @SerialName("published_at") val publicada: String? = null,
)

data class VersionNueva(val version: String, val titulo: String, val notas: String, val apkUrl: String, val tamano: Long, val fecha: String?)

/** Compara «1.10.0» contra «1.9.2» por números. */
fun compararVersion(a: String, b: String): Int {
    val x = a.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

object Actualizaciones {
    var disponible by mutableStateOf<VersionNueva?>(null)
    var revisando by mutableStateOf(false)
    var progreso by mutableStateOf<Float?>(null)
    var error by mutableStateOf<String?>(null)
    var aviso by mutableStateOf<String?>(null)
    var mostrarVentana by mutableStateOf(false)

    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy {
        HttpClient {
            install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 }
            expectSuccess = false
        }
    }

    /** Busca una versión más nueva. [alAbrir]: si la hay, se muestra la ventana; si falla, no molesta. */
    suspend fun revisar(alAbrir: Boolean = false) {
        if (revisando) return
        revisando = true
        if (!alAbrir) { error = null; aviso = null }
        try {
            val r = http.get("https://api.github.com/repos/$REPO_RELEASES/releases?per_page=30") {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "AnaquelMercado")
            }
            if (!r.status.isSuccess()) throw IllegalStateException("GitHub respondió ${r.status.value}")
            val actual = versionInstalada()
            val descarga = "https://github.com/$REPO_RELEASES/releases/download/"
            val mejor = json.decodeFromString<List<ReleaseGh>>(r.bodyAsText()).asSequence()
                .filter { !it.draft && !it.prerelease && it.tag.startsWith(PREFIJO_RELEASE) }
                .mapNotNull { rel ->
                    rel.assets.firstOrNull { it.name.endsWith(".apk") && it.url.startsWith(descarga) }?.let { a ->
                        VersionNueva(rel.tag.removePrefix(PREFIJO_RELEASE), rel.name ?: rel.tag, rel.body.orEmpty().trim(), a.url, a.size, rel.publicada)
                    }
                }
                .maxWithOrNull { a, b -> compararVersion(a.version, b.version) }
            disponible = mejor?.takeIf { compararVersion(it.version, actual) > 0 }
            if (disponible == null && !alAbrir) aviso = "Tienes la versión más reciente ($actual)."
            if (alAbrir && disponible != null) mostrarVentana = true
        } catch (e: Exception) {
            if (!alAbrir) error = "No se pudo revisar. Revisa tu conexión a internet."
        }
        revisando = false
    }

    /** Baja el APK de la versión nueva y abre el instalador. */
    suspend fun actualizar() {
        val v = disponible ?: return
        if (progreso != null) return
        progreso = 0f; error = null
        try {
            val r = http.get(v.apkUrl) {
                header("User-Agent", "AnaquelMercado")
                timeout { requestTimeoutMillis = 20 * 60_000 }
                onDownload { bajado, total -> if (total != null && total > 0) progreso = bajado.toFloat() / total }
            }
            if (!r.status.isSuccess()) throw IllegalStateException("Descarga ${r.status.value}")
            progreso = 1f
            instalarApk(r.readRawBytes())?.let { error = it }
        } catch (e: Exception) {
            error = "No se pudo bajar la actualización. Revisa tu internet e intenta de nuevo."
        }
        progreso = null
    }
}

/** Notas del release (Markdown sencillo) como texto legible. */
fun notasLegibles(md: String): String = md.lines().joinToString("\n") { l ->
    val t = l.trim()
    when {
        t.startsWith("#") -> t.trimStart('#').trim().uppercase()
        t.startsWith("- ") || t.startsWith("* ") -> "•  " + t.drop(2)
        else -> t
    }.replace("**", "").replace("`", "")
}.trim()

private fun megas(b: Long) = if (b <= 0) "" else " · ${(b / 1_048_576.0 * 10).toInt() / 10.0} MB"

/** Ventana que sale al abrir la app cuando hay una versión nueva. */
@Composable
fun VentanaActualizacion() {
    val a = Actualizaciones
    val v = a.disponible ?: return
    if (!a.mostrarVentana) return
    val alcance = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (a.progreso == null) a.mostrarVentana = false },
        shape = RoundedCornerShape(28.dp), containerColor = M.c.superficie,
        icon = { LogoAnaquel(52.dp) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nueva versión ${v.version}", fontWeight = FontWeight.Black, fontSize = 22.sp, color = M.c.texto)
                Text("Tienes la ${versionInstalada()}${megas(v.tamano)}", fontSize = 13.sp, color = M.c.texto2)
            }
        },
        text = { CuerpoActualizacion(v) },
        confirmButton = {
            Boton(if (a.progreso != null) "Bajando…" else "Actualizar", { alcance.launch { a.actualizar() } },
                icono = "descargar", cargando = a.progreso != null)
        },
        dismissButton = { if (a.progreso == null) Boton("Ahora no", { a.mostrarVentana = false }, variante = Variante.Fantasma) },
    )
}

@Composable
private fun CuerpoActualizacion(v: VersionNueva) {
    val a = Actualizaciones
    Column(verticalArrangement = Arrangement.spacedBy(E.x3)) {
        Text("Qué hay de nuevo", style = MaterialTheme.typography.labelMedium, color = M.c.marca)
        Text(
            notasLegibles(v.notas).ifBlank { "Mejoras y correcciones." },
            style = MaterialTheme.typography.bodyMedium, color = M.c.texto,
            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
        )
        a.progreso?.let { p ->
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(E.x1)) {
                LinearProgressIndicator({ p }, Modifier.fillMaxWidth(), color = M.c.marca, trackColor = M.c.marcaSuave)
                Text("Bajando ${(p * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, color = M.c.texto2)
            }
        }
        a.error?.let { Alerta(it, "error") }
    }
}

/** Tarjeta fija en «Cuenta»: versión instalada, buscar y actualizar. */
@Composable
fun TarjetaActualizaciones() {
    val a = Actualizaciones
    val alcance = rememberCoroutineScope()
    Tarjeta {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoAnaquel(40.dp)
            Spacer(Modifier.width(E.x3))
            Column(Modifier.weight(1f)) {
                Titulo("Actualizaciones", grande = false)
                Text("Versión instalada ${versionInstalada()}", style = MaterialTheme.typography.bodySmall, color = M.c.texto2)
            }
            if (a.disponible != null) Insignia("Nueva", "exito")
        }
        val v = a.disponible
        if (v != null) {
            Text("Ya salió la versión ${v.version}.", style = MaterialTheme.typography.titleMedium, color = M.c.texto)
            CuerpoActualizacion(v)
            Boton(if (a.progreso != null) "Bajando…" else "Actualizar a ${v.version}", { alcance.launch { a.actualizar() } },
                Modifier.fillMaxWidth(), icono = "descargar", cargando = a.progreso != null)
        } else {
            a.aviso?.let { Alerta(it, "exito") }
            a.error?.let { Alerta(it, "error") }
            Boton("Buscar actualizaciones", { alcance.launch { a.revisar() } }, Modifier.fillMaxWidth(), Variante.Secundario,
                icono = "descargar", cargando = a.revisando)
        }
    }
}
