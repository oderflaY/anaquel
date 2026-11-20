package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.util.formatPrice

// Perfil de una tienda de la red: portada, sello de verificada, cifras,
// condiciones (horario, reparto, crédito, pagos) y su catálogo con fotos.
// Unas tiendas lo tienen completo (el mayorista) y otras casi vacío.

private val Verde = Color(0xFF0E7C5A)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HojaPerfil(
    p: PerfilTienda, onCerrar: () -> Unit, onChat: () -> Unit, onPedir: () -> Unit, onProducto: (ItemCatalogo) -> Unit,
) {
    val nombre = p.nombre.removeSuffix(" (demo)")
    val mayorista = p.giro.contains("mayor", true) || p.giro.contains("distrib", true) || (p.clientes ?: 0) > 30
    ModalBottomSheet(
        onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NeuColors.Background, dragHandle = null, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxWidth().heightIn(max = 2000.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- Portada + avatar ----------
            item(span = { GridItemSpan(2) }, key = "portada") {
                Box(Modifier.fillMaxWidth().height(196.dp)) {
                    Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.linearGradient(listOf(Color(0xFF12A374), Color(0xFF0B3D2E))))) {
                        if (p.portada != null) {
                            FotoRed(p.portada, null, Modifier.fillMaxSize(), escala = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0xAA0B3D2E)))))
                        }
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(40.dp, 4.dp).background(Color.White.copy(alpha = 0.7f), CircleShape))
                        if (mayorista) Text("MAYORISTA · SURTE A TIENDITAS", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp,
                            modifier = Modifier.align(Alignment.TopStart).padding(16.dp, 24.dp).background(Color(0x55000000), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(start = 18.dp).size(88.dp).pop().shadow(10.dp, CircleShape)
                            .background(Color.White, CircleShape).border(4.dp, NeuColors.Background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(iniciales(nombre), fontSize = 30.sp, fontWeight = FontWeight.Black, color = Verde)
                    }
                    if (p.completo > 0) Completitud(p.completo, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 4.dp))
                }
            }
            // ---------- Nombre, sellos y descripción ----------
            item(span = { GridItemSpan(2) }, key = "nombre") {
                Column(Modifier.padding(horizontal = 18.dp).entrada(0), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nombre, fontSize = 24.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), lineHeight = 27.sp)
                        if (p.verificada) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Ico.Verificado, "Verificada", tint = Color(0xFF1D9BF0), modifier = Modifier.size(22.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.giro.isNotBlank()) Text(p.giro, fontSize = 13.sp, color = NeuColors.TextSecondary)
                        p.calificacion?.takeIf { it > 0 }?.let { c ->
                            Icon(Icons.Rounded.Star, null, tint = Color(0xFFF5A623), modifier = Modifier.size(16.dp))
                            Text("$c", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
                            p.resenas?.let { Text("($it reseñas)", fontSize = 13.sp, color = NeuColors.TextSecondary) }
                        }
                        km(p.distanciaKm)?.let { Text(it, fontSize = 13.sp, color = NeuColors.TextSecondary) }
                    }
                    if (p.descripcion.isNotBlank()) Text(p.descripcion, fontSize = 15.sp, color = NeuColors.TextPrimary, lineHeight = 21.sp)
                    if (p.etiquetas.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.etiquetas.forEach {
                            Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Verde,
                                modifier = Modifier.background(Verde.copy(alpha = 0.1f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                }
            }
            // ---------- Cifras ----------
            item(span = { GridItemSpan(2) }, key = "cifras") {
                Row(Modifier.padding(horizontal = 18.dp).entrada(1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cifra("${p.productos}", "productos", Modifier.weight(1f))
                    p.clientes?.takeIf { it > 0 }?.let { Cifra("$it", "tiendas surte", Modifier.weight(1f)) }
                    p.desde?.takeIf { it > 0 }?.let { Cifra("$it", "desde", Modifier.weight(1f)) }
                    if (p.tratos > 0) Cifra("${p.tratos}", "tratos", Modifier.weight(1f))
                }
            }
            // ---------- Botones ----------
            item(span = { GridItemSpan(2) }, key = "botones") {
                Row(Modifier.padding(horizontal = 18.dp).entrada(2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Boton("Hacer pedido", Ico.Paquete, true, Modifier.weight(1f), onPedir)
                    Boton("Mensaje", Ico.Chat, false, Modifier.weight(1f), onChat)
                }
            }
            // ---------- Condiciones ----------
            val filas = listOfNotNull(
                p.horario?.let { Triple(Icons.Rounded.AccessTime, "Horario", it) },
                p.entrega?.let { Triple(Icons.Rounded.LocalShipping, "Reparto", it) },
                p.pedidoMinimoC?.takeIf { it > 0 }?.let { Triple(Ico.Paquete, "Pedido mínimo", formatPrice(it)) },
                p.credito?.let { Triple(Icons.Rounded.CreditCard, "Crédito", it) },
                p.pagos?.let { Triple(Icons.Rounded.Payments, "Formas de pago", it) },
                p.zona?.let { Triple(Icons.Rounded.Map, "Zona de reparto", it) },
                p.direccion.takeIf { it.isNotBlank() }?.let { Triple(Ico.Ubicacion, "Dirección", it) },
            )
            if (filas.isNotEmpty()) item(span = { GridItemSpan(2) }, key = "condiciones") {
                Column(
                    Modifier.padding(horizontal = 18.dp).entrada(3).fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(vertical = 6.dp),
                ) {
                    filas.forEachIndexed { i, (ico, t, v) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).background(Verde.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(ico, null, tint = Verde, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t, fontSize = 12.sp, color = NeuColors.TextSecondary)
                                Text(v, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary)
                            }
                        }
                        if (i < filas.lastIndex) Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(Color(0x0F000000)))
                    }
                }
            } else item(span = { GridItemSpan(2) }, key = "sin-datos") {
                Text("Esta tienda todavía no llena su perfil. Escríbele para conocer horarios y entregas.", fontSize = 14.sp,
                    color = NeuColors.TextSecondary, modifier = Modifier.padding(horizontal = 18.dp))
            }
            // ---------- Catálogo ----------
            if (p.catalogo.isNotEmpty()) {
                item(span = { GridItemSpan(2) }, key = "cat-tit") {
                    Text("Catálogo", fontSize = 18.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary, modifier = Modifier.padding(start = 18.dp, top = 6.dp))
                }
                items(p.catalogo, key = { "c" + it.productoId }) { it2 ->
                    val lado = if (p.catalogo.indexOf(it2) % 2 == 0) PaddingValues(start = 18.dp) else PaddingValues(end = 18.dp)
                    Producto(it2, Modifier.padding(lado)) { onProducto(it2) }
                }
            }
        }
    }
}

