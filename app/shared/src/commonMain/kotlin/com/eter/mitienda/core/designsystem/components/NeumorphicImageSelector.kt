package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.image.LocalImageState
import com.eter.mitienda.core.image.rememberLocalImage
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add_photo
import mitienda.shared.generated.resources.ic_broken_image
import org.jetbrains.compose.resources.painterResource

/**
 * Contenedor extruido y cuadrado que muestra la foto del producto y actúa como
 * botón para cambiarla. Estados: sin foto, procesando, foto, foto perdida.
 *
 * @param imagePath ruta a mostrar (temporal recién elegida o la guardada en SQLite).
 * @param isProcessing `true` mientras se comprime la imagen elegida.
 */
@Composable
fun NeumorphicImageSelector(
    imagePath: String?,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
) {
    val image = rememberLocalImage(imagePath, maxSizePx = 480)

    NeumorphicCard(
        modifier = modifier.size(size),
        onClick = if (isProcessing) null else onClick,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                isProcessing || image is LocalImageState.Loading -> CircularProgressIndicator(
                    color = NeuColors.Magenta,
                    trackColor = NeuColors.ShadowDark,
                    modifier = Modifier.size(36.dp),
                )
                image is LocalImageState.Loaded -> Image(
                    bitmap = image.bitmap,
                    contentDescription = "Foto del producto. Toca para cambiarla",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(NeuDimens.SpaceSm)
                        .clip(NeuShapes.Small),
                )
                image is LocalImageState.Missing -> SelectorHint(
                    icon = painterResource(Res.drawable.ic_broken_image),
                    text = "Imagen no encontrada\nToca para elegir otra",
                )
                else -> SelectorHint(
                    icon = painterResource(Res.drawable.ic_add_photo),
                    text = "Agregar foto",
                )
            }
        }
    }
}

@Composable
private fun SelectorHint(icon: Painter, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = NeuColors.TextSecondary,
        )
        Spacer(Modifier.size(NeuDimens.SpaceSm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = NeuColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
