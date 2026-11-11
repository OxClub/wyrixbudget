package com.wyrix.budget.presentation.invoices

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

private data class GstTotals(val taxable: BigDecimal, val cgst: BigDecimal, val sgst: BigDecimal, val igst: BigDecimal, val total: BigDecimal)
private fun decimal(value: String) = value.toBigDecimalOrNull() ?: BigDecimal.ZERO
private fun money(value: BigDecimal) = "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(value)
private fun calculateTotals(quantity: BigDecimal, rate: BigDecimal, gst: BigDecimal, interState: Boolean): GstTotals {
    val taxable = quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP)
    val tax = taxable.multiply(gst).divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
    val half = tax.divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)
    val cgst = if (interState) BigDecimal.ZERO else half
    val sgst = if (interState) BigDecimal.ZERO else tax.subtract(half)
    val igst = if (interState) tax else BigDecimal.ZERO
    return GstTotals(taxable, cgst, sgst, igst, taxable.add(tax).setScale(2, RoundingMode.HALF_UP))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GstInvoiceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var seller by remember { mutableStateOf("") }; var sellerAddress by remember { mutableStateOf("") }; var sellerGstin by remember { mutableStateOf("") }
    var buyer by remember { mutableStateOf("") }; var buyerAddress by remember { mutableStateOf("") }; var buyerGstin by remember { mutableStateOf("") }
    var invoiceNo by remember { mutableStateOf("INV-${LocalDate.now().year}-001") }; var invoiceDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var description by remember { mutableStateOf("") }; var hsn by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }; var unitPrice by remember { mutableStateOf("0") }; var gstRate by remember { mutableStateOf("18") }
    var interState by remember { mutableStateOf(false) }
    val totals = remember(quantity, unitPrice, gstRate, interState) { calculateTotals(decimal(quantity), decimal(unitPrice), decimal(gstRate), interState) }

    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val document = buildInvoicePdf(invoiceNo, invoiceDate, seller, sellerAddress, sellerGstin, buyer, buyerAddress, buyerGstin, description, hsn, quantity, unitPrice, gstRate, interState, totals)
            try {
                val output = context.contentResolver.openOutputStream(uri)
                if (output == null) Toast.makeText(context, "Unable to open destination", Toast.LENGTH_LONG).show()
                else output.use { document.writeTo(it); Toast.makeText(context, "Invoice PDF exported", Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                Toast.makeText(context, "Could not export invoice: ${error.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            } finally { document.close() }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("GST invoice generator") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("GST invoice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Create an itemised Indian GST invoice and export it as a PDF.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            InvoiceSection("Invoice details") {
                InvoiceField("Invoice number", invoiceNo) { invoiceNo = it }
                InvoiceField("Invoice date (YYYY-MM-DD)", invoiceDate) { invoiceDate = it }
            }
            InvoiceSection("Your business") {
                InvoiceField("Business / seller name *", seller) { seller = it }
                InvoiceField("Business address", sellerAddress, singleLine = false) { sellerAddress = it }
                InvoiceField("Seller GSTIN", sellerGstin, supporting = "15-character GSTIN, if registered") { sellerGstin = it.uppercase(Locale.ROOT).take(15) }
            }
            InvoiceSection("Bill to customer") {
                InvoiceField("Customer name *", buyer) { buyer = it }
                InvoiceField("Customer address", buyerAddress, singleLine = false) { buyerAddress = it }
                InvoiceField("Customer GSTIN", buyerGstin, supporting = "Leave blank for an unregistered customer") { buyerGstin = it.uppercase(Locale.ROOT).take(15) }
            }
            InvoiceSection("Item or service") {
                InvoiceField("Description *", description, singleLine = false) { description = it }
                InvoiceField("HSN / SAC code", hsn) { hsn = it }
                InvoiceField("Quantity", quantity, KeyboardType.Decimal) { quantity = it.filter { c -> c.isDigit() || c == '.' }.take(12) }
                InvoiceField("Rate per unit (₹)", unitPrice, KeyboardType.Decimal) { unitPrice = it.filter { c -> c.isDigit() || c == '.' }.take(14) }
                InvoiceField("GST rate (%)", gstRate, KeyboardType.Decimal, supporting = "Common rates: 0, 5, 12, 18 or 28") { gstRate = it.filter { c -> c.isDigit() || c == '.' }.take(5) }
            }
            InvoiceSection("Tax treatment") {
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = !interState, onClick = { interState = false }); Text("Intra-state (CGST + SGST)") }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = interState, onClick = { interState = true }); Text("Inter-state (IGST)") }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Invoice summary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TotalLine("Taxable amount", money(totals.taxable))
                    if (interState) TotalLine("IGST (${gstRate.ifBlank { "0" }}%)", money(totals.igst)) else {
                        TotalLine("CGST (${decimal(gstRate).divide(BigDecimal("2")).stripTrailingZeros().toPlainString()}%)", money(totals.cgst))
                        TotalLine("SGST (${decimal(gstRate).divide(BigDecimal("2")).stripTrailingZeros().toPlainString()}%)", money(totals.sgst))
                    }
                    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Grand total", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                            Text(money(totals.total), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Button(onClick = { createPdf.launch("${invoiceNo.ifBlank { "WYRIX-invoice" }}.pdf") }, enabled = seller.isNotBlank() && buyer.isNotBlank() && description.isNotBlank() && decimal(quantity) > BigDecimal.ZERO && decimal(unitPrice) >= BigDecimal.ZERO && decimal(gstRate) >= BigDecimal.ZERO && decimal(gstRate) <= BigDecimal("100"), modifier = Modifier.fillMaxWidth()) { Text("Export professional PDF invoice") }
            OutlinedButton(onClick = { seller = ""; sellerAddress = ""; sellerGstin = ""; buyer = ""; buyerAddress = ""; buyerGstin = ""; description = ""; hsn = ""; quantity = "1"; unitPrice = "0"; gstRate = "18"; interState = false }, modifier = Modifier.fillMaxWidth()) { Text("Clear form") }
            Text("Note: This tool calculates an illustrative GST invoice. Confirm the applicable GST rate, HSN/SAC, place of supply, invoice rules and tax treatment for your transaction before issuing it. It does not file GST returns or validate GSTINs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InvoiceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content() }
    }
}

