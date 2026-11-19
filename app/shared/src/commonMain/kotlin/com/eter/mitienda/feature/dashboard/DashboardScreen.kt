package com.eter.mitienda.feature.dashboard

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicChip
import com.eter.mitienda.core.designsystem.components.NeumorphicFab
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.theme.MiTiendaTheme
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.scanner.NeumorphicScannerModal
import com.eter.mitienda.domain.model.Category
import com.eter.mitienda.domain.model.Product
import com.eter.mitienda.feature.dashboard.components.ProductCard
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_barcode
import mitienda.shared.generated.resources.ic_cart
import mitienda.shared.generated.resources.ic_bluetooth
import mitienda.shared.generated.resources.ic_search
import mitienda.shared.generated.resources.ic_settings
import org.jetbrains.compose.resources.painterResource

/**
 * Punto de entrada con estado: conecta el ViewModel con la UI sin estado y
 * monta, por encima de ella, el escáner y los avisos (Snackbar).
 * La navegación se recibe como callbacks para que la pantalla no la conozca.
 *
 * @param onCreateProduct abre el alta con el código escaneado ya prellenado.
 */
@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel,
    onManageCategories: () -> Unit,
    onBluetoothClick: () -> Unit,
    onAddProductClick: () -> Unit,
    onSellClick: () -> Unit,
    onProductClick: (Product) -> Unit,
    onCreateProduct: (barcode: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showScanner by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Muestra el aviso y, al terminar, lo marca como visto.
    // (Marcarlo antes cambiaría la clave y cancelaría este efecto a medias.)
    val pending = viewModel.event
    LaunchedEffect(pending) {
        val event = pending ?: return@LaunchedEffect
        val result = when (event) {
            is DashboardEvent.NotFound -> snackbarHostState.showSnackbar(
                message = "Producto no encontrado (${event.barcode})",
                actionLabel = "Crear producto",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            is DashboardEvent.Message -> snackbarHostState.showSnackbar(event.text, withDismissAction = true)
        }
        viewModel.onEventShown()
        if (event is DashboardEvent.NotFound && result == SnackbarResult.ActionPerformed) {
            onCreateProduct(event.barcode)
        }
    }

    Box(Modifier.fillMaxSize()) {
        DashboardScreen(
            state = state,
            query = viewModel.query,
            onQueryChange = viewModel::onQueryChange,
            onFilterSelected = viewModel::onFilterSelected,
            onAdjustStock = viewModel::onAdjustStock,
            onRetry = viewModel::onRetry,
            onManageCategories = onManageCategories,
            onBluetoothClick = onBluetoothClick,
            onScanClick = { showScanner = true },
            onAddProductClick = onAddProductClick,
            onSellClick = onSellClick,
            onProductClick = onProductClick,
        )

        NeuSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Por encima del FAB para no taparlo.
                .padding(bottom = NeuDimens.FabSize + NeuDimens.ScreenPadding),
        )

        if (showScanner) {
            NeumorphicScannerModal(
                onBarcodeScanned = viewModel::onBarcodeScanned,
                onDismiss = { showScanner = false },
            )
        }
    }
}

/** Snackbar con la paleta de la app: fondo oscuro de alto contraste y acción en magenta. */
@Composable
private fun NeuSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier.padding(horizontal = NeuDimens.SpaceLg)) { data ->
        Snackbar(
            snackbarData = data,
            shape = NeuShapes.Large,
            containerColor = NeuColors.TextPrimary,
            contentColor = NeuColors.Background,
            actionColor = NeuColors.MagentaOnDark,
            dismissActionContentColor = NeuColors.Background,
        )
    }
}

