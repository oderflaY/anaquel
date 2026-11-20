package com.eter.mitienda.feature.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.image.LocalImageState
import com.eter.mitienda.core.image.rememberLocalImage
import com.eter.mitienda.core.util.centsToInput
import com.eter.mitienda.feature.anaquel.BotonCircular
import com.eter.mitienda.feature.anaquel.BotonVerde
import com.eter.mitienda.feature.anaquel.ChipVerde
import com.eter.mitienda.feature.anaquel.NumeroAnimado
import com.eter.mitienda.feature.anaquel.VerdeClaro
import com.eter.mitienda.feature.anaquel.VerdeMarca
import com.eter.mitienda.feature.anaquel.entrada
import com.eter.mitienda.feature.anaquel.iconoDe
import com.eter.mitienda.feature.anaquel.rebote
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.scanner.NeumorphicScannerModal
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.domain.model.CartItem
import com.eter.mitienda.domain.model.Product
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_arrow_back
import mitienda.shared.generated.resources.ic_barcode
import mitienda.shared.generated.resources.ic_cart
import mitienda.shared.generated.resources.ic_delete
import mitienda.shared.generated.resources.ic_remove
import mitienda.shared.generated.resources.ic_search
import org.jetbrains.compose.resources.painterResource

private val Rojo = Color(0xFFE5484D)

/**
 * Punto de venta: buscador/escáner arriba, carrito en medio y el resumen de
 * cobro fijo abajo (total, efectivo recibido, cambio y "Cobrar").
 */
@Composable
fun SaleRoute(
    viewModel: CartViewModel,
    onBack: () -> Unit,
    onSaleCompleted: (saleId: Long) -> Unit,
) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val items = viewModel.items

    // Venta cobrada → ticket.
    LaunchedEffect(viewModel.completedSaleId) {
        viewModel.completedSaleId?.let { id ->
            viewModel.onSaleShown()
            onSaleCompleted(id)
        }
    }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            viewModel.onMessageShown()
        }
    }

    // Salir con productos en el carrito pide confirmación (no perder una venta a medias).
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = items.isNotEmpty() && !showScanner,
        onBackCompleted = { showDiscard = true },
    )
    val requestExit: () -> Unit = {
        if (items.isEmpty()) onBack() else showDiscard = true
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(NeuColors.Background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            SaleHeader(
                itemCount = viewModel.itemCount,
                totalCents = viewModel.totalCents,
                canClear = items.isNotEmpty(),
                onBack = requestExit,
                onClear = { showDiscard = true },
            )

            CampoVenta(
                viewModel.query, viewModel::onQueryChange,
                onEnter = { viewModel.onQuerySubmit(catalog) }, onEscanear = { showScanner = true },
            )

            val suggestions = viewModel.suggestions(catalog)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = NeuDimens.ScreenPadding,
                    end = NeuDimens.ScreenPadding,
                    top = NeuDimens.SpaceXl,
                    bottom = NeuDimens.SpaceXl,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (suggestions.isNotEmpty()) {
                    item(key = "suggestions-title") {
                        Text("Toca para agregar", fontSize = 15.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
                    }
                    items(suggestions, key = { "s-${it.id}" }) { product ->
                        SuggestionCard(product, onClick = { viewModel.onSuggestionSelected(product) })
                    }
                } else if (items.isEmpty()) {
                    item(key = "empty") {
                        val rapidos = remember(catalog) { catalog.filter { it.stock > 0 }.sortedByDescending { it.stock }.take(9) }
                        EmptyCart(rapidos, onScan = { showScanner = true }, onAgregar = viewModel::onSuggestionSelected)
                    }
                }
                items(items, key = { "c-${it.product.id}" }) { item ->
                    CartItemCard(
                        item = item,
                        onIncrement = { viewModel.increment(item.product.id) },
                        onDecrement = { viewModel.decrement(item.product.id) },
                        onRemove = { viewModel.remove(item.product.id) },
                    )
                }
            }

            if (items.isNotEmpty()) {
                CheckoutPanel(
                    itemCount = viewModel.itemCount,
                    totalCents = viewModel.totalCents,
                    received = viewModel.received,
                    receivedError = viewModel.receivedError,
                    changeCents = viewModel.changeCents,
                    isCharging = viewModel.isCharging,
                    onReceivedChange = viewModel::onReceivedChange,
                    onCharge = viewModel::charge,
                )
            }
        }

        SnackbarHost(
            snackbarHostState,
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(NeuDimens.SpaceLg),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = NeuShapes.Large,
                containerColor = NeuColors.TextPrimary,
                contentColor = NeuColors.Background,
                dismissActionContentColor = NeuColors.Background,
            )
        }

        if (showScanner) {
            NeumorphicScannerModal(
                onBarcodeScanned = viewModel::onBarcodeScanned,
                onDismiss = { showScanner = false },
                continuous = true,
                statusText = viewModel.scanStatus,
            )
        }
    }

    if (showDiscard) {
        DiscardSaleDialog(
            onDiscard = {
                showDiscard = false
                viewModel.clear()
                onBack()
            },
            onDismiss = { showDiscard = false },
        )
    }
}