@Composable
private fun InvoiceField(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, supporting: String? = null, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = singleLine, keyboardOptions = KeyboardOptions(keyboardType = keyboard), supportingText = supporting?.let { { Text(it) } })
}

@Composable
private fun TotalLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.SemiBold) }
}

private fun buildInvoicePdf(invoiceNo: String, invoiceDate: String, seller: String, sellerAddress: String, sellerGstin: String, buyer: String, buyerAddress: String, buyerGstin: String, description: String, hsn: String, quantity: String, unitPrice: String, gstRate: String, interState: Boolean, totals: GstTotals): PdfDocument {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val canvas = page.canvas
    val navy = Paint().apply { color = android.graphics.Color.rgb(13, 39, 76); style = Paint.Style.FILL }
    canvas.drawRect(0f, 0f, 595f, 130f, navy)
    fun draw(text: String, x: Float, y: Float, size: Float = 12f, bold: Boolean = false, color: Int = android.graphics.Color.DKGRAY) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL); this.color = color }
        canvas.drawText(text.take(85), x, y, paint)
    }
    val white = android.graphics.Color.WHITE
    draw("WYRIX BUDGET", 40f, 48f, 23f, true, white); draw("TAX INVOICE", 40f, 82f, 17f, true, white)
    draw("Invoice: $invoiceNo", 390f, 48f, 12f, true, white); draw("Date: $invoiceDate", 390f, 72f, 11f, false, white)
    draw("FROM", 40f, 165f, 10f, true, android.graphics.Color.GRAY); draw(seller, 40f, 190f, 15f, true); draw(sellerAddress, 40f, 211f); draw("GSTIN: ${sellerGstin.ifBlank { "Not provided" }}", 40f, 232f)
    draw("BILL TO", 315f, 165f, 10f, true, android.graphics.Color.GRAY); draw(buyer, 315f, 190f, 15f, true); draw(buyerAddress, 315f, 211f); draw("GSTIN: ${buyerGstin.ifBlank { "Unregistered / not provided" }}", 315f, 232f)
    val line = Paint().apply { color = android.graphics.Color.LTGRAY; strokeWidth = 1f }; canvas.drawLine(40f, 270f, 555f, 270f, line)
    draw("DESCRIPTION", 40f, 300f, 10f, true); draw("HSN/SAC", 280f, 300f, 10f, true); draw("QTY", 365f, 300f, 10f, true); draw("RATE", 420f, 300f, 10f, true); draw("AMOUNT", 495f, 300f, 10f, true)
    draw(description, 40f, 330f, 12f, true); draw(hsn.ifBlank { "—" }, 280f, 330f); draw(quantity, 365f, 330f); draw(money(decimal(unitPrice)), 420f, 330f); draw(money(totals.taxable), 495f, 330f); canvas.drawLine(40f, 355f, 555f, 355f, line)
    var y = 395f; draw("Taxable amount", 350f, y); draw(money(totals.taxable), 485f, y); y += 26f
    if (interState) { draw("IGST ($gstRate%)", 350f, y); draw(money(totals.igst), 485f, y) } else {
        draw("CGST (${decimal(gstRate).divide(BigDecimal("2")).stripTrailingZeros().toPlainString()}%)", 350f, y); draw(money(totals.cgst), 485f, y); y += 26f
        draw("SGST (${decimal(gstRate).divide(BigDecimal("2")).stripTrailingZeros().toPlainString()}%)", 350f, y); draw(money(totals.sgst), 485f, y)
    }
    y += 55f; val totalPaint = Paint().apply { color = android.graphics.Color.rgb(13, 39, 76); style = Paint.Style.FILL }
    canvas.drawRoundRect(330f, y - 25f, 555f, y + 15f, 8f, 8f, totalPaint); draw("GRAND TOTAL  ${money(totals.total)}", 345f, y + 1f, 13f, true, white)
    draw("Thank you for your business.", 40f, 700f, 12f, true); draw("Generated with WYRIX Budget · Please verify tax details before issue.", 40f, 735f, 9f, false, android.graphics.Color.GRAY)
    document.finishPage(page)
    return document
}
