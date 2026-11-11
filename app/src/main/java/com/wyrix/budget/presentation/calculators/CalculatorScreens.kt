package com.wyrix.budget.presentation.calculators

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.*
import com.wyrix.budget.domain.model.LoanWithOutstanding
import com.wyrix.budget.domain.repository.AnalyticsRepository
import com.wyrix.budget.domain.repository.BudgetRepository
import com.wyrix.budget.domain.repository.LoanRepository
import com.wyrix.budget.domain.usecase.PlanningSnapshot
import com.wyrix.budget.presentation.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class CalcKind(val label: String, val blurb: String) {
    EMI("Loan EMI", "Monthly payment, total interest and schedule"),
    COMPOUND("Compound interest", "What a deposit could grow to"),
    SAVINGS("Savings goal", "How much to save per day, week or month"),
    EMERGENCY("Emergency fund", "Target based on your essential spending"),
    DEBT("Debt payoff", "Snowball vs avalanche on your recorded debts"),
    AFFORD("Affordability", "Can you afford a purchase right now?"),
    BUDGET_PLAN("Budget planner", "Split your income into needs, wants and savings"),
    TAX("Income tax estimate (India)", "Old vs new regime, with the assumptions shown"),
}

data class CalcContext(
    val base: CurrencyInfo = CurrencyInfo.INR,
    val loaded: Boolean = false,
    val balanceMinor: Long = 0,
    val budgetRemainingMinor: Long? = null,
    val upcoming30Minor: Long = 0,
    val avgMonthlyExpenseMinor: Long = 0,
    val debts: List<DebtPayoff.DebtInput> = emptyList(),
    val fyStartYear: Int = LocalDate.now().year,
)

@HiltViewModel
class CalculatorsViewModel @Inject constructor(
    prefs: UserPreferencesRepository, snapshot: PlanningSnapshot, budgets: BudgetRepository, loans: LoanRepository, analytics: AnalyticsRepository, private val clock: Clock,
) : UiViewModel() {
    private val _ctx = MutableStateFlow(CalcContext())
    val ctx: StateFlow<CalcContext> = _ctx

    init {
        launchSafely {
            val s = prefs.settings.first()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val now = clock.instant()
            val active = budgets.observeStatuses(today, zone).first().filter { !today.isBefore(it.budget.start) && !today.isAfter(it.budget.end) }
            val range = com.wyrix.budget.core.time.TimeRange(now.minusSeconds(90L * 86_400).toEpochMilli(), now.plusSeconds(86_400).toEpochMilli())
            val spent90 = analytics.byCategory(true, range).sumOf { it.amountMinor }
            val debts = loans.observeAll().first().filter { it.loan.isActive && it.outstandingMinor > 0 }.map {
                DebtPayoff.DebtInput(it.loan.lender, it.outstandingMinor, it.loan.rateBps, it.loan.emiMinor)
            }
            _ctx.value = CalcContext(
                base = CurrencyInfo.byCode(s.baseCurrency), loaded = true, balanceMinor = snapshot.balance().totalMinor,
                budgetRemainingMinor = active.firstOrNull()?.remainingMinor, upcoming30Minor = snapshot.outflowNext(30, today),
                avgMonthlyExpenseMinor = spent90 / 3, debts = debts, fyStartYear = PeriodCalculator.financialYearStart(now, zone, s.fyStartMonth),
            )
        }
    }
}

@Composable
fun CalculatorsHubScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    ScreenScaffold("Calculators", onBack) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(CalcKind.entries, key = { it.name }) { k ->
                InfoCard(Modifier.clickable { onOpen(k.name) }) {
                    Text(k.label, style = MaterialTheme.typography.titleMedium)
                    Note(k.blurb)
                }
            }
            item { Note("Calculators give estimates for planning. They are not financial, tax or legal advice.") }
        }
    }
}

@Composable
fun CalculatorScreen(kind: String, onBack: () -> Unit, vm: CalculatorsViewModel = hiltViewModel()) {
    val ctx by vm.ctx.collectAsStateWithLifecycle()
    val k = CalcKind.entries.firstOrNull { it.name == kind } ?: CalcKind.EMI
    ScreenScaffold(k.label, onBack) { padding ->
        if (!ctx.loaded) { LoadingBox(padding); return@ScreenScaffold }
        FormColumn(padding) {
            when (k) {
                CalcKind.EMI -> EmiCalc(ctx)
                CalcKind.COMPOUND -> CompoundCalc(ctx)
                CalcKind.SAVINGS -> SavingsCalc(ctx)
                CalcKind.EMERGENCY -> EmergencyCalc(ctx)
                CalcKind.DEBT -> DebtCalc(ctx)
                CalcKind.AFFORD -> AffordCalc(ctx)
                CalcKind.BUDGET_PLAN -> BudgetPlanCalc(ctx)
                CalcKind.TAX -> TaxCalc(ctx)
            }
        }
    }
}