private fun iniciales(n: String) = n.split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "T" }

@Composable
private fun Completitud(pct: Int, modifier: Modifier) {
    var arranca by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arranca = true }
    val v by animateFloatAsState(if (arranca) pct / 100f else 0f, tween(900))
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text("Perfil $pct% completo", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(120.dp).height(6.dp).clip(CircleShape).background(Color(0x14000000))) {
            Box(Modifier.fillMaxWidth(v).height(6.dp).background(if (pct >= 80) Verde else Color(0xFFE0871B), CircleShape))
        }
    }
}

@Composable
private fun Cifra(valor: String, texto: String, modifier: Modifier) {
    Column(modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, fontSize = 18.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
        Text(texto, fontSize = 11.sp, color = NeuColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun Boton(texto: String, icono: ImageVector, lleno: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Row(
        modifier.heightIn(min = 50.dp).rebote(fuente, 0.95f).clip(RoundedCornerShape(16.dp))
            .background(if (lleno) Verde else Color.White).border(1.5.dp, Verde, RoundedCornerShape(16.dp))
            .clickable(interactionSource = fuente, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = if (lleno) Color.White else Verde, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(texto, color = if (lleno) Color.White else Verde, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

@Composable
private fun Producto(it: ItemCatalogo, modifier: Modifier, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Column(
        modifier.rebote(fuente, 0.95f).background(Color.White, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = fuente, indication = null, onClick = onClick).padding(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFF4F6F5), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            FotoRed(it.foto, it.nombre, Modifier.fillMaxSize().padding(8.dp)) {
                Icon(iconoDe(it.nombre), null, tint = Verde.copy(alpha = 0.6f), modifier = Modifier.size(44.dp))
            }
            if (it.precioMayoreoC > 0 && it.minimoMayoreo > 0) Text("MAYOREO", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).offset(4.dp, 4.dp).background(Color(0xFFE5484D), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(it.nombre, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatPrice(it.precioC), fontSize = 15.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
            Text(" /${it.unidad}", fontSize = 11.sp, color = NeuColors.TextSecondary)
        }
        if (it.precioMayoreoC > 0 && it.minimoMayoreo > 0) Text("${formatPrice(it.precioMayoreoC)} desde ${it.minimoMayoreo}", fontSize = 11.sp, color = Verde, fontWeight = FontWeight.Bold)
    }
}