@Composable
private fun SaleHeader(itemCount: Int, totalCents: Long, canClear: Boolean, onBack: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotonCircular(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", 44, onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("CAJA", color = VerdeMarca, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Text("Nueva venta", fontSize = 22.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
            Text(
                when (itemCount) {
                    0 -> "Escanea o busca para empezar"
                    1 -> "1 artículo · ${formatPrice(totalCents)}"
                    else -> "$itemCount artículos · ${formatPrice(totalCents)}"
                },
                fontSize = 13.sp, color = NeuColors.TextSecondary,
            )
        }
        if (canClear) {
            val fuente = remember { MutableInteractionSource() }
            Box(
                Modifier.size(44.dp).rebote(fuente, 0.85f).background(Rojo.copy(alpha = 0.1f), CircleShape)
                    .clickable(interactionSource = fuente, indication = null, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DeleteOutline, "Vaciar carrito", tint = Rojo, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** Buscador plano con botón verde para escanear. Enter agrega (sirve con lector USB/Bluetooth). */
@Composable
private fun CampoVenta(valor: String, onCambio: (String) -> Unit, onEnter: () -> Unit, onEscanear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp)
            .shadow(6.dp, RoundedCornerShape(18.dp), spotColor = NeuColors.ShadowSoft)
            .background(Color.White, RoundedCornerShape(18.dp)).border(1.dp, NeuColors.Hairline, RoundedCornerShape(18.dp))
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = NeuColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            valor, onCambio, Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = NeuColors.TextPrimary), cursorBrush = SolidColor(VerdeMarca),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onEnter() }),
            decorationBox = { campo -> if (valor.isEmpty()) Text("Buscar por nombre o código", color = NeuColors.TextSecondary, fontSize = 16.sp); campo() },
        )
        val fuente = remember { MutableInteractionSource() }
        Row(
            Modifier.rebote(fuente, 0.9f).clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(VerdeClaro, VerdeMarca)))
                .clickable(interactionSource = fuente, indication = null, onClick = onEscanear).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.QrCodeScanner, "Escanear productos", tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Escanear", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** Carrito vacío: explicación corta y los productos para agregarlos con un toque. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyCart(rapidos: List<Product>, onScan: () -> Unit, onAgregar: (Product) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth().entrada().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF12A374), Color(0xFF0B3D2E)))).clickable(onClick = onScan).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Cobra en segundos", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                Text("Escanea los productos y el total se calcula solo. El inventario baja al cobrar.",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(56.dp).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.QrCodeScanner, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        if (rapidos.isNotEmpty()) {
            Text("Toca para agregar", fontSize = 15.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
            FlowRow(
                Modifier.fillMaxWidth(), maxItemsInEachRow = 3,
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rapidos.forEach { p ->
                    val fuente = remember { MutableInteractionSource() }
                    Column(
                        Modifier.weight(1f).rebote(fuente, 0.93f).clip(RoundedCornerShape(18.dp)).background(Color.White)
                            .border(1.dp, NeuColors.Hairline, RoundedCornerShape(18.dp))
                            .clickable(interactionSource = fuente, indication = null) { onAgregar(p) }.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MiniProducto(p, 54)
                        Spacer(Modifier.height(6.dp))
                        Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 14.sp, modifier = Modifier.heightIn(min = 28.dp))
                        Text(formatPrice(p.priceCents), fontSize = 13.sp, fontWeight = FontWeight.Black, color = VerdeMarca)
                    }
                }
                repeat((3 - rapidos.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Foto local del producto o su ícono sobre gris claro (mismo look que Miniatura de la red). */
@Composable
private fun MiniProducto(p: Product, tam: Int = 52) {
    val image = rememberLocalImage(p.imagePath, maxSizePx = 192)
    Box(
        Modifier.size(tam.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF4F6F5)),
        contentAlignment = Alignment.Center,
    ) {
        if (image is LocalImageState.Loaded) {
            Image(image.bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(iconoDe(p.name), null, tint = VerdeMarca.copy(alpha = 0.6f), modifier = Modifier.size((tam * 0.5f).dp))
        }
    }
}

@Composable
private fun SuggestionCard(product: Product, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().rebote(fuente, 0.97f).clip(RoundedCornerShape(18.dp)).background(Color.White)
            .border(1.dp, NeuColors.Hairline, RoundedCornerShape(18.dp))
            .clickable(interactionSource = fuente, indication = null, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniProducto(product, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(product.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${product.stock} en inventario · ${product.barcode}", fontSize = 12.sp, color = NeuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(formatPrice(product.priceCents), fontSize = 15.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(32.dp).background(VerdeMarca, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "Agregar", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CartItemCard(item: CartItem, onIncrement: () -> Unit, onDecrement: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().entrada().clip(RoundedCornerShape(20.dp)).background(Color.White)
            .border(1.dp, if (item.exceedsStock) Rojo.copy(alpha = 0.5f) else NeuColors.Hairline, RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniProducto(item.product, 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.product.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Text("${formatPrice(item.product.priceCents)} c/u", fontSize = 12.sp, color = NeuColors.TextSecondary)
            if (item.exceedsStock) Text("Solo hay ${item.product.stock} en inventario", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Rojo)
            Text(formatPrice(item.subtotalCents), fontSize = 16.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.background(NeuColors.Background, CircleShape).border(1.dp, NeuColors.Hairline, CircleShape).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotonContador(
                if (item.quantity == 1) Icons.Rounded.DeleteOutline else Icons.Rounded.Remove,
                if (item.quantity == 1) "Quitar ${item.product.name}" else "Una pieza menos",
                if (item.quantity == 1) Rojo else VerdeMarca,
                if (item.quantity == 1) onRemove else onDecrement,
            )
            Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                NumeroAnimado(item.quantity, NeuColors.TextPrimary, 17.sp, FontWeight.Black)
            }
            BotonContador(Icons.Rounded.Add, "Una pieza más", VerdeMarca, onIncrement)
        }
    }
}

@Composable
private fun BotonContador(icono: ImageVector, descripcion: String, color: Color, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Box(
        Modifier.size(34.dp).rebote(fuente, 0.8f).shadow(2.dp, CircleShape, spotColor = NeuColors.ShadowSoft).background(Color.White, CircleShape)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick).semantics { contentDescription = descripcion },
        contentAlignment = Alignment.Center,
    ) { Icon(icono, null, tint = color, modifier = Modifier.size(18.dp)) }
}

/** Resumen fijo abajo: total grande, efectivo recibido con billetes rápidos, cambio y «Cobrar». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckoutPanel(
    itemCount: Int,
    totalCents: Long,
    received: String,
    receivedError: String?,
    changeCents: Long?,
    isCharging: Boolean,
    onReceivedChange: (String) -> Unit,
    onCharge: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), spotColor = NeuColors.ShadowSoft)
            .background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("TOTAL", color = NeuColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                Text(formatPrice(totalCents), fontSize = 32.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("CAMBIO", color = NeuColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                Text(changeCents?.takeIf { it >= 0 }?.let(::formatPrice) ?: "—", fontSize = 24.sp, fontWeight = FontWeight.Black,
                    color = if ((changeCents ?: -1) >= 0) VerdeMarca else NeuColors.TextSecondary)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 50.dp).background(NeuColors.Background, RoundedCornerShape(14.dp))
                .border(1.dp, if (receivedError != null) Rojo.copy(alpha = 0.6f) else NeuColors.Hairline, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recibí $", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextSecondary)
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                received, onReceivedChange, Modifier.weight(1f), singleLine = true,
                textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary), cursorBrush = SolidColor(VerdeMarca),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCharge() }),
                decorationBox = { campo -> if (received.isEmpty()) Text("opcional", color = NeuColors.TextSecondary, fontSize = 15.sp); campo() },
            )
        }
        // Billetes rápidos: el exacto y los que alcanzan a cubrir el total.
        val billetes = (listOf(totalCents) + listOf(5000L, 10000L, 20000L, 50000L).filter { it > totalCents }).distinct().take(4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            billetes.forEach { b ->
                val txt = centsToInput(b)
                ChipVerde(if (b == totalCents) "Exacto" else formatPrice(b), received == txt) { onReceivedChange(txt) }
            }
        }
        receivedError?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Rojo) }
        BotonVerde(
            if (isCharging) "Registrando…" else "Cobrar ${formatPrice(totalCents)}",
            Icons.Rounded.PointOfSale, habilitado = !isCharging, onClick = onCharge,
        )
        Text(if (itemCount == 1) "1 artículo" else "$itemCount artículos", fontSize = 12.sp, color = NeuColors.TextSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DiscardSaleDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(NeuDimens.SpaceLg)
                .neumorphic(NeuShapes.Large, NeuDimens.ElevationLarge)
                .padding(NeuDimens.SpaceXl),
        ) {
            Text("¿Descartar la venta?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
            Spacer(Modifier.size(NeuDimens.SpaceMd))
            Text(
                "Se vaciará el carrito. No se registra nada ni cambia el inventario.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextSecondary,
            )
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton(text = "Seguir", onClick = onDismiss, containerColor = NeuColors.Background, modifier = Modifier.weight(1f))
                NeumorphicButton(text = "Descartar", onClick = onDiscard, containerColor = NeuColors.LowStockContainer, modifier = Modifier.weight(1f))
            }
        }
    }
}
