package com.wyrix.budget.domain.calc

/** Generic, exporter-friendly report: a summary block, tables and an optional bar chart. */
data class ReportTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    /** Indexes of columns holding plain decimal numbers (written as numbers in Excel, never formula-escaped in CSV). */
    val numericColumns: Set<Int> = emptySet(),
)

data class Report(
    val title: String,
    val periodLabel: String,
    val generatedOn: String,
    val currencyCode: String,
    val summary: List<Pair<String, String>>,
    val tables: List<ReportTable>,
    /** label -> amount in minor units, drawn as a bar chart in PDF reports. */
    val bars: List<Pair<String, Long>> = emptyList(),
)
