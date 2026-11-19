package com.eter.mitienda.feature.sale

import androidx.compose.foundation.background
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
                canClear = items.isNotEmpty(),
                onBack = requestExit,
                onClear = { showDiscard = true },
            )

            NeumorphicInput(
                value = viewModel.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Buscar por nombre o código",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NeuDimens.ScreenPadding),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Enter agrega: así también funciona un lector de códigos USB/Bluetooth.
                keyboardActions = KeyboardActions(onSearch = { viewModel.onQuerySubmit(catalog) }),
                leadingIcon = {
                    Icon(
                        painterResource(Res.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(NeuDimens.IconSize),
                        tint = NeuColors.TextSecondary,
                    )
                },
                trailingIcon = {
                    NeumorphicIconButton(
                        icon = painterResource(Res.drawable.ic_barcode),
                        contentDescription = "Escanear productos",
                        onClick = { showScanner = true },
                        size = NeuDimens.IconButtonSmallSize,
                        iconSize = NeuDimens.IconSmallSize,
                        containerColor = NeuColors.Blue,
                        elevation = NeuDimens.ElevationSmall,
                    )
                },
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
                verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg),
            ) {
                if (suggestions.isNotEmpty()) {
                    item(key = "suggestions-title") {
                        Text("Toca para agregar", style = MaterialTheme.typography.labelMedium, color = NeuColors.TextSecondary)
                    }
                    items(suggestions, key = { "s-${it.id}" }) { product ->
                        SuggestionCard(product, onClick = { viewModel.onSuggestionSelected(product) })
                    }
                } else if (items.isEmpty()) {
                    item(key = "empty") { EmptyCart(onScan = { showScanner = true }) }
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
private fun SaleHeader(itemCount: Int, canClear: Boolean, onBack: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = NeuDimens.ScreenPadding,
                end = NeuDimens.ScreenPadding,
                top = NeuDimens.SpaceLg,
                bottom = NeuDimens.SpaceXl,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeumorphicIconButton(icon = painterResource(Res.drawable.ic_arrow_back), contentDescription = "Volver", onClick = onBack)
        Spacer(Modifier.width(NeuDimens.SpaceLg))
        Column(Modifier.weight(1f)) {
            Text("Nueva venta", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
            Text(
                when (itemCount) {
                    0 -> "Carrito vacío"
                    1 -> "1 artículo"
                    else -> "$itemCount artículos"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextSecondary,
            )
        }
        if (canClear) {
            NeumorphicIconButton(
                icon = painterResource(Res.drawable.ic_delete),
                contentDescription = "Vaciar carrito",
                onClick = onClear,
                iconTint = NeuColors.OnLowStock,
            )
        }
    }
}

@Composable
private fun EmptyCart(onScan: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = NeuDimens.SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).neumorphic(NeuShapes.Circle, NeuDimens.ElevationLarge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(Res.drawable.ic_cart), contentDescription = null, tint = NeuColors.Magenta, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.size(NeuDimens.SpaceXl))
        Text(
            "Escanea los productos o búscalos por nombre.\nEl total se calcula solo.",
            style = MaterialTheme.typography.bodyMedium,
            color = NeuColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(NeuDimens.SpaceXl))
        NeumorphicButton(
            text = "Escanear productos",
            icon = painterResource(Res.drawable.ic_barcode),
            containerColor = NeuColors.Blue,
            onClick = onScan,
        )
    }
}

@Composable
private fun SuggestionCard(product: Product, onClick: () -> Unit) {
    NeumorphicCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = NeuDimens.ElevationMedium,
        contentPadding = PaddingValues(horizontal = NeuDimens.SpaceLg, vertical = NeuDimens.SpaceMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium, color = NeuColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${product.barcode} · ${product.stock} en inventario",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(NeuDimens.SpaceSm))
            Text(formatPrice(product.priceCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
            Spacer(Modifier.width(NeuDimens.SpaceSm))
            Icon(painterResource(Res.drawable.ic_add), contentDescription = "Agregar", tint = NeuColors.Magenta, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CartItemCard(
    item: CartItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRemove: () -> Unit,
) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.product.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatPrice(item.product.priceCents)} c/u",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuColors.TextSecondary,
                )
                if (item.exceedsStock) {
                    Text(
                        "Solo hay ${item.product.stock} en inventario",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NeuColors.OnLowStock,
                    )
                }
            }
            Spacer(Modifier.width(NeuDimens.SpaceSm))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatPrice(item.subtotalCents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary,
                )
                Spacer(Modifier.size(NeuDimens.SpaceSm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Con 1 pieza el botón izquierdo quita el renglón.
                    if (item.quantity == 1) {
                        NeumorphicIconButton(
                            icon = painterResource(Res.drawable.ic_delete),
                            contentDescription = "Quitar ${item.product.name}",
                            onClick = onRemove,
                            size = 32.dp,
                            iconSize = 16.dp,
                            elevation = NeuDimens.ElevationSmall,
                            iconTint = NeuColors.OnLowStock,
                        )
                    } else {
                        NeumorphicIconButton(
                            icon = painterResource(Res.drawable.ic_remove),
                            contentDescription = "Una pieza menos",
                            onClick = onDecrement,
                            size = 32.dp,
                            iconSize = 16.dp,
                            elevation = NeuDimens.ElevationSmall,
                        )
                    }
                    Text(
                        item.quantity.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(40.dp),
                    )
                    NeumorphicIconButton(
                        icon = painterResource(Res.drawable.ic_add),
                        contentDescription = "Una pieza más",
                        onClick = onIncrement,
                        size = 32.dp,
                        iconSize = 16.dp,
                        elevation = NeuDimens.ElevationSmall,
                    )
                }
            }
        }
    }
}

