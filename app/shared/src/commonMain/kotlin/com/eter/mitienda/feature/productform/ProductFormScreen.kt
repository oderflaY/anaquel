package com.eter.mitienda.feature.productform

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicChip
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicDropdown
import com.eter.mitienda.core.designsystem.components.NeumorphicField
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.components.NeumorphicImageSelector
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.theme.MiTiendaTheme
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.image.rememberImagePickerLauncher
import com.eter.mitienda.core.scanner.NeumorphicScannerModal
import com.eter.mitienda.feature.categories.CategoryNameDialog
import com.eter.mitienda.domain.model.Category
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_arrow_back
import mitienda.shared.generated.resources.ic_barcode
import mitienda.shared.generated.resources.ic_camera
import mitienda.shared.generated.resources.ic_delete
import mitienda.shared.generated.resources.ic_image
import mitienda.shared.generated.resources.ic_location
import org.jetbrains.compose.resources.painterResource

/**
 * Punto de entrada con estado: conecta [ProductFormViewModel] con la UI y monta
 * el escáner y el menú de foto por encima de la pantalla.
 */
@Composable
fun ProductFormRoute(
    viewModel: ProductFormViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var showPhotoSheet by rememberSaveable { mutableStateOf(false) }
    var showNewCategory by rememberSaveable { mutableStateOf(false) }
    val imagePicker = rememberImagePickerLauncher(onResult = viewModel::onImagePicked)

    // Al guardar o eliminar con éxito se cierra la pantalla. El Dashboard ya se
    // habrá actualizado solo gracias al flujo reactivo del repositorio.
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onBack()
    }

    Box(Modifier.fillMaxSize()) {
        ProductFormScreen(
            state = state,
            onBack = onBack,
            onBarcodeChange = viewModel::onBarcodeChange,
            onNameChange = viewModel::onNameChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onPriceChange = viewModel::onPriceChange,
            onCostChange = viewModel::onCostChange,
            onStockChange = viewModel::onStockChange,
            onMinStockChange = viewModel::onMinStockChange,
            onCategoryChange = viewModel::onCategoryChange,
            onNewCategoryClick = { showNewCategory = true },
            onLocationChange = viewModel::onLocationChange,
            onScanClick = { showScanner = true },
            isProcessingImage = imagePicker.isProcessing,
            onImageClick = { showPhotoSheet = true },
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        )

        if (showNewCategory) {
            val close = {
                showNewCategory = false
                viewModel.onNewCategoryDialogClosed()
            }
            CategoryNameDialog(
                title = "Nueva categoría",
                confirmLabel = "Crear",
                error = state.newCategoryError,
                onConfirm = { name -> viewModel.onCreateCategory(name, onCreated = close) },
                onDismiss = close,
            )
        }

        if (showPhotoSheet) {
            PhotoSourceSheet(
                hasImage = state.displayImagePath != null,
                onTakePhoto = imagePicker::takePhoto,
                onPickFromGallery = imagePicker::pickFromGallery,
                onRemove = viewModel::onRemoveImage,
                onDismiss = { showPhotoSheet = false },
            )
        }

        if (showScanner) {
            NeumorphicScannerModal(
                // El código escaneado entra por el mismo camino que el teclado.
                onBarcodeScanned = viewModel::onBarcodeChange,
                onDismiss = { showScanner = false },
            )
        }
    }
}

