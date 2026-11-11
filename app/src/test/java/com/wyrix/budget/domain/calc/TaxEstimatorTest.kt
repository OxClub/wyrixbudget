package com.wyrix.budget.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxEstimatorTest {
    private fun rs(x: Long) = x * 100
    private fun newRegime(year: Int) = IndianTaxRules.rules(year, TaxRegime.NEW)!!
    private fun oldRegime(year: Int) = IndianTaxRules.rules(year, TaxRegime.OLD)!!

    @Test fun newRegime2026FifteenLakhSalaried() {
        // taxable 14,25,000 -> 93,750 + 4% cess = 97,500
        val r = TaxEstimator.estimate(newRegime(2026), rs(1_500_000), 0, salaried = true)
        assertEquals(rs(1_425_000), r.taxableIncomeMinor)
        assertEquals(rs(93_750), r.taxBeforeRebateMinor)
        assertEquals(rs(3_750), r.cessMinor)
        assertEquals(rs(97_500), r.totalTaxMinor)
    }

    @Test fun newRegimeRebateMakesUpTo12LakhTaxFree() {
        val atLimit = TaxEstimator.estimate(newRegime(2026), rs(1_275_000), 0, salaried = true) // taxable 12,00,000
        assertEquals(rs(60_000), atLimit.taxBeforeRebateMinor)
        assertEquals(rs(60_000), atLimit.rebateMinor)
        assertEquals(0L, atLimit.totalTaxMinor)

        val belowLimit = TaxEstimator.estimate(newRegime(2025), rs(1_200_000), 0, salaried = true)
        assertEquals(0L, belowLimit.totalTaxMinor)
    }

    @Test fun marginalReliefJustAboveTheRebateLimit() {
        // taxable 12,10,000: slab tax 61,500 but tax is capped at the 10,000 earned above 12 lakh (+4% cess)
        val r = TaxEstimator.estimate(newRegime(2026), rs(1_285_000), 0, salaried = true)
        assertEquals(rs(61_500), r.taxBeforeRebateMinor)
        assertEquals(rs(51_500), r.marginalReliefMinor)
        assertEquals(rs(10_400), r.totalTaxMinor)
    }

    @Test fun oldRegimeWithDeductions() {
        // gross 10L, standard 50k + other deductions 2.5L -> taxable 7L -> 52,500 + cess 2,100
        val r = TaxEstimator.estimate(oldRegime(2026), rs(1_000_000), rs(250_000), salaried = true)
        assertEquals(rs(700_000), r.taxableIncomeMinor)
        assertEquals(rs(54_600), r.totalTaxMinor)
    }

    @Test fun otherDeductionsAreIgnoredUnderTheNewRegime() {
        val a = TaxEstimator.estimate(newRegime(2026), rs(1_500_000), 0, true)
        val b = TaxEstimator.estimate(newRegime(2026), rs(1_500_000), rs(300_000), true)
        assertEquals(a.totalTaxMinor, b.totalTaxMinor)
    }

    @Test fun oldRegimeRebateUpToFiveLakh() {
        val r = TaxEstimator.estimate(oldRegime(2026), rs(550_000), 0, salaried = true) // taxable 5,00,000
        assertEquals(0L, r.totalTaxMinor)
    }

    @Test fun fy2024UsesTheOlderNewRegimeSlabs() {
        val r = TaxEstimator.estimate(newRegime(2024), rs(1_000_000), 0, salaried = true) // taxable 9,25,000
        assertEquals(rs(42_500), r.taxBeforeRebateMinor)
        assertEquals(rs(44_200), r.totalTaxMinor)
    }

    @Test fun nonSalariedGetsNoStandardDeduction() {
        val r = TaxEstimator.estimate(newRegime(2026), rs(1_000_000), 0, salaried = false)
        assertEquals(rs(1_000_000), r.taxableIncomeMinor)
    }

    @Test fun unsupportedYearsReturnNoRules() {
        assertNull(IndianTaxRules.rules(2019, TaxRegime.NEW))
    }

    @Test fun zeroIncome() {
        val r = TaxEstimator.estimate(newRegime(2026), 0, 0, true)
        assertEquals(0L, r.totalTaxMinor)
        assertEquals(0L, r.effectiveRateBps)
    }
}
