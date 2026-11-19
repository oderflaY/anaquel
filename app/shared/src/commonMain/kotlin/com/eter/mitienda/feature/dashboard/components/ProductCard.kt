package com.eter.mitienda.feature.dashboard.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.neumorphism.neumorphicInset
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.image.LocalImageState
import com.eter.mitienda.core.image.rememberLocalImage
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.domain.model.Product
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_barcode
import mitienda.shared.generated.resources.ic_broken_image
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_image
import mitienda.shared.generated.resources.ic_location
import mitienda.shared.generated.resources.ic_remove
import org.jetbrains.compose.resources.painterResource

/**
 * Tarjeta de producto: imagen (hueco inset) + nombre, código, precio y
 * categoría; abajo, la ubicación y los botones − / + para ajustar el stock sin
 * abrir el formulario.
 */
@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    onAdjustStock: (delta: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NeumorphicCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductThumbnail(product.imagePath)
            Spacer(Modifier.width(NeuDimens.SpaceLg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(NeuDimens.SpaceXs))
                BarcodeLabel(product.barcode)
                Spacer(Modifier.size(NeuDimens.SpaceSm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatPrice(product.priceCents),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary,
                    )
                    product.category?.let {
                        Text(
                            text = it.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = NeuColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = NeuDimens.SpaceSm),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(NeuDimens.SpaceMd))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocationLabel(product.location, Modifier.weight(1f))
            Spacer(Modifier.width(NeuDimens.SpaceSm))
            StockStepper(product, onAdjustStock)
        }
    }
}

/**
 * Hueco hundido con la foto del producto, recortada con el mismo radio que el
 * hueco. Sin foto → ícono de imagen; foto borrada o dañada → ícono de "imagen
 * rota". Nunca lanza: los fallos de lectura llegan como LocalImageState.Missing.
 */
@Composable
private fun ProductThumbnail(imagePath: String?) {
    val image = rememberLocalImage(imagePath, maxSizePx = 256)
    Box(
        modifier = Modifier
            .size(76.dp)
            .neumorphicInset(NeuShapes.Small, NeuDimens.ElevationSmall),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            is LocalImageState.Loaded -> Image(
                bitmap = image.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .padding(NeuDimens.SpaceXs)
                    .clip(NeuShapes.Small),
            )
            LocalImageState.Missing -> Icon(
                painter = painterResource(Res.drawable.ic_broken_image),
                contentDescription = "Imagen no encontrada",
                modifier = Modifier.size(28.dp),
                tint = NeuColors.OnLowStock.copy(alpha = 0.6f),
            )
            LocalImageState.Empty, LocalImageState.Loading -> Icon(
                painter = painterResource(Res.drawable.ic_image),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = NeuColors.TextSecondary.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun LocationLabel(location: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(Res.drawable.ic_location),
            contentDescription = "Ubicación",
            modifier = Modifier.size(16.dp),
            tint = if (location != null) NeuColors.Blue else NeuColors.TextSecondary.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(NeuDimens.SpaceXs))
        Text(
            text = location ?: "Sin ubicación",
            style = MaterialTheme.typography.bodySmall,
            color = if (location != null) NeuColors.TextPrimary else NeuColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** [−] Stock: N [+]. El "−" se desactiva visualmente en 0 (la BD tampoco baja de 0). */
@Composable
private fun StockStepper(product: Product, onAdjustStock: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeumorphicIconButton(
            icon = painterResource(Res.drawable.ic_remove),
            contentDescription = "Restar uno al stock de ${product.name}",
            onClick = { if (product.stock > 0) onAdjustStock(-1) },
            size = 32.dp,
            iconSize = 16.dp,
            elevation = NeuDimens.ElevationSmall,
            iconTint = if (product.stock > 0) NeuColors.TextPrimary else NeuColors.TextSecondary.copy(alpha = 0.4f),
        )
        Spacer(Modifier.width(NeuDimens.SpaceSm))
        StockBadge(stock = product.stock, isLow = product.isLowStock)
        Spacer(Modifier.width(NeuDimens.SpaceSm))
        NeumorphicIconButton(
            icon = painterResource(Res.drawable.ic_add),
            contentDescription = "Sumar uno al stock de ${product.name}",
            onClick = { onAdjustStock(1) },
            size = 32.dp,
            iconSize = 16.dp,
            elevation = NeuDimens.ElevationSmall,
        )
    }
}

@Composable
private fun BarcodeLabel(barcode: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(Res.drawable.ic_barcode),
            contentDescription = "Código de barras",
            modifier = Modifier.size(14.dp),
            tint = NeuColors.TextSecondary,
        )
        Spacer(Modifier.width(NeuDimens.SpaceXs + 2.dp))
        Text(
            text = barcode,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = NeuColors.TextSecondary,
        )
    }
}

/**
 * Badge de existencias. Con stock bajo cambia de color Y de texto, para no
 * depender solo del color (accesibilidad).
 */
@Composable
private fun StockBadge(stock: Int, isLow: Boolean) {
    val container = if (isLow) NeuColors.LowStockContainer else NeuColors.ChipBackground
    val content = NeuColors.contentColorFor(container)
    Text(
        text = if (isLow) "Bajo: $stock" else "Stock: $stock",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = content,
        modifier = Modifier
            .background(container, NeuShapes.Pill)
            .padding(horizontal = NeuDimens.SpaceMd, vertical = NeuDimens.SpaceXs),
    )
}
