package com.wyrix.budget.domain.calc

enum class TaxRegime(val label: String) { NEW("New regime"), OLD("Old regime") }

data class TaxSlab(val upToMinor: Long?, val rateBps: Int)

data class TaxRules(
    val fyStartYear: Int,
    val regime: TaxRegime,
    val slabs: List<TaxSlab>,
    val standardDeductionMinor: Long,
    val rebateLimitMinor: Long,
    val rebateMaxMinor: Long,
    val marginalRelief: Boolean,
    val cessBps: Int = 400,
) {
    val fyLabel: String get() = "FY $fyStartYear-${(fyStartYear + 1) % 100}"
}

/**
 * Configurable Indian income-tax rule tables (resident individual, below 60). Rules change; these are
 * checked against published slabs for FY 2024-25, 2025-26 and 2026-27 and must be reviewed every year.
 * Surcharge, senior-citizen slabs and special-rate income are NOT modelled.
 */
object IndianTaxRules {
    private fun rs(rupees: Long) = rupees * 100
    private val LAKH = 100_000L

    val supportedYears: List<Int> = listOf(2026, 2025, 2024)

    fun rules(fyStartYear: Int, regime: TaxRegime): TaxRules? {
        if (fyStartYear !in supportedYears) return null
        return when (regime) {
            TaxRegime.OLD -> TaxRules(
                fyStartYear, regime,
                slabs = listOf(
                    TaxSlab(rs(250_000), 0), TaxSlab(rs(5 * LAKH), 500),
                    TaxSlab(rs(10 * LAKH), 2000), TaxSlab(null, 3000),
                ),
                standardDeductionMinor = rs(50_000),
                rebateLimitMinor = rs(5 * LAKH), rebateMaxMinor = rs(12_500),
                marginalRelief = false,
            )
            TaxRegime.NEW -> if (fyStartYear >= 2025) TaxRules(
                fyStartYear, regime,
                slabs = listOf(
                    TaxSlab(rs(4 * LAKH), 0), TaxSlab(rs(8 * LAKH), 500), TaxSlab(rs(12 * LAKH), 1000),
                    TaxSlab(rs(16 * LAKH), 1500), TaxSlab(rs(20 * LAKH), 2000),
                    TaxSlab(rs(24 * LAKH), 2500), TaxSlab(null, 3000),
                ),
                standardDeductionMinor = rs(75_000),
                rebateLimitMinor = rs(12 * LAKH), rebateMaxMinor = rs(60_000),
                marginalRelief = true,
            ) else TaxRules(
                fyStartYear, regime,
                slabs = listOf(
                    TaxSlab(rs(3 * LAKH), 0), TaxSlab(rs(7 * LAKH), 500), TaxSlab(rs(10 * LAKH), 1000),
                    TaxSlab(rs(12 * LAKH), 1500), TaxSlab(rs(15 * LAKH), 2000), TaxSlab(null, 3000),
                ),
                standardDeductionMinor = rs(75_000),
                rebateLimitMinor = rs(7 * LAKH), rebateMaxMinor = rs(25_000),
                marginalRelief = true,
            )
        }
    }
}

object TaxEstimator {
    data class SlabRow(val fromMinor: Long, val toMinor: Long?, val rateBps: Int, val taxMinor: Long)
    data class Result(
        val taxableIncomeMinor: Long,
        val slabRows: List<SlabRow>,
        val taxBeforeRebateMinor: Long,
        val rebateMinor: Long,
        val marginalReliefMinor: Long,
        val cessMinor: Long,
        val totalTaxMinor: Long,
        val effectiveRateBps: Long,
    )

    /**
     * @param otherDeductionsMinor 80C/80D/HRA etc. Applied only under the old regime.
     * @param salaried whether the standard deduction applies.
     */
    fun estimate(rules: TaxRules, grossIncomeMinor: Long, otherDeductionsMinor: Long, salaried: Boolean): Result {
        val std = if (salaried) rules.standardDeductionMinor else 0L
        val other = if (rules.regime == TaxRegime.OLD) otherDeductionsMinor else 0L
        val taxable = (grossIncomeMinor - std - other).coerceAtLeast(0)

        val rows = ArrayList<SlabRow>()
        var lower = 0L
        var tax = 0L
        for (slab in rules.slabs) {
            val upper = slab.upToMinor
            val top = if (upper == null) taxable else minOf(taxable, upper)
            val inBand = (top - lower).coerceAtLeast(0)
            val bandTax = roundDiv(inBand * slab.rateBps, 10_000)
            rows += SlabRow(lower, upper, slab.rateBps, bandTax)
            tax += bandTax
            if (upper == null || taxable <= upper) break
            lower = upper
        }

        val rebate = if (taxable <= rules.rebateLimitMinor) minOf(tax, rules.rebateMaxMinor) else 0L
        var relief = 0L
        if (rules.marginalRelief && taxable > rules.rebateLimitMinor) {
            val excess = taxable - rules.rebateLimitMinor
            if (tax > excess) relief = tax - excess
        }
        val afterRelief = (tax - rebate - relief).coerceAtLeast(0)
        val cess = roundDiv(afterRelief * rules.cessBps, 10_000)
        val total = afterRelief + cess
        val effective = if (grossIncomeMinor <= 0) 0 else total * 10_000 / grossIncomeMinor
        return Result(taxable, rows, tax, rebate, relief, cess, total, effective)
    }
}