/** UI sin estado: recibe todo por parámetros (fácil de previsualizar y probar). */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onFilterSelected: (ProductFilter) -> Unit,
    onAdjustStock: (Product, Int) -> Unit,
    onRetry: () -> Unit,
    onManageCategories: () -> Unit,
    onBluetoothClick: () -> Unit,
    onScanClick: () -> Unit,
    onAddProductClick: () -> Unit,
    onSellClick: () -> Unit,
    onProductClick: (Product) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            DashboardHeader(
                totalProducts = state.totalProducts,
                onManageCategories = onManageCategories,
                onBluetoothClick = onBluetoothClick,
                modifier = Modifier.padding(
                    start = NeuDimens.ScreenPadding,
                    end = NeuDimens.ScreenPadding,
                    top = NeuDimens.SpaceLg,
                ),
            )

            Spacer(Modifier.size(NeuDimens.SpaceXl))

            // Buscador fijo: queda fuera de la lista, por lo que no se desplaza.
            NeumorphicInput(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Buscar, escanear o ubicación…",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NeuDimens.ScreenPadding),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(NeuDimens.IconSize),
                        tint = NeuColors.TextSecondary,
                    )
                },
                trailingIcon = {
                    NeumorphicIconButton(
                        icon = painterResource(Res.drawable.ic_barcode),
                        contentDescription = "Escanear código de barras",
                        onClick = onScanClick,
                        size = NeuDimens.IconButtonSmallSize,
                        iconSize = NeuDimens.IconSmallSize,
                        containerColor = NeuColors.Blue,
                        elevation = NeuDimens.ElevationSmall,
                    )
                },
            )

            FilterRow(
                categories = state.categories,
                lowStockCount = state.lowStockCount,
                selected = state.filter,
                onFilterSelected = onFilterSelected,
                onManageCategories = onManageCategories,
            )

            ProductList(
                state = state,
                query = query,
                onProductClick = onProductClick,
                onAdjustStock = onAdjustStock,
                onRetry = onRetry,
                modifier = Modifier.weight(1f),
            )
        }

        // Acción principal del día a día: abrir la caja.
        NeumorphicButton(
            text = "Vender",
            icon = painterResource(Res.drawable.ic_cart),
            containerColor = NeuColors.Blue,
            onClick = onSellClick,
            elevation = NeuDimens.ElevationLarge,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(NeuDimens.ScreenPadding)
                .height(NeuDimens.FabSize),
        )

        NeumorphicFab(
            icon = painterResource(Res.drawable.ic_add),
            contentDescription = "Agregar producto",
            onClick = onAddProductClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(NeuDimens.ScreenPadding),
        )
    }
}

@Composable
private fun DashboardHeader(
    totalProducts: Int,
    onManageCategories: () -> Unit,
    onBluetoothClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Inventario",
                style = MaterialTheme.typography.headlineMedium,
                color = NeuColors.TextPrimary,
            )
            Text(
                text = if (totalProducts == 1) "1 producto" else "$totalProducts productos",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextSecondary,
            )
        }
        NeumorphicIconButton(
            icon = painterResource(Res.drawable.ic_bluetooth),
            contentDescription = "Sincronizar por Bluetooth",
            onClick = onBluetoothClick,
        )
        Spacer(Modifier.width(NeuDimens.SpaceLg))
        NeumorphicIconButton(
            icon = painterResource(Res.drawable.ic_settings),
            contentDescription = "Categorías",
            onClick = onManageCategories,
        )
    }
}

/**
 * Chips de filtro: Todos, Stock bajo (con contador) y las categorías del
 * usuario. El último chip lleva a crear/editar categorías.
 */