/** Resumen fijo abajo: total grande, efectivo recibido, cambio y "Cobrar". */
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NeuDimens.SpaceSm)
            .neumorphic(
                RoundedCornerShape(topStart = NeuDimens.SpaceXl, topEnd = NeuDimens.SpaceXl),
                NeuDimens.ElevationLarge,
            )
            .padding(NeuDimens.ScreenPadding),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (itemCount == 1) "1 artículo" else "$itemCount artículos",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text("Total ", style = MaterialTheme.typography.titleMedium, color = NeuColors.TextSecondary)
            Text(
                formatPrice(totalCents),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary,
            )
        }
        Spacer(Modifier.size(NeuDimens.SpaceLg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NeumorphicInput(
                    value = received,
                    onValueChange = onReceivedChange,
                    placeholder = "Recibido (opcional)",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCharge() }),
                    leadingIcon = {
                        Text("$", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = NeuColors.TextSecondary)
                    },
                )
            }
            Spacer(Modifier.width(NeuDimens.SpaceLg))
            Column(horizontalAlignment = Alignment.End) {
                Text("Cambio", style = MaterialTheme.typography.labelMedium, color = NeuColors.TextSecondary)
                Text(
                    changeCents?.takeIf { it >= 0 }?.let(::formatPrice) ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.Blue,
                )
            }
        }
        if (receivedError != null) {
            Text(
                receivedError,
                style = MaterialTheme.typography.bodySmall,
                color = NeuColors.OnLowStock,
                modifier = Modifier.padding(start = NeuDimens.SpaceXs, top = NeuDimens.SpaceSm),
            )
        }
        Spacer(Modifier.size(NeuDimens.SpaceLg))
        NeumorphicButton(
            text = if (isCharging) "Registrando…" else "Cobrar ${formatPrice(totalCents)}",
            onClick = onCharge,
            enabled = !isCharging,
            elevation = NeuDimens.ElevationLarge,
            modifier = Modifier.fillMaxWidth(),
        )
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
