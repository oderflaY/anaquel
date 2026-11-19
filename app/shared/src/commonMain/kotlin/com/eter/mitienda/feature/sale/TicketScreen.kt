package com.eter.mitienda.feature.sale

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.share.rememberTextSharer
import com.eter.mitienda.core.util.formatDateTime
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.domain.model.Sale
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_arrow_back
import mitienda.shared.generated.resources.ic_cart
import mitienda.shared.generated.resources.ic_check_circle
import mitienda.shared.generated.resources.ic_share
import org.jetbrains.compose.resources.painterResource

/** Ticket digital de una venta, con opción de compartirlo. */
@Composable
fun TicketRoute(
    viewModel: TicketViewModel,
    onDone: () -> Unit,
    onNewSale: () -> Unit,
) {
    val share = rememberTextSharer()

    Column(
        Modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeumorphicIconButton(icon = painterResource(Res.drawable.ic_arrow_back), contentDescription = "Volver al inventario", onClick = onDone)
            Spacer(Modifier.width(NeuDimens.SpaceLg))
            Text("Ticket", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
        }

        when (val state = viewModel.state) {
            TicketUiState.Loading -> Unit
            is TicketUiState.Failed -> Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.OnLowStock,
                modifier = Modifier.padding(NeuDimens.ScreenPadding),
            )
            is TicketUiState.Loaded -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(NeuDimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(Res.drawable.ic_check_circle), contentDescription = null, tint = NeuColors.Blue, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(NeuDimens.SpaceSm))
                    Text(
                        "Venta registrada · inventario actualizado",
                        style = MaterialTheme.typography.titleMedium,
                        color = NeuColors.TextPrimary,
                    )
                }

                Receipt(state.sale)

                Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                    NeumorphicButton(
                        text = "Compartir",
                        icon = painterResource(Res.drawable.ic_share),
                        containerColor = NeuColors.Blue,
                        onClick = { share("Ticket #${state.sale.folio}", TicketFormatter.toText(state.sale)) },
                        modifier = Modifier.weight(1f),
                    )
                    NeumorphicButton(
                        text = "Nueva venta",
                        icon = painterResource(Res.drawable.ic_cart),
                        onClick = onNewSale,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** El ticket "impreso": tarjeta extruida con tipografía monoespaciada y separadores punteados. */
@Composable
private fun Receipt(sale: Sale) {
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = NeuColors.TextPrimary)

    NeumorphicCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(NeuDimens.SpaceXl)) {
        Text(
            STORE_NAME,
            style = mono.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Ticket #${sale.folio}", style = mono, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            formatDateTime(sale.timestamp),
            style = mono.copy(color = NeuColors.TextSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        DashedDivider()
        sale.lines.forEach { line ->
            Text("${line.quantity} × ${line.name}", style = mono)
            ReceiptRow("   ${formatPrice(line.unitPriceCents)} c/u", formatPrice(line.subtotalCents), mono.copy(color = NeuColors.TextSecondary))
            Spacer(Modifier.size(NeuDimens.SpaceXs))
        }
        DashedDivider()
        ReceiptRow("Artículos", sale.itemCount.toString(), mono)
        ReceiptRow("TOTAL", formatPrice(sale.totalCents), mono.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
        sale.receivedCents?.let { ReceiptRow("Recibido", formatPrice(it), mono) }
        sale.changeCents?.let { ReceiptRow("Cambio", formatPrice(it), mono.copy(fontWeight = FontWeight.Bold, color = NeuColors.Blue)) }
        DashedDivider()
        Text(
            "¡Gracias por su compra!",
            style = mono.copy(color = NeuColors.Magenta, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReceiptRow(left: String, right: String, style: TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        Text(left, style = style, modifier = Modifier.weight(1f))
        Text(right, style = style)
    }
}

@Composable
private fun DashedDivider() {
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = NeuDimens.SpaceMd)
            .height(1.dp),
    ) {
        drawLine(
            color = NeuColors.TextSecondary.copy(alpha = 0.5f),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
        )
    }
}
