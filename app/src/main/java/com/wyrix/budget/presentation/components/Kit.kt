package com.wyrix.budget.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.domain.calc.AlertLevel
import com.wyrix.budget.domain.model.ActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Base currency and privacy mask, provided once at the app root so every screen formats consistently. */
val LocalBaseCurrency = compositionLocalOf { CurrencyInfo.INR }
val LocalHideAmounts = compositionLocalOf { false }

@Composable
fun Money(
    minor: Long,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    currency: CurrencyInfo = LocalBaseCurrency.current,
    prefix: String = "",
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) = MoneyText(minor, LocalHideAmounts.current, modifier, style, color, prefix, fontWeight, currency)

/** ViewModel base: one-shot messages (snackbar), a "done" signal, and a safe launcher that never loses errors silently. */
open class UiViewModel : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()
    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done: Flow<Unit> = _done.receiveAsFlow()

    protected suspend fun say(message: String) { _messages.send(message) }
    protected suspend fun finish() { _done.send(Unit) }

    protected fun launchSafely(errorMessage: String = "Something went wrong. Nothing was changed.", block: suspend () -> Unit) =
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { _messages.send(errorMessage) }
        }

    /** Shows the first rejection message, or runs [onSuccess]. */
    protected suspend fun <T> handle(result: ActionResult<T>, onSuccess: suspend (T) -> Unit) {
        when (result) {
            is ActionResult.Success -> onSuccess(result.value)
            is ActionResult.Rejected -> say(result.messages.first())
        }
    }
}

@Composable
fun CollectMessages(messages: Flow<String>, host: SnackbarHostState) {
    LaunchedEffect(messages) { messages.collect { host.showSnackbar(it) } }
}

@Composable
fun CollectDone(done: Flow<Unit>, onDone: () -> Unit) {
    LaunchedEffect(done) { done.collect { onDone() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

@Composable
fun FormColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun AppTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier, enabled = enabled,
        label = { Text(label) },
        prefix = prefix?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/** Decimal input restricted to the currency's digits. [allowNegative] only for balances. */
@Composable
fun MoneyField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    currency: CurrencyInfo = LocalBaseCurrency.current,
    allowNegative: Boolean = false,
    supporting: String? = null,
) {
    val digits = currency.minorDigits
    val regex = remember(digits, allowNegative) {
        Regex(if (digits == 0) "^${if (allowNegative) "-?" else ""}\\d{0,10}$" else "^${if (allowNegative) "-?" else ""}\\d{0,10}(\\.\\d{0,$digits})?$")
    }
    AppTextField(
        value = value, onChange = { raw -> val n = raw.replace(',', '.'); if (regex.matches(n)) onChange(n) },
        label = label, modifier = modifier, keyboardType = KeyboardType.Decimal, prefix = currency.symbol.trim(), supporting = supporting,
    )
}

@Composable
fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), decimal: Boolean = false, suffix: String? = null, supporting: String? = null) {
    val regex = remember(decimal) { Regex(if (decimal) "^\\d{0,3}(\\.\\d{0,2})?$" else "^\\d{0,6}$") }
    OutlinedTextField(
        value = value, onValueChange = { raw -> val n = raw.replace(',', '.'); if (regex.matches(n)) onChange(n) },
        modifier = modifier, label = { Text(label) }, suffix = suffix?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipSelect(label: String?, options: List<T>, selected: T?, onSelect: (T) -> Unit, text: (T) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) SectionLabel(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(text(o)) }) }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun MoneyStatRow(label: String, minor: Long, currency: CurrencyInfo = LocalBaseCurrency.current, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Money(minor, currency = currency, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ErrorList(errors: List<String>) {
    errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun levelColor(level: AlertLevel): Color = when (level) {
    AlertLevel.OK -> MaterialTheme.colorScheme.primary
    AlertLevel.NOTICE -> MaterialTheme.colorScheme.secondary
    AlertLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    AlertLevel.EXCEEDED -> MaterialTheme.colorScheme.error
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, description: String? = null) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier.fillMaxWidth().height(10.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        drawRoundRect(track, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun LoadingBox(padding: PaddingValues = PaddingValues()) {
    Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

// ---- charts (Canvas) -------------------------------------------------------------------------

data class ChartSlice(val label: String, val value: Long, val color: Color)

/** Donut chart with a text description for accessibility. Slices below 0.5% are merged by the caller. */
@Composable
fun DonutChart(slices: List<ChartSlice>, modifier: Modifier = Modifier, description: String) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    Canvas(modifier.size(160.dp).semantics { contentDescription = description }) {
        val stroke = size.minDimension * 0.22f
        var start = -90f
        slices.forEach { s ->
            val sweep = 360f * s.value / total
            drawArc(s.color, start, (sweep - 1f).coerceAtLeast(0.5f), false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Butt))
            start += sweep
        }
    }
}

/** Side-by-side bars per label (e.g. income vs expenses per month). */
@Composable
fun GroupedBarChart(labels: List<String>, first: List<Long>, second: List<Long>, firstColor: Color, secondColor: Color, description: String, modifier: Modifier = Modifier) {
    val max = (first + second).maxOrNull()?.coerceAtLeast(1) ?: 1
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val groupW = size.width / labels.size.coerceAtLeast(1)
            val barW = groupW * 0.32f
            labels.indices.forEach { i ->
                val x = i * groupW + groupW * 0.15f
                val h1 = size.height * first[i] / max
                val h2 = size.height * second[i] / max
                drawRoundRect(firstColor, Offset(x, size.height - h1), Size(barW, h1.coerceAtLeast(1f)), androidx.compose.ui.geometry.CornerRadius(4f))
                drawRoundRect(secondColor, Offset(x + barW + 4f, size.height - h2), Size(barW, h2.coerceAtLeast(1f)), androidx.compose.ui.geometry.CornerRadius(4f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1) }
        }
    }
}

@Composable
fun Legend(items: List<ChartSlice>, currency: CurrencyInfo = LocalBaseCurrency.current) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorDot(s.color)
                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Money(s.value, style = MaterialTheme.typography.bodySmall, currency = currency)
            }
        }
    }
}

fun fmt(minor: Long, currency: CurrencyInfo): String = MoneyFormatter.format(minor, currency)
