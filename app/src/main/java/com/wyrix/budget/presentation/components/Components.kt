package com.wyrix.budget.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.domain.model.TransactionListItem
import com.wyrix.budget.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object DateFormats {
    val date: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val time: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val dateTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
}

private const val HIDDEN_AMOUNT = "••••••"

/** Shows a formatted amount, or a mask when the privacy toggle is on. */
@Composable
fun MoneyText(
    minor: Long,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    prefix: String = "",
    fontWeight: FontWeight? = null,
    currency: com.wyrix.budget.core.money.CurrencyInfo = com.wyrix.budget.core.money.CurrencyInfo.INR,
) {
    Text(
        text = if (hidden) prefix + HIDDEN_AMOUNT else prefix + MoneyFormatter.format(minor, currency),
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier, size: Int = 12) {
    Box(modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
fun FullScreenMessage(title: String, message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(title, message) }
}

@Composable
fun TransactionRow(
    item: TransactionListItem,
    hideAmounts: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = item.transaction
    val scheme = MaterialTheme.colorScheme
    val dateText = remember(t.occurredAt) {
        Instant.ofEpochMilli(t.occurredAt).atZone(ZoneId.systemDefault()).format(DateFormats.dateTime)
    }
    val dotColor = when (t.type) {
        TransactionType.INCOME, TransactionType.EXPENSE ->
            item.categoryColorArgb?.let { Color(it) } ?: scheme.outline
        TransactionType.TRANSFER -> scheme.secondary
        TransactionType.ADJUSTMENT -> scheme.tertiary
    }
    val subtitle = when (t.type) {
        TransactionType.INCOME, TransactionType.EXPENSE ->
            listOfNotNull(item.categoryName, item.accountName).joinToString(" • ")
        TransactionType.TRANSFER -> "${item.accountName} → ${item.transferAccountName ?: "?"}"
        TransactionType.ADJUSTMENT -> "Reconciliation • ${item.accountName}"
    }
    val (prefix, amountColor) = when (t.type) {
        TransactionType.INCOME -> "+" to scheme.primary
        TransactionType.EXPENSE -> "-" to scheme.onSurface
        TransactionType.TRANSFER -> "" to scheme.secondary
        TransactionType.ADJUSTMENT -> (if (t.amountMinor >= 0) "+" else "") to scheme.tertiary
    }
    val displayMinor = t.amountMinor

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ColorDot(dotColor, size = 14)
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(dateText, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        MoneyText(
            minor = displayMinor,
            hidden = hideAmounts,
            prefix = prefix,
            color = amountColor,
            fontWeight = FontWeight.SemiBold,
            currency = com.wyrix.budget.core.money.CurrencyInfo.byCode(t.currencyCode),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(date: LocalDate, onDateChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(date.format(DateFormats.date), modifier = Modifier.padding(start = 8.dp))
    }
    if (show) {
        // The Material date picker works in UTC midnight milliseconds.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(time: LocalTime, onTimeChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = modifier) {
        Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(time.format(DateFormats.time), modifier = Modifier.padding(start = 8.dp))
    }
    if (show) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}