private fun money(text: String, cur: CurrencyInfo): Long? = MoneyParser.parseOrNull(text, currency = cur)
private fun rateBps(text: String): Int? = text.toBigDecimalOrNull()?.movePointRight(2)?.toInt()?.takeIf { it in 0..10_000 }

@Composable private fun EmiCalc(c: CalcContext) {
    var amount by rememberSaveable { mutableStateOf("") }; var rate by rememberSaveable { mutableStateOf("") }; var months by rememberSaveable { mutableStateOf("") }
    MoneyField(amount, { amount = it }, "Loan amount", currency = c.base)
    NumberField(rate, { rate = it }, "Interest rate (per year)", decimal = true, suffix = "%")
    NumberField(months, { months = it }, "Tenure", suffix = "months")
    val p = money(amount, c.base); val r = rateBps(rate); val n = months.toIntOrNull()
    if (p != null && p > 0 && r != null && n != null && n in 1..600) {
        val s = LoanMath.schedule(p, r, n)
        InfoCard {
            SectionTitle("Result")
            MoneyStatRow("Monthly EMI", s.emiMinor, c.base); MoneyStatRow("Total interest", s.totalInterestMinor, c.base); MoneyStatRow("Total payment", s.totalPaymentMinor, c.base)
        }
        InfoCard {
            SectionTitle("First payments")
            s.schedule.take(6).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("#${row.index}"); Money(row.principalMinor, currency = c.base, style = MaterialTheme.typography.bodySmall); Money(row.interestMinor, currency = c.base, style = MaterialTheme.typography.bodySmall); Money(row.balanceMinor, currency = c.base, style = MaterialTheme.typography.bodySmall) } }
            Note("Columns: number, principal, interest, balance after. EMI = P × r × (1+r)^n ÷ ((1+r)^n − 1), r = annual rate ÷ 12.")
        }
    } else Note("Enter an amount, rate and tenure (1 to 600 months).")
}

@Composable private fun CompoundCalc(c: CalcContext) {
    var amount by rememberSaveable { mutableStateOf("") }; var rate by rememberSaveable { mutableStateOf("") }; var months by rememberSaveable { mutableStateOf("") }; var n by rememberSaveable { mutableStateOf(12) }
    MoneyField(amount, { amount = it }, "Amount invested", currency = c.base)
    NumberField(rate, { rate = it }, "Interest rate (per year)", decimal = true, suffix = "%")
    NumberField(months, { months = it }, "Period", suffix = "months")
    ChipSelect("Compounded", listOf(1, 2, 4, 12), n, { n = it }) { when (it) { 1 -> "Yearly"; 2 -> "Half-yearly"; 4 -> "Quarterly"; else -> "Monthly" } }
    val p = money(amount, c.base); val r = rateBps(rate); val m = months.toIntOrNull()
    if (p != null && r != null && m != null && m in 1..1200) {
        val fv = CompoundMath.futureValue(p, r, n, m)
        InfoCard { MoneyStatRow("Future value", fv, c.base); MoneyStatRow("Interest earned", fv - p, c.base); Note("A = P(1 + r/n)^(nt). An illustration only; real returns vary and are never guaranteed.") }
    }
}

@Composable private fun SavingsCalc(c: CalcContext) {
    var target by rememberSaveable { mutableStateOf("") }; var saved by rememberSaveable { mutableStateOf("") }; var months by rememberSaveable { mutableStateOf("12") }
    MoneyField(target, { target = it }, "Target amount", currency = c.base)
    MoneyField(saved, { saved = it }, "Already saved", currency = c.base)
    NumberField(months, { months = it }, "Time to reach it", suffix = "months")
    val t = money(target, c.base); val s = money(saved.ifBlank { "0" }, c.base); val m = months.toIntOrNull()
    if (t != null && s != null && m != null && m in 1..1200) {
        val to = LocalDate.now().plusMonths(m.toLong())
        val plan = SavingsMath.plan(t, s, LocalDate.now(), to)
        if (plan != null) InfoCard {
            MoneyStatRow("Still to save", plan.remainingMinor, c.base); MoneyStatRow("Per month", plan.monthlyMinor, c.base)
            MoneyStatRow("Per week", plan.weeklyMinor, c.base); MoneyStatRow("Per day", plan.dailyMinor, c.base); StatRow("Target date", to.toString())
        }
    }
}