@Composable
private fun FilterRow(
    categories: List<Category>,
    lowStockCount: Int,
    selected: ProductFilter,
    onFilterSelected: (ProductFilter) -> Unit,
    onManageCategories: () -> Unit,
) {
    // LazyRow recorta su contenido: el padding vertical deja espacio a las sombras.
    LazyRow(
        contentPadding = PaddingValues(
            start = NeuDimens.ScreenPadding,
            end = NeuDimens.ScreenPadding,
            top = NeuDimens.SpaceXl,
            bottom = NeuDimens.SpaceSm,
        ),
        horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd),
    ) {
        item(key = "all") {
            NeumorphicChip(
                text = "Todos",
                selected = selected == ProductFilter.All,
                onClick = { onFilterSelected(ProductFilter.All) },
            )
        }
        item(key = "low") {
            NeumorphicChip(
                text = if (lowStockCount > 0) "Stock bajo ($lowStockCount)" else "Stock bajo",
                selected = selected == ProductFilter.LowStock,
                onClick = { onFilterSelected(ProductFilter.LowStock) },
            )
        }
        items(categories, key = { "cat-${it.id}" }) { category ->
            NeumorphicChip(
                text = category.name,
                selected = selected == ProductFilter.ByCategory(category.id),
                onClick = { onFilterSelected(ProductFilter.ByCategory(category.id)) },
            )
        }
        item(key = "manage") {
            NeumorphicChip(
                text = "+ Categoría",
                selected = false,
                onClick = onManageCategories,
            )
        }
    }
}

@Composable
private fun ProductList(
    state: DashboardUiState,
    query: String,
    onProductClick: (Product) -> Unit,
    onAdjustStock: (Product, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = NeuDimens.ScreenPadding,
            end = NeuDimens.ScreenPadding,
            // LazyColumn también recorta: la sombra clara de la primera tarjeta
            // sube offset + blur (8 + 16 = 24dp), así que necesita este margen.
            top = NeuDimens.SpaceXl,
            // Deja libre el área del FAB para que no tape la última tarjeta.
            bottom = NeuDimens.FabSize + NeuDimens.ScreenPadding * 2,
        ),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
    ) {
        val message = when {
            state.error != null -> state.error
            state.isLoading -> "Cargando inventario…"
            state.totalProducts == 0 -> "Aún no hay productos.\nToca + para agregar el primero."
            state.products.isEmpty() && query.isNotBlank() -> "Sin resultados para \"${query.trim()}\""
            state.products.isEmpty() && state.filter == ProductFilter.LowStock -> "Ningún producto tiene stock bajo."
            state.products.isEmpty() -> "No hay productos en esta categoría."
            else -> null
        }
        if (message != null) {
            item(key = "message") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NeuDimens.SpaceXxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.error != null) NeuColors.OnLowStock else NeuColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (state.error != null) {
                        Spacer(Modifier.size(NeuDimens.SpaceXl))
                        NeumorphicButton(text = "Reintentar", onClick = onRetry)
                    }
                }
            }
        }
        items(state.products, key = { it.id }) { product ->
            ProductCard(
                product = product,
                onClick = { onProductClick(product) },
                onAdjustStock = { delta -> onAdjustStock(product, delta) },
            )
        }
    }
}

@Preview
@Composable
private fun DashboardScreenPreview() {
    MiTiendaTheme {
        DashboardScreen(
            state = DashboardUiState(
                products = previewProducts,
                totalProducts = previewProducts.size,
                isLoading = false,
            ),
            query = "",
            onQueryChange = {},
            onFilterSelected = {},
            onAdjustStock = { _, _ -> },
            onRetry = {},
            onManageCategories = {},
            onBluetoothClick = {},
            onScanClick = {},
            onAddProductClick = {},
            onSellClick = {},
            onProductClick = {},
        )
    }
}

private val previewProducts = listOf(
    Product(
        id = 1,
        barcode = "7501234567890",
        name = "Cuaderno profesional raya 100 hojas",
        priceCents = 4_550,
        costCents = 3_000,
        stock = 24,
        category = Category(1, "Cuadernos"),
        location = "Pasillo 1 · Estante A",
    ),
    Product(
        id = 2,
        barcode = "7509876543210",
        name = "Lápiz grafito HB No. 2 (caja 12 pzas)",
        priceCents = 8_900,
        costCents = 6_000,
        stock = 3,
        category = Category(2, "Lápices"),
        minStock = 4,
    ),
)