@Composable
fun ProductFormScreen(
    state: ProductFormUiState,
    onBack: () -> Unit,
    onBarcodeChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onCostChange: (String) -> Unit,
    onStockChange: (String) -> Unit,
    onMinStockChange: (String) -> Unit,
    onCategoryChange: (Category?) -> Unit,
    onNewCategoryClick: () -> Unit,
    onLocationChange: (String) -> Unit,
    onScanClick: () -> Unit,
    isProcessingImage: Boolean,
    onImageClick: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            // safeDrawing incluye el teclado: el contenido se recorta sobre él y
            // el scroll permite llegar al campo enfocado.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        FormHeader(
            title = if (state.isEditing) "Editar producto" else "Nuevo producto",
            showDelete = state.isEditing,
            onBack = onBack,
            onDeleteClick = { showDeleteDialog = true },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // El padding va DENTRO del scroll: deja aire a las sombras, que
                // si no se recortarían contra los bordes del contenedor.
                .padding(
                    start = NeuDimens.ScreenPadding,
                    end = NeuDimens.ScreenPadding,
                    top = NeuDimens.SpaceLg,
                    bottom = NeuDimens.ScreenPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
        ) {
            NeumorphicImageSelector(
                imagePath = state.displayImagePath,
                isProcessing = isProcessingImage,
                onClick = onImageClick,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            NeumorphicField(label = "Código de barras", error = state.barcodeError) {
                NeumorphicInput(
                    value = state.barcode,
                    onValueChange = onBarcodeChange,
                    placeholder = "Escribe o escanea",
                    modifier = Modifier.fillMaxWidth(),
                    // Teclado alfanumérico: Code 128 admite letras, así que el
                    // teclado numérico impediría capturar esos códigos a mano.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
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
            }

            NeumorphicField(label = "Nombre del producto", error = state.nameError) {
                NeumorphicInput(
                    value = state.name,
                    onValueChange = onNameChange,
                    placeholder = "Ej. Cuaderno profesional raya",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            NeumorphicField(label = "Descripción (opcional)") {
                NeumorphicInput(
                    value = state.description,
                    onValueChange = onDescriptionChange,
                    placeholder = "Marca, tamaño, color…",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicField(
                    label = "Precio de venta",
                    error = state.priceError,
                    modifier = Modifier.weight(1f),
                ) {
                    MoneyInput(value = state.price, onValueChange = onPriceChange, placeholder = "0.00")
                }
                NeumorphicField(
                    label = "Costo",
                    error = state.costError,
                    modifier = Modifier.weight(1f),
                ) {
                    MoneyInput(value = state.cost, onValueChange = onCostChange, placeholder = "0.00")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicField(
                    label = "Stock actual",
                    error = state.stockError,
                    modifier = Modifier.weight(1f),
                ) {
                    NeumorphicInput(
                        value = state.stock,
                        onValueChange = onStockChange,
                        placeholder = "0",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }
                // Por debajo o igual a este número, el producto sale en "Stock bajo".
                NeumorphicField(
                    label = "Stock mínimo",
                    error = state.minStockError,
                    modifier = Modifier.weight(1f),
                ) {
                    NeumorphicInput(
                        value = state.minStock,
                        onValueChange = onMinStockChange,
                        placeholder = "5",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }
            }

            NeumorphicField(label = "Categoría") {
                NeumorphicDropdown(
                    selected = state.category,
                    options = listOf(null) + state.categories + NEW_CATEGORY_OPTION,
                    optionLabel = { it?.name ?: "Sin categoría" },
                    onSelect = { option ->
                        if (option == NEW_CATEGORY_OPTION) onNewCategoryClick() else onCategoryChange(option)
                    },
                )
            }

            NeumorphicField(label = "Ubicación en la tienda (opcional)") {
                NeumorphicInput(
                    value = state.location,
                    onValueChange = onLocationChange,
                    placeholder = "Ej. Pasillo 2 · Estante B",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(Res.drawable.ic_location),
                            contentDescription = null,
                            modifier = Modifier.size(NeuDimens.IconSize),
                            tint = NeuColors.TextSecondary,
                        )
                    },
                )
                // Ubicaciones ya usadas: un toque y queda escrita igual que en otros
                // productos (así "Pasillo 2" no termina escrito de tres formas).
                val suggestions = state.locationSuggestions
                if (suggestions.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(vertical = NeuDimens.SpaceMd),
                        horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm),
                    ) {
                        items(suggestions) { suggestion ->
                            NeumorphicChip(
                                text = suggestion,
                                selected = false,
                                onClick = { onLocationChange(suggestion) },
                            )
                        }
                    }
                }
            }

            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeuColors.OnLowStock,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NeuColors.LowStockContainer, NeuShapes.Small)
                        .padding(NeuDimens.SpaceMd),
                )
            }

            NeumorphicButton(
                text = when {
                    state.isSaving -> "Guardando…"
                    state.isEditing -> "Guardar cambios"
                    else -> "Guardar producto"
                },
                onClick = onSave,
                enabled = !state.isSaving && !state.isLoadingProduct && !isProcessingImage,
                elevation = NeuDimens.ElevationLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NeuDimens.SpaceSm),
            )
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            productName = state.name,
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun FormHeader(
    title: String,
    showDelete: Boolean,
    onBack: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = NeuDimens.ScreenPadding,
                end = NeuDimens.ScreenPadding,
                top = NeuDimens.SpaceLg,
                bottom = NeuDimens.SpaceSm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeumorphicIconButton(
            icon = painterResource(Res.drawable.ic_arrow_back),
            contentDescription = "Volver",
            onClick = onBack,
        )
        Spacer(Modifier.width(NeuDimens.SpaceLg))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NeuColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (showDelete) {
            NeumorphicIconButton(
                icon = painterResource(Res.drawable.ic_delete),
                contentDescription = "Eliminar producto",
                onClick = onDeleteClick,
                iconTint = NeuColors.OnLowStock,
            )
        }
    }
}

/** Input de importe con prefijo "$" y teclado decimal. */
@Composable
private fun MoneyInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    NeumorphicInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
        leadingIcon = {
            Text(
                text = "$",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = NeuColors.TextSecondary,
            )
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    productName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Background,
        shape = NeuShapes.Large,
        title = { Text("¿Eliminar producto?", color = NeuColors.TextPrimary) },
        text = {
            Text(
                text = "Se eliminará \"$productName\" del inventario. Esta acción no se puede deshacer.",
                color = NeuColors.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Eliminar", color = NeuColors.OnLowStock, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = NeuColors.TextPrimary)
            }
        },
    )
}