@Composable private fun EmergencyCalc(c: CalcContext) {
    var monthly by rememberSaveable { mutableStateOf("") }; var current by rememberSaveable { mutableStateOf("") }; var months by rememberSaveable { mutableStateOf(6) }
    MoneyField(monthly, { monthly = it }, "Monthly essential expenses", currency = c.base)
    if (c.avgMonthlyExpenseMinor > 0) TextButton(onClick = { monthly = MoneyFormatter.formatPlain(c.avgMonthlyExpenseMinor, c.base) }) { Text("Use my 3-month average (${MoneyFormatter.format(c.avgMonthlyExpenseMinor, c.base)})") }
    ChipSelect("Months of cover", listOf(3, 6, 9, 12), months, { months = it }) { "$it months" }
    MoneyField(current, { current = it }, "Emergency savings you have now", currency = c.base)
    val m = money(monthly, c.base); val cur = money(current.ifBlank { "0" }, c.base)
    if (m != null && cur != null) {
        val target = EmergencyFundMath.target(m, months); val short = EmergencyFundMath.shortfall(target, cur)
        InfoCard {
            MoneyStatRow("Target fund", target, c.base); MoneyStatRow("Shortfall", short, c.base)
            if (short > 0) MoneyStatRow("To close it in 12 months", SavingsMath.monthlyForMonths(target, cur, 12), c.base) else Text("You have reached this target.")
            Note("Essential expenses are things you must pay even if income stops. Typical guidance is 3 to 6 months.")
        }
    }
}

@Composable private fun DebtCalc(c: CalcContext) {
    var extra by rememberSaveable { mutableStateOf("") }
    if (c.debts.isEmpty()) { Text("Add your debts under Debts and loans first, then compare payoff strategies here."); return }
    MoneyField(extra, { extra = it }, "Extra you can pay each month", currency = c.base)
    val e = money(extra.ifBlank { "0" }, c.base)
    if (e != null) {
        val snow = DebtPayoff.simulate(c.debts, e, DebtStrategy.SNOWBALL); val aval = DebtPayoff.simulate(c.debts, e, DebtStrategy.AVALANCHE)
        listOf(DebtStrategy.SNOWBALL to snow, DebtStrategy.AVALANCHE to aval).forEach { (s, r) ->
            InfoCard {
                SectionTitle(s.label)
                if (!r.completed) Text("Not paid off within 50 years at these payments. Increase the amount.", color = MaterialTheme.colorScheme.error) else {
                    StatRow("Debt-free in", "${r.months / 12} yr ${r.months % 12} mo"); MoneyStatRow("Total interest", r.totalInterestMinor, c.base); StatRow("Payoff order", r.payoffOrder.joinToString(" → "))
                }
            }
        }
        if (snow.completed && aval.completed) Note("Avalanche saves ${MoneyFormatter.format((snow.totalInterestMinor - aval.totalInterestMinor).coerceAtLeast(0), c.base)} in interest. Snowball clears small debts sooner, which some people find more motivating.")
        Note("Uses your recorded debts and minimum payments. Interest accrues monthly at rate ÷ 12.")
    }
}

@Composable private fun AffordCalc(c: CalcContext) {
    var price by rememberSaveable { mutableStateOf("") }
    MoneyField(price, { price = it }, "Purchase amount", currency = c.base)
    val p = money(price, c.base)
    if (p != null && p > 0) {
        val r = AffordabilityMath.evaluate(p, c.budgetRemainingMinor ?: Long.MAX_VALUE / 4, c.balanceMinor, c.upcoming30Minor)
        InfoCard {
            MoneyStatRow("Your balance now", c.balanceMinor, c.base); MoneyStatRow("Bills and payments due in 30 days", c.upcoming30Minor, c.base)
            MoneyStatRow("Balance after purchase and those payments", r.balanceAfterMinor, c.base, if (r.balanceStaysPositive) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.error)
            if (c.budgetRemainingMinor != null) MoneyStatRow("Left in your budget afterwards", r.remainingBudgetAfterMinor, c.base, if (r.withinBudget) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.error)
            Text(if (r.balanceStaysPositive && r.withinBudget) "This fits within your balance${if (c.budgetRemainingMinor != null) " and budget" else ""}." else if (!r.balanceStaysPositive) "This would take your balance below zero after upcoming payments." else "This would put you over your current budget.", style = MaterialTheme.typography.titleSmall)
            Note("Based on recorded balances and scheduled payments only.")
        }
    }
}

