package com.wyrix.budget.domain.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val MC = MathContext(34, RoundingMode.HALF_EVEN)

internal fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b
internal fun roundDiv(a: Long, b: Long): Long = (a + b / 2) / b

/** Loan maths. Rates are annual percentages in basis points (850 = 8.50%). Amounts in minor units. */
object LoanMath {
    data class Row(val index: Int, val paymentMinor: Long, val interestMinor: Long, val principalMinor: Long, val balanceMinor: Long)
    data class Summary(val emiMinor: Long, val totalInterestMinor: Long, val totalPaymentMinor: Long, val schedule: List<Row>)

    /** One month of interest: balance x (annual rate / 12), rounded half-up to a minor unit. */
    fun monthlyInterest(balanceMinor: Long, rateBps: Int): Long =
        if (balanceMinor <= 0 || rateBps <= 0) 0 else (balanceMinor * rateBps + 60_000L) / 120_000L

    /** EMI = P x r x (1+r)^n / ((1+r)^n - 1); zero-interest loans are split evenly. */
    fun emi(principalMinor: Long, rateBps: Int, months: Int): Long {
        require(months > 0) { "months must be positive" }
        if (principalMinor <= 0) return 0
        if (rateBps <= 0) return roundDiv(principalMinor, months.toLong())
        val r = BigDecimal(rateBps).divide(BigDecimal(120_000), MC)
        val pow = BigDecimal.ONE.add(r).pow(months, MC)
        val numerator = BigDecimal(principalMinor).multiply(r).multiply(pow)
        val denominator = pow.subtract(BigDecimal.ONE)
        return numerator.divide(denominator, MC).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** Full amortisation schedule for [months] instalments; the final instalment clears any rounding residue. */
    fun schedule(principalMinor: Long, rateBps: Int, months: Int): Summary {
        val emi = emi(principalMinor, rateBps, months)
        return amortize(principalMinor, rateBps, emi, maxMonths = months, forceClearAt = months)!!
    }

    /**
     * Schedule for a known payment amount. Returns null if the payment never clears the balance
     * (e.g. it does not even cover the interest) within [maxMonths].
     */
    fun amortize(
        principalMinor: Long,
        rateBps: Int,
        paymentMinor: Long,
        maxMonths: Int = 600,
        forceClearAt: Int? = null,
    ): Summary? {
        if (principalMinor <= 0) return Summary(paymentMinor, 0, 0, emptyList())
        var balance = principalMinor
        var totalInterest = 0L
        var totalPaid = 0L
        val rows = ArrayList<Row>()
        for (i in 1..maxMonths) {
            val interest = monthlyInterest(balance, rateBps)
            var principalPart = paymentMinor - interest
            var payment = paymentMinor
            if (principalPart >= balance || i == forceClearAt) {
                principalPart = balance
                payment = balance + interest
            }
            if (principalPart <= 0 && i == maxMonths) return null
            balance -= principalPart
            totalInterest += interest
            totalPaid += payment
            rows += Row(i, payment, interest, principalPart, balance)
            if (balance <= 0L) return Summary(paymentMinor, totalInterest, totalPaid, rows)
        }
        return null
    }
}

object CompoundMath {
    /** A = P(1 + r/n)^(nt). This is an estimate for illustration, never a guaranteed return. */
    fun futureValue(principalMinor: Long, rateBps: Int, compoundsPerYear: Int, months: Int): Long {
        require(compoundsPerYear > 0 && months >= 0)
        val r = rateBps / 10_000.0
        val n = compoundsPerYear.toDouble()
        val t = months / 12.0
        return Math.round(principalMinor * Math.pow(1 + r / n, n * t))
    }
}

object SavingsMath {
    data class Plan(
        val remainingMinor: Long,
        val days: Long,
        val months: Int,
        val dailyMinor: Long,
        val weeklyMinor: Long,
        val monthlyMinor: Long,
    )

    /** Required contributions to reach [targetMinor] by [to]. Returns null if [to] is not after [from]. */
    fun plan(targetMinor: Long, savedMinor: Long, from: LocalDate, to: LocalDate): Plan? {
        if (!to.isAfter(from)) return null
        val remaining = (targetMinor - savedMinor).coerceAtLeast(0)
        val days = ChronoUnit.DAYS.between(from, to)
        var months = ChronoUnit.MONTHS.between(from, to).toInt()
        if (from.plusMonths(months.toLong()).isBefore(to)) months += 1
        months = months.coerceAtLeast(1)
        val weeks = ceilDiv(days, 7).coerceAtLeast(1)
        return Plan(
            remainingMinor = remaining,
            days = days,
            months = months,
            dailyMinor = ceilDiv(remaining, days),
            weeklyMinor = ceilDiv(remaining, weeks),
            monthlyMinor = ceilDiv(remaining, months.toLong()),
        )
    }

