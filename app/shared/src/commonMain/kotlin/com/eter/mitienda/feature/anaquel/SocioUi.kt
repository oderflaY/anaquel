package com.eter.mitienda.feature.anaquel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.util.formatPrice

// «Tu socio»: plan del día del asesor y tarjetas de lo que recomienda el grafo de la red.

private val VerdeSocio = Color(0xFF0E7C5A)
private val AmbarSocio = Color(0xFFC77700)
private val RojoSocio = Color(0xFFC0392B)
private val MoradoSocio = Color(0xFF8E44AD)
private val AzulSocio = Color(0xFF2F5DA8)

private fun colorTipo(tipo: String) = when (tipo) {
    "surtir" -> RojoSocio
    "nuevo" -> MoradoSocio
    "proveedor" -> AzulSocio
    "vender" -> AmbarSocio
    else -> VerdeSocio
}

private fun nombreTipo(tipo: String) = when (tipo) {
    "surtir" -> "Surtir"
    "nuevo" -> "Nuevo para ti"
    "proveedor" -> "De confianza"
    "vender" -> "Para vender"
    "exclusivo" -> "Solo para ti"
    else -> "Te conviene"
}

@Composable
private fun EtiquetaTipo(tipo: String) {
    val c = colorTipo(tipo)
    Text(
        nombreTipo(tipo).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c, maxLines = 1,
        modifier = Modifier.background(c.copy(alpha = 0.12f), NeuShapes.Pill).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Una recomendación del grafo. [pedir] abre el chat con el pedido armado
 * (tienda, producto, cantidad); [negociar] abre el trato; [escribir] el chat solo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TarjetaRecomendacion(
    r: Recomendacion,
    pedir: ((Long, Long, Long) -> Unit)?,
    negociar: (ResultadoRed) -> Unit,
    escribir: ((Long) -> Unit)? = null,
) {
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EtiquetaTipo(r.tipo)
            Spacer(Modifier.weight(1f))
            if (r.ganancia > 0 && r.tipo != "vender") Pastilla("ganas ${r.ganancia}%")
        }
        Spacer(Modifier.height(NeuDimens.SpaceSm))
        Txt(r.titulo, negritas = true, lineas = 2)
        if (r.motivo.isNotBlank()) Txt(r.motivo, secundario = true, chico = true)
        val o = r.oferta
        if (o != null && r.tipo != "vender") {
            val costo = if (o.precioMayoreoC > 0) o.precioMayoreoC else o.precioC
            Spacer(Modifier.height(NeuDimens.SpaceXs))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm), verticalAlignment = Alignment.CenterVertically) {
                Txt("${formatPrice(costo)} c/u" + (o.minimoMayoreo.takeIf { it > 1 }?.let { " desde $it" } ?: ""), chico = true, negritas = true)
                if (r.cantidad > 0) Txt("· sugiero ${r.cantidad}", chico = true, secundario = true, lineas = 1)
                km(r.distanciaKm)?.let { Txt("· $it", chico = true, secundario = true, lineas = 1) }
            }
        }
        val t = r.tienda ?: return@NeumorphicCard
        Spacer(Modifier.height(NeuDimens.SpaceMd))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd), verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm)) {
            if (r.tipo == "vender") {
                escribir?.let { e -> NeumorphicButton("Ofrecérselo", onClick = { e(t.id) }) }
            } else {
                if (o != null && pedir != null) {
                    NeumorphicButton("Pedir ${r.cantidad.coerceAtLeast(o.minimoMayoreo).coerceAtLeast(1)}", onClick = {
                        pedir(t.id, o.id, r.cantidad.coerceAtLeast(o.minimoMayoreo).coerceAtLeast(1))
                    })
                }
                r.comoResultado()?.let { res -> NeumorphicButton("Negociar", onClick = { negociar(res) }, containerColor = NeuColors.Blue) }
            }
        }
    }
}

@Composable
private fun TarjetaCifra(c: CifraPlan, modifier: Modifier) {
    val tono = when (c.tono) { "mal" -> RojoSocio; "ojo" -> AmbarSocio; "bien" -> VerdeSocio; else -> NeuColors.TextPrimary }
    NeumorphicCard(modifier) {
        Txt(c.etiqueta, secundario = true, chico = true, lineas = 1)
        Text(c.valor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tono, maxLines = 1)
        if (c.detalle.isNotBlank()) Txt(c.detalle, secundario = true, chico = true, lineas = 2)
    }
}

/** Encabezado del asesor: saludo, cifras del negocio, lo urgente, el consejo y lo que sale del grafo. */
internal fun LazyListScope.planSocio(plan: PlanAsesor, vm: AnaquelViewModel, negociar: (ResultadoRed) -> Unit) {
    item(key = "plan-saludo") {
        Column(Modifier.fillMaxWidth().background(VerdeSocio, NeuShapes.Large).padding(16.dp)) {
            Text(plan.saludo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(plan.resumen, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
        }
    }
    plan.cifras.chunked(2).forEachIndexed { i, fila ->
        item(key = "plan-cifras-$i") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                fila.forEach { TarjetaCifra(it, Modifier.weight(1f)) }
                if (fila.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (plan.alertas.isNotEmpty()) {
        item(key = "plan-alertas-t") { Txt("Lo que atendería hoy", negritas = true) }
        items(plan.alertas, key = { "plan-a-" + it.tipo + it.titulo }) { a ->
            val c = when (a.tipo) { "acaba" -> RojoSocio; "parado" -> AmbarSocio; "temporada" -> MoradoSocio; else -> AzulSocio }
            NeumorphicCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).width(8.dp).height(8.dp).background(c, NeuShapes.Pill))
                    Spacer(Modifier.width(NeuDimens.SpaceMd))
                    Column(Modifier.weight(1f)) {
                        Txt(a.titulo, negritas = true)
                        Txt(a.texto, secundario = true, chico = true)
                        if (a.accion.isNotBlank()) {
                            Spacer(Modifier.height(NeuDimens.SpaceSm))
                            NeumorphicButton("Pregúntale a tu socio", onClick = { vm.preguntar(a.accion) }, containerColor = c)
                        }
                    }
                }
            }
        }
    }
    if (plan.consejo.isNotBlank()) {
        item(key = "plan-consejo") {
            NeumorphicCard(Modifier.fillMaxWidth()) {
                Txt("El consejo de hoy", negritas = true, color = VerdeSocio)
                Txt(plan.consejo)
            }
        }
    }
    if (plan.recomendaciones.isNotEmpty()) {
        item(key = "plan-recos-t") {
            Column {
                Txt("Lo que encontré en la red para ti", negritas = true)
                Txt("Comparé tu tienda con los proveedores y con tiendas parecidas a la tuya.", secundario = true, chico = true)
            }
        }
        items(plan.recomendaciones.filter { it.tipo != "vender" }.take(5), key = { "plan-r-" + it.clave }) { r ->
            TarjetaRecomendacion(r, pedir = null, negociar = negociar)
        }
    }
    item(key = "plan-chat-t") {
        Column(Modifier.padding(top = NeuDimens.SpaceSm)) {
            Txt("Platica con tu socio", negritas = true)
            Txt("Pregúntame lo que quieras de tu negocio; reviso tus números y la red antes de contestarte.", secundario = true, chico = true)
        }
    }
}