@Composable private fun BudgetPlanCalc(c: CalcContext) {
    var income by rememberSaveable { mutableStateOf("") }; var needs by rememberSaveable { mutableStateOf("50") }; var wants by rememberSaveable { mutableStateOf("30") }; var save by rememberSaveable { mutableStateOf("20") }
    MoneyField(income, { income = it }, "Monthly income", currency = c.base)
    NumberField(needs, { needs = it }, "Needs", suffix = "%"); NumberField(wants, { wants = it }, "Wants", suffix = "%"); NumberField(save, { save = it }, "Savings and debt repayment", suffix = "%")
    val inc = money(income, c.base); val w = listOf(needs, wants, save).map { it.toDoubleOrNull()?.let { v -> (v * 100).toInt() } }
    if (inc != null && inc > 0 && w.none { it == null }) {
        if (w.sumOf { it!! } != 10_000) Text("The three shares add up to ${w.sumOf { it!! } / 100.0}%, not 100%.", color = MaterialTheme.colorScheme.error)
        else {
            val parts = BudgetAllocator.allocate(inc, w.map { it!! })
            InfoCard { listOf("Needs", "Wants", "Savings and debt repayment").forEachIndexed { i, n -> MoneyStatRow(n, parts[i], c.base) }; Note("The common 50/30/20 split is a starting point, not a rule.") }
        }
    }
}

@Composable private fun TaxCalc(c: CalcContext) {
    var gross by rememberSaveable { mutableStateOf("") }; var deductions by rememberSaveable { mutableStateOf("") }
    var regime by rememberSaveable { mutableStateOf(TaxRegime.NEW) }; var year by rememberSaveable { mutableStateOf(c.fyStartYear.takeIf { it in IndianTaxRules.supportedYears } ?: IndianTaxRules.supportedYears.first()) }; var salaried by rememberSaveable { mutableStateOf(true) }
    val inr = CurrencyInfo.INR
    MoneyField(gross, { gross = it }, "Annual gross income", currency = inr)
    ChipSelect("Financial year", IndianTaxRules.supportedYears, year, { year = it }) { "FY $it-${(it + 1) % 100}" }
    ChipSelect("Regime", TaxRegime.entries, regime, { regime = it }) { it.label }
    Row { Text("Salaried (standard deduction)", Modifier.weight(1f)); Switch(salaried, { salaried = it }) }
    if (regime == TaxRegime.OLD) MoneyField(deductions, { deductions = it }, "Deductions (80C, 80D, HRA, etc.)", currency = inr)
    val g = money(gross, inr); val d = money(deductions.ifBlank { "0" }, inr); val rules = IndianTaxRules.rules(year, regime)
    if (g != null && d != null && rules != null) {
        val r = TaxEstimator.estimate(rules, g, d, salaried)
        InfoCard {
            SectionTitle("${rules.fyLabel} · ${regime.label}")
            MoneyStatRow("Taxable income", r.taxableIncomeMinor, inr)
            r.slabRows.filter { it.taxMinor > 0 || it.rateBps > 0 }.forEach { s ->
                StatRow("${MoneyFormatter.format(s.fromMinor, inr)} – ${s.toMinor?.let { MoneyFormatter.format(it, inr) } ?: "above"} @ ${BigDecimal(s.rateBps).movePointLeft(2).toPlainString()}%", MoneyFormatter.format(s.taxMinor, inr))
            }
            MoneyStatRow("Tax before rebate", r.taxBeforeRebateMinor, inr)
            if (r.rebateMinor > 0) MoneyStatRow("Rebate (Section 87A)", -r.rebateMinor, inr)
            if (r.marginalReliefMinor > 0) MoneyStatRow("Marginal relief", -r.marginalReliefMinor, inr)
            MoneyStatRow("Health and education cess (4%)", r.cessMinor, inr)
            MoneyStatRow("Estimated tax", r.totalTaxMinor, inr, MaterialTheme.colorScheme.primary)
            StatRow("Effective rate", "${r.effectiveRateBps / 100.0}%")
        }
        Note("Estimate for a resident individual below 60. Surcharge, senior-citizen slabs, capital-gains and other special-rate income, TDS and employer-specific items are not modelled. Rules change every Budget; verify with the Income Tax Department or a tax professional before filing.")
    }
}