    fun monthlyForMonths(targetMinor: Long, savedMinor: Long, months: Int): Long =
        ceilDiv((targetMinor - savedMinor).coerceAtLeast(0), months.coerceAtLeast(1).toLong())

    /** Progress in basis points (3889 = 38.89%); not capped, so over-saving shows above 10000. */
    fun progressBps(savedMinor: Long, targetMinor: Long): Long =
        if (targetMinor <= 0) 0 else savedMinor.coerceAtLeast(0) * 10_000 / targetMinor
}

object EmergencyFundMath {
    fun target(monthlyEssentialMinor: Long, months: Int): Long = monthlyEssentialMinor * months
    fun shortfall(targetMinor: Long, currentMinor: Long): Long = (targetMinor - currentMinor).coerceAtLeast(0)
}

enum class DebtStrategy(val label: String) {
    SNOWBALL("Snowball (smallest balance first)"),
    AVALANCHE("Avalanche (highest interest first)"),
}

object DebtPayoff {
    data class DebtInput(val name: String, val balanceMinor: Long, val rateBps: Int, val minPaymentMinor: Long)
    data class Result(val months: Int, val totalInterestMinor: Long, val payoffOrder: List<String>, val completed: Boolean)

    /**
     * Month-by-month simulation. Every month the same total (all minimum payments plus [extraMonthlyMinor])
     * is paid; minimums go to every open debt and the rest to the priority debt, so freed-up minimums
     * "roll over". Interest accrues monthly before payments.
     */
    fun simulate(
        debts: List<DebtInput>,
        extraMonthlyMinor: Long,
        strategy: DebtStrategy,
        maxMonths: Int = 600,
    ): Result {
        val balance = debts.map { it.balanceMinor }.toLongArray()
        val cleared = BooleanArray(debts.size) { balance[it] <= 0 }
        val order = ArrayList<String>()
        val totalBudget = debts.sumOf { it.minPaymentMinor } + extraMonthlyMinor
        var totalInterest = 0L
        var month = 0
        while (balance.any { it > 0 } && month < maxMonths) {
            month++
            for (i in balance.indices) if (balance[i] > 0) {
                val interest = LoanMath.monthlyInterest(balance[i], debts[i].rateBps)
                balance[i] += interest
                totalInterest += interest
            }
            var pool = totalBudget
            for (i in balance.indices) if (balance[i] > 0) {
                val pay = minOf(balance[i], debts[i].minPaymentMinor)
                balance[i] -= pay
                pool -= pay
            }
            val priority = balance.indices.filter { balance[it] > 0 }.sortedWith(
                when (strategy) {
                    DebtStrategy.SNOWBALL -> compareBy<Int>({ balance[it] }, { -debts[it].rateBps })
                    DebtStrategy.AVALANCHE -> compareBy<Int>({ -debts[it].rateBps }, { balance[it] })
                },
            )
            for (i in priority) {
                if (pool <= 0) break
                val pay = minOf(balance[i], pool)
                balance[i] -= pay
                pool -= pay
            }
            for (i in balance.indices) if (!cleared[i] && balance[i] <= 0) {
                cleared[i] = true
                order += debts[i].name
            }
        }
        return Result(month, totalInterest, order, completed = balance.none { it > 0 })
    }
}

object AffordabilityMath {
    data class Result(
        val remainingBudgetAfterMinor: Long,
        val balanceAfterMinor: Long,
        val withinBudget: Boolean,
        val balanceStaysPositive: Boolean,
    )

    fun evaluate(purchaseMinor: Long, remainingBudgetMinor: Long, currentBalanceMinor: Long, upcomingObligationsMinor: Long): Result {
        val budgetAfter = remainingBudgetMinor - purchaseMinor
        val balanceAfter = currentBalanceMinor - upcomingObligationsMinor - purchaseMinor
        return Result(budgetAfter, balanceAfter, budgetAfter >= 0, balanceAfter >= 0)
    }
}

object BudgetAllocator {
    /** Splits [totalMinor] by integer weights using the largest-remainder method so shares sum exactly. */
    fun allocate(totalMinor: Long, weights: List<Int>): List<Long> {
        val sum = weights.sumOf { it.toLong() }
        if (weights.isEmpty() || sum <= 0 || totalMinor <= 0) return List(weights.size) { 0L }
        val base = weights.map { totalMinor * it / sum }.toMutableList()
        var leftover = totalMinor - base.sum()
        val remainders = weights.indices.sortedByDescending { (totalMinor * weights[it]) % sum }
        var i = 0
        while (leftover > 0) {
            base[remainders[i % remainders.size]] += 1
            leftover--
            i++
        }
        return base
    }
}