@Preview
@Composable
private fun ProductFormScreenPreview() {
    MiTiendaTheme {
        ProductFormScreen(
            state = ProductFormUiState(nameError = "El nombre es obligatorio"),
            onBack = {},
            onBarcodeChange = {},
            onNameChange = {},
            onDescriptionChange = {},
            onPriceChange = {},
            onCostChange = {},
            onStockChange = {},
            onMinStockChange = {},
            onCategoryChange = {},
            onNewCategoryClick = {},
            onLocationChange = {},
            onScanClick = {},
            isProcessingImage = false,
            onImageClick = {},
            onSave = {},
            onDelete = {},
        )
    }
}

/**
 * Menú "de dónde sacar la foto" como bottom sheet. Cada opción cierra el menú
 * y luego abre la cámara o la galería del sistema.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSourceSheet(
    hasImage: Boolean,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Background,
        shape = RoundedCornerShape(topStart = NeuDimens.SpaceXl, topEnd = NeuDimens.SpaceXl),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = NeuDimens.ScreenPadding,
                    end = NeuDimens.ScreenPadding,
                    bottom = NeuDimens.ScreenPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg),
        ) {
            Text(
                text = "Foto del producto",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary,
            )
            NeumorphicButton(
                text = "Tomar foto",
                icon = painterResource(Res.drawable.ic_camera),
                onClick = {
                    onDismiss()
                    onTakePhoto()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            NeumorphicButton(
                text = "Elegir de galería",
                icon = painterResource(Res.drawable.ic_image),
                containerColor = NeuColors.Blue,
                onClick = {
                    onDismiss()
                    onPickFromGallery()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasImage) {
                NeumorphicButton(
                    text = "Quitar foto",
                    icon = painterResource(Res.drawable.ic_delete),
                    containerColor = NeuColors.LowStockContainer,
                    onClick = {
                        onDismiss()
                        onRemove()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Opción especial del selector de categoría que abre el diálogo de creación. */
private val NEW_CATEGORY_OPTION = Category(id = -1, name = "+ Nueva categoría…")
