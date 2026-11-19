package com.eter.mitienda.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicChip
import com.eter.mitienda.core.designsystem.components.NeumorphicDropdown
import com.eter.mitienda.core.designsystem.components.NeumorphicField
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.neumorphism.neumorphicInset
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.domain.model.OrderStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_arrow_back
import mitienda.shared.generated.resources.ic_clock
import mitienda.shared.generated.resources.ic_delete
import mitienda.shared.generated.resources.ic_orders
import org.jetbrains.compose.resources.painterResource

@Composable
fun OrderFormRoute(
    viewModel: OrderFormViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.state
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.isFinished) { if (state.isFinished) onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceLg, bottom = NeuDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeumorphicIconButton(icon = painterResource(Res.drawable.ic_arrow_back), contentDescription = "Volver", onClick = onBack)
            Spacer(Modifier.width(NeuDimens.SpaceLg))
            Text(
                if (state.isEditing) "Editar pedido" else "Nuevo pedido",
                style = MaterialTheme.typography.headlineMedium,
                color = NeuColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (state.isEditing) {
                NeumorphicIconButton(
                    icon = painterResource(Res.drawable.ic_delete),
                    contentDescription = "Eliminar pedido",
                    onClick = { showDelete = true },
                    iconTint = NeuColors.OnLowStock,
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(NeuDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
        ) {
            NeumorphicField("Cliente", error = state.customerError) {
                NeumorphicInput(
                    value = state.customer,
                    onValueChange = viewModel::onCustomerChange,
                    placeholder = "Nombre de quien encarga",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
            }
            NeumorphicField("Teléfono (opcional)") {
                NeumorphicInput(
                    value = state.phone,
                    onValueChange = viewModel::onPhoneChange,
                    placeholder = "618 123 4567",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                )
            }
            NeumorphicField("¿Qué se va a hacer?", error = state.kindError) {
                NeumorphicInput(
                    value = state.kind,
                    onValueChange = viewModel::onKindChange,
                    placeholder = "Piñata, manualidad…",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
                LazyRow(
                    contentPadding = PaddingValues(vertical = NeuDimens.SpaceMd),
                    horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm),
                ) {
                    items(state.kindSuggestions) { kind ->
                        NeumorphicChip(
                            text = kind,
                            selected = kind.equals(state.kind.trim(), ignoreCase = true),
                            onClick = { viewModel.onKindChange(kind) },
                        )
                    }
                }
            }
            NeumorphicField("Descripción", error = state.descriptionError) {
                NeumorphicInput(
                    value = state.description,
                    onValueChange = viewModel::onDescriptionChange,
                    placeholder = "Ej. Spiderman, 7 picos, con dulces",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicField("Fecha de entrega", error = state.dateError, modifier = Modifier.weight(1.3f)) {
                    PickerField(
                        icon = painterResource(Res.drawable.ic_orders),
                        text = state.deliveryDate?.let { "${OrderDates.weekday(it)} ${OrderDates.dateLabel(it)}" } ?: "Elegir",
                        placeholder = state.deliveryDate == null,
                        onClick = { showDate = true },
                    )
                }
                NeumorphicField("Hora", modifier = Modifier.weight(1f)) {
                    PickerField(
                        icon = painterResource(Res.drawable.ic_clock),
                        text = OrderDates.timeLabel(state.deliveryHour, state.deliveryMinute),
                        placeholder = false,
                        onClick = { showTime = true },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicField("Precio", error = state.priceError, modifier = Modifier.weight(1f)) {
                    MoneyField(state.price, viewModel::onPriceChange)
                }
                NeumorphicField("Anticipo", error = state.depositError, modifier = Modifier.weight(1f)) {
                    MoneyField(state.deposit, viewModel::onDepositChange)
                }
            }
            state.balanceText?.let {
                Text("Resta por cobrar al entregar: $it", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = NeuColors.OnChip)
            }

            NeumorphicField("Estado") {
                NeumorphicDropdown(
                    selected = state.status,
                    options = OrderStatus.entries,
                    optionLabel = { it.label },
                    onSelect = viewModel::onStatusChange,
                )
            }
            NeumorphicField("Notas (opcional)") {
                NeumorphicInput(
                    value = state.notes,
                    onValueChange = viewModel::onNotesChange,
                    placeholder = "Colores, materiales, quién recoge…",
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }

            state.errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeuColors.OnLowStock,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(NeuColors.LowStockContainer, NeuShapes.Small).padding(NeuDimens.SpaceMd),
                )
            }

            NeumorphicButton(
                text = if (state.isSaving) "Guardando…" else if (state.isEditing) "Guardar cambios" else "Guardar pedido",
                onClick = viewModel::save,
                enabled = !state.isSaving,
                elevation = NeuDimens.ElevationLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Te avisaremos un día antes a las 9:00 y el día de la entrega.",
                style = MaterialTheme.typography.bodySmall,
                color = NeuColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDate) {
        DeliveryDateDialog(
            initial = state.deliveryDate,
            onPicked = {
                viewModel.onDateChange(it)
                showDate = false
            },
            onDismiss = { showDate = false },
        )
    }
    if (showTime) {
        DeliveryTimeDialog(
            hour = state.deliveryHour,
            minute = state.deliveryMinute,
            onPicked = { h, m ->
                viewModel.onTimeChange(h, m)
                showTime = false
            },
            onDismiss = { showTime = false },
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = "¿Eliminar este pedido?",
            message = "Se borra el pedido y sus recordatorios. Esto no se puede deshacer.",
            confirmLabel = "Eliminar",
            onConfirm = {
                showDelete = false
                viewModel.delete()
            },
            onDismiss = { showDelete = false },
        )
    }
}

/** Campo hundido (como un input) que abre un selector al tocarlo. */
@Composable
private fun PickerField(icon: Painter, text: String, placeholder: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .neumorphicInset(NeuShapes.Large)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = NeuDimens.InputHeight)
            .padding(horizontal = NeuDimens.SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = NeuColors.TextSecondary, modifier = Modifier.size(NeuDimens.IconSmallSize))
        Spacer(Modifier.width(NeuDimens.SpaceSm))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (placeholder) NeuColors.TextSecondary else NeuColors.TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit) {
    NeumorphicInput(
        value = value,
        onValueChange = onChange,
        placeholder = "0.00",
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        leadingIcon = { Text("$", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = NeuColors.TextSecondary) },
    )
}

/**
 * El DatePicker de Material trabaja en milisegundos UTC a medianoche; aquí se
 * convierte a/desde LocalDate para no desfasar un día por la zona horaria.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryDateDialog(initial: LocalDate?, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayUtc = OrderDates.today().atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: OrderDates.today()).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    val colors = DatePickerDefaults.colors(
        containerColor = NeuColors.Background,
        selectedDayContainerColor = NeuColors.Magenta,
        selectedDayContentColor = NeuColors.OnAccent,
        todayDateBorderColor = NeuColors.Blue,
        todayContentColor = NeuColors.Blue,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onPicked(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                }
            }) { Text("Aceptar", color = NeuColors.Magenta, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = NeuColors.TextPrimary) } },
    ) {
        DatePicker(state = state, colors = colors, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryTimeDialog(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(NeuDimens.SpaceLg)
                .neumorphic(NeuShapes.Large, NeuDimens.ElevationLarge)
                .padding(NeuDimens.SpaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Hora de entrega", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
            Spacer(Modifier.size(NeuDimens.SpaceLg))
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = NeuColors.ChipBackground,
                    selectorColor = NeuColors.Magenta,
                    timeSelectorSelectedContainerColor = NeuColors.Magenta,
                    timeSelectorSelectedContentColor = NeuColors.OnAccent,
                    timeSelectorUnselectedContainerColor = NeuColors.ChipBackground,
                    timeSelectorUnselectedContentColor = NeuColors.OnChip,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton(text = "Cancelar", onClick = onDismiss, containerColor = NeuColors.Background, modifier = Modifier.weight(1f))
                NeumorphicButton(text = "Aceptar", onClick = { onPicked(state.hour, state.minute) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(NeuDimens.SpaceLg)
                .neumorphic(NeuShapes.Large, NeuDimens.ElevationLarge)
                .padding(NeuDimens.SpaceXl),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
            Spacer(Modifier.size(NeuDimens.SpaceMd))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextSecondary)
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton(text = "Cancelar", onClick = onDismiss, containerColor = NeuColors.Background, modifier = Modifier.weight(1f))
                NeumorphicButton(text = confirmLabel, onClick = onConfirm, containerColor = NeuColors.LowStockContainer, modifier = Modifier.weight(1f))
            }
        }
    }
}
