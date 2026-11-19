package com.eter.mitienda.feature.categories

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicFab
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.neumorphism.neumorphicInset
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.domain.model.CategoryWithCount
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_arrow_back
import mitienda.shared.generated.resources.ic_category
import mitienda.shared.generated.resources.ic_delete
import mitienda.shared.generated.resources.ic_edit
import org.jetbrains.compose.resources.painterResource

/** Crear, renombrar y borrar las categorías del usuario. */
@Composable
fun CategoriesRoute(
    viewModel: CategoriesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeumorphicIconButton(
                    icon = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = "Volver",
                    onClick = onBack,
                )
                Spacer(Modifier.width(NeuDimens.SpaceLg))
                Column {
                    Text("Categorías", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
                    Text(
                        "Organiza tu inventario a tu manera",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeuColors.TextSecondary,
                    )
                }
            }

            val categories = state.categories
            LazyColumn(
                contentPadding = PaddingValues(
                    start = NeuDimens.ScreenPadding,
                    end = NeuDimens.ScreenPadding,
                    top = NeuDimens.SpaceXl,
                    bottom = NeuDimens.FabSize + NeuDimens.ScreenPadding * 2,
                ),
                verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg),
            ) {
                val message = when {
                    state.error != null -> state.error
                    categories == null -> "Cargando…"
                    categories.isEmpty() -> "Aún no tienes categorías.\nToca + para crear la primera."
                    else -> null
                }
                if (message != null) {
                    item {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeuColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = NeuDimens.SpaceXxl),
                        )
                    }
                }
                items(categories.orEmpty(), key = { it.category.id }) { item ->
                    CategoryCard(
                        item = item,
                        onRename = { viewModel.openDialog(CategoryDialog.Rename(item.category)) },
                        onDelete = { viewModel.openDialog(CategoryDialog.Delete(item)) },
                    )
                }
            }
        }

        NeumorphicFab(
            icon = painterResource(Res.drawable.ic_add),
            contentDescription = "Nueva categoría",
            onClick = { viewModel.openDialog(CategoryDialog.Create) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(NeuDimens.ScreenPadding),
        )
    }

    when (val dialog = viewModel.dialog) {
        CategoryDialog.Create -> CategoryNameDialog(
            title = "Nueva categoría",
            confirmLabel = "Crear",
            error = viewModel.dialogError,
            onConfirm = viewModel::create,
            onDismiss = viewModel::closeDialog,
        )
        is CategoryDialog.Rename -> CategoryNameDialog(
            title = "Renombrar categoría",
            confirmLabel = "Guardar",
            initialName = dialog.category.name,
            error = viewModel.dialogError,
            onConfirm = { viewModel.rename(dialog.category, it) },
            onDismiss = viewModel::closeDialog,
        )
        is CategoryDialog.Delete -> DeleteCategoryDialog(
            item = dialog.item,
            error = viewModel.dialogError,
            onConfirm = { viewModel.delete(dialog.item.category) },
            onDismiss = viewModel::closeDialog,
        )
        null -> Unit
    }
}

@Composable
private fun CategoryCard(item: CategoryWithCount, onRename: () -> Unit, onDelete: () -> Unit) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth(), onClick = onRename) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).neumorphicInset(NeuShapes.Circle, NeuDimens.ElevationSmall),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_category),
                    contentDescription = null,
                    tint = NeuColors.Magenta,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            Column(Modifier.weight(1f)) {
                Text(
                    item.category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (item.productCount) {
                        0 -> "Sin productos"
                        1 -> "1 producto"
                        else -> "${item.productCount} productos"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuColors.TextSecondary,
                )
            }
            NeumorphicIconButton(
                icon = painterResource(Res.drawable.ic_edit),
                contentDescription = "Renombrar ${item.category.name}",
                onClick = onRename,
                size = NeuDimens.IconButtonSmallSize,
                iconSize = NeuDimens.IconSmallSize,
                elevation = NeuDimens.ElevationSmall,
            )
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            NeumorphicIconButton(
                icon = painterResource(Res.drawable.ic_delete),
                contentDescription = "Eliminar ${item.category.name}",
                onClick = onDelete,
                size = NeuDimens.IconButtonSmallSize,
                iconSize = NeuDimens.IconSmallSize,
                elevation = NeuDimens.ElevationSmall,
                iconTint = NeuColors.OnLowStock,
            )
        }
    }
}

/** Avisa cuántos productos quedarán sin categoría: ninguno se borra. */
@Composable
private fun DeleteCategoryDialog(
    item: CategoryWithCount,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NeuDimens.SpaceLg)
                .neumorphic(NeuShapes.Large, NeuDimens.ElevationLarge)
                .padding(NeuDimens.SpaceXl),
        ) {
            Text(
                "¿Eliminar \"${item.category.name}\"?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary,
            )
            Spacer(Modifier.size(NeuDimens.SpaceMd))
            Text(
                when (item.productCount) {
                    0 -> "La categoría no tiene productos."
                    1 -> "El producto de esta categoría quedará como \"Sin categoría\". No se borra ningún producto."
                    else -> "Los ${item.productCount} productos de esta categoría quedarán como \"Sin categoría\". " +
                        "No se borra ningún producto."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextSecondary,
            )
            if (error != null) {
                Spacer(Modifier.size(NeuDimens.SpaceSm))
                Text(error, style = MaterialTheme.typography.bodySmall, color = NeuColors.OnLowStock)
            }
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton(
                    text = "Cancelar",
                    onClick = onDismiss,
                    containerColor = NeuColors.Background,
                    modifier = Modifier.weight(1f),
                )
                NeumorphicButton(
                    text = "Eliminar",
                    onClick = onConfirm,
                    containerColor = NeuColors.LowStockContainer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
