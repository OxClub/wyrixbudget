package com.wyrix.budget.domain.usecase

import com.wyrix.budget.core.money.MAX_AMOUNT_MINOR
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.domain.calc.AllocationInput
import com.wyrix.budget.domain.calc.BudgetCalculator
import com.wyrix.budget.domain.calc.Frequency
import com.wyrix.budget.domain.calc.LoanMath
import com.wyrix.budget.domain.calc.Recurrence
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private fun reject(vararg messages: String) = ActionResult.Rejected(messages.toList())
private fun LocalDate.atNoon(): Long = atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun amountProblem(minor: Long, what: String = "Amount"): String? =
    if (minor <= 0) "$what must be greater than zero." else if (minor > MAX_AMOUNT_MINOR) "$what is too large." else null

// ---- budgets ------------------------------------------------------------------------------

class SaveBudgetUseCase @Inject constructor(private val repo: BudgetRepository, private val clock: Clock) {
    suspend operator fun invoke(budget: Budget, allocations: List<AllocationInput>): ActionResult<Long> {
        val errors = mutableListOf<String>()
        val name = budget.name.trim()
        if (name.isEmpty()) errors += "Enter a budget name."
        if (name.length > 40) errors += "Budget name must be at most 40 characters."
        amountProblem(budget.totalMinor, "Total budget")?.let { errors += it }
        if (budget.endDate.isBefore(budget.startDate)) errors += "End date must not be before the start date."
        if (allocations.any { it.amountMinor <= 0 }) errors += "Each category amount must be greater than zero."
        if (allocations.map { it.categoryId }.toSet().size != allocations.size) errors += "A category can only be added once."
        if (allocations.sumOf { it.amountMinor } > budget.totalMinor) errors += "Category amounts add up to more than the total budget."
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)

        val thresholds = budget.thresholds.ifEmpty { BudgetCalculator.DEFAULT_THRESHOLDS }
        val clean = budget.copy(
            name = name, thresholds = thresholds,
            createdAt = if (budget.id == 0L) clock.millis() else budget.createdAt,
        )
        return ActionResult.Success(repo.save(clean, allocations))
    }
}

// ---- savings goals ------------------------------------------------------------------------

class SaveGoalUseCase @Inject constructor(private val repo: GoalRepository, private val clock: Clock) {
    suspend operator fun invoke(goal: SavingsGoal): ActionResult<Long> {
        val errors = mutableListOf<String>()
        val name = goal.name.trim()
        if (name.isEmpty()) errors += "Enter a goal name."
        if (name.length > 60) errors += "Goal name must be at most 60 characters."
        amountProblem(goal.targetMinor, "Target amount")?.let { errors += it }
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)
        return ActionResult.Success(
            repo.save(goal.copy(name = name, description = goal.description.trim(), createdAt = if (goal.id == 0L) clock.millis() else goal.createdAt)),
        )
    }
}

/** Adds (positive) or withdraws (negative) savings. Moves nothing between accounts, so it is never income or expense. */
class AddContributionUseCase @Inject constructor(private val repo: GoalRepository) {
    suspend operator fun invoke(goal: GoalWithSaved, amountMinor: Long, accountId: Long?, occurredAt: Long, note: String): ActionResult<Long> {
        if (amountMinor == 0L) return reject("Enter an amount.")
        if (kotlin.math.abs(amountMinor) > MAX_AMOUNT_MINOR) return reject("That amount is too large.")
        if (goal.savedMinor + amountMinor < 0) {
            return reject("You can't withdraw more than the ${MoneyFormatter.format(goal.savedMinor)} saved so far.")
        }
        val id = repo.addContribution(GoalContribution(goalId = goal.goal.id, amountMinor = amountMinor, accountId = accountId, occurredAt = occurredAt, note = note.trim()))
        val newSaved = goal.savedMinor + amountMinor
        if (goal.goal.status == GoalStatus.ACTIVE && newSaved >= goal.goal.targetMinor) {
            repo.save(goal.goal.copy(status = GoalStatus.COMPLETED))
        } else if (goal.goal.status == GoalStatus.COMPLETED && newSaved < goal.goal.targetMinor) {
            repo.save(goal.goal.copy(status = GoalStatus.ACTIVE))
        }
        return ActionResult.Success(id)
    }
}

// ---- bills --------------------------------------------------------------------------------

class SaveBillUseCase @Inject constructor(private val repo: BillRepository, private val clock: Clock) {
    suspend operator fun invoke(bill: Bill): ActionResult<Long> {
        val errors = mutableListOf<String>()
        if (bill.name.trim().isEmpty()) errors += "Enter a bill name."
        if (bill.name.trim().length > 60) errors += "Bill name must be at most 60 characters."
        amountProblem(bill.amountMinor)?.let { errors += it }
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)
        return ActionResult.Success(repo.save(bill.copy(name = bill.name.trim(), createdAt = if (bill.id == 0L) clock.millis() else bill.createdAt)))
    }
}

/** Marks the bill's current due date paid, optionally recording the expense, then moves the bill to its next due date. */
class PayBillUseCase @Inject constructor(
    private val repo: BillRepository,
    private val saveTransaction: SaveTransactionUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(billId: Long, createExpense: Boolean, accountId: Long?, paidOn: LocalDate): ActionResult<Unit> {
        val bill = repo.get(billId) ?: return reject("This bill no longer exists.")
        if (!bill.isActive) return reject("This bill is already marked as paid.")
        val due = bill.nextDueDate
        if (repo.hasPayment(billId, due)) return reject("This due date is already marked as paid.")

        var transactionId: Long? = null
        if (createExpense) {
            val account = accountId ?: bill.accountId ?: return reject("Choose the account the bill was paid from.")
            val category = bill.categoryId ?: return reject("Give this bill a category first, or mark it paid without recording an expense.")
            val result = saveTransaction(
                Transaction(
                    type = TransactionType.EXPENSE, amountMinor = bill.amountMinor, title = bill.name,
                    categoryId = category, accountId = account, occurredAt = paidOn.atNoon(), notes = "Bill payment",
                ),
            )
            when (result) {
                is ActionResult.Success -> transactionId = result.value
                is ActionResult.Rejected -> return result
            }
        }
        val next = Recurrence.nextAfter(bill.startDate, bill.frequency, 1, due)
        val ok = repo.recordPayment(BillPayment(billId = billId, dueDate = due, paidAt = clock.millis(), amountMinor = bill.amountMinor, transactionId = transactionId), next)
        return if (ok) ActionResult.Success(Unit) else reject("This due date is already marked as paid.")
    }
}

// ---- subscriptions ------------------------------------------------------------------------

class SaveSubscriptionUseCase @Inject constructor(private val repo: SubscriptionRepository, private val clock: Clock) {
    suspend operator fun invoke(s: Subscription): ActionResult<Long> {
        val errors = mutableListOf<String>()
        if (s.name.trim().isEmpty()) errors += "Enter a subscription name."
        amountProblem(s.amountMinor)?.let { errors += it }
        if (s.cycle == Frequency.ONCE || s.cycle == Frequency.CUSTOM) errors += "Choose a billing cycle."
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)
        return ActionResult.Success(repo.save(s.copy(name = s.name.trim(), createdAt = if (s.id == 0L) clock.millis() else s.createdAt)))
    }
}

class RenewSubscriptionUseCase @Inject constructor(
    private val repo: SubscriptionRepository,
    private val saveTransaction: SaveTransactionUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long, createExpense: Boolean, accountId: Long?, paidOn: LocalDate): ActionResult<Unit> {
        val sub = repo.get(id) ?: return reject("This subscription no longer exists.")
        if (sub.status != SubscriptionStatus.ACTIVE) return reject("Only active subscriptions can be renewed.")
        val due = sub.renewalDate
        if (repo.hasRenewal(id, due)) return reject("This renewal is already recorded.")

        var transactionId: Long? = null
        if (createExpense) {
            val account = accountId ?: sub.accountId ?: return reject("Choose the account it was paid from.")
            val category = sub.categoryId ?: return reject("Give this subscription a category first, or renew without recording an expense.")
            val result = saveTransaction(
                Transaction(
                    type = TransactionType.EXPENSE, amountMinor = sub.amountMinor, title = sub.name,
                    categoryId = category, accountId = account, occurredAt = paidOn.atNoon(), notes = "Subscription renewal",
                ),
            )
            when (result) {
                is ActionResult.Success -> transactionId = result.value
                is ActionResult.Rejected -> return result
            }
        }
        val next = Recurrence.nextAfter(sub.startDate, sub.cycle, 1, due) ?: return reject("Couldn't work out the next renewal date.")
        val ok = repo.recordRenewal(SubscriptionPayment(subscriptionId = id, dueDate = due, paidAt = clock.millis(), amountMinor = sub.amountMinor, transactionId = transactionId), next)
        return if (ok) ActionResult.Success(Unit) else reject("This renewal is already recorded.")
    }
}

// ---- debts --------------------------------------------------------------------------------

class SaveLoanUseCase @Inject constructor(private val repo: LoanRepository, private val clock: Clock) {
    suspend operator fun invoke(loan: Loan): ActionResult<Long> {
        val errors = mutableListOf<String>()
        if (loan.lender.trim().isEmpty()) errors += "Enter the lender or person's name."
        amountProblem(loan.principalMinor, "Original amount")?.let { errors += it }
        if (loan.outstandingStartMinor < 0 || loan.outstandingStartMinor > MAX_AMOUNT_MINOR) errors += "Outstanding amount is out of range."
        if (loan.outstandingStartMinor > loan.principalMinor && loan.type != LoanType.CREDIT_CARD) errors += "Outstanding amount can't exceed the original amount."
        if (loan.rateBps !in 0..10_000) errors += "Interest rate must be between 0% and 100%."
        if (loan.emiMinor < 0 || loan.emiMinor > MAX_AMOUNT_MINOR) errors += "Payment amount is out of range."
        if (loan.endDate != null && loan.endDate.isBefore(loan.startDate)) errors += "End date must not be before the start date."
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)
        return ActionResult.Success(repo.save(loan.copy(lender = loan.lender.trim(), createdAt = if (loan.id == 0L) clock.millis() else loan.createdAt)))
    }
}

/**
 * Records a repayment. Interest for the period is outstanding x annual rate / 12 (assumes monthly
 * instalments); the rest of the payment reduces the principal. Optionally records an expense too.
 */
class RecordLoanPaymentUseCase @Inject constructor(
    private val repo: LoanRepository,
    private val categories: CategoryRepository,
    private val saveTransaction: SaveTransactionUseCase,
) {
    suspend operator fun invoke(loanId: Long, amountMinor: Long, paidOn: LocalDate, createExpense: Boolean, accountId: Long?): ActionResult<Unit> {
        val row = repo.get(loanId) ?: return reject("This loan no longer exists.")
        val loan = row.loan
        amountProblem(amountMinor, "Payment")?.let { return reject(it) }
        val interest = LoanMath.monthlyInterest(row.outstandingMinor, loan.rateBps)
        val maxPayable = row.outstandingMinor + interest
        if (amountMinor > maxPayable) return reject("That is more than the outstanding amount plus interest (${MoneyFormatter.format(maxPayable)}).")

        var transactionId: Long? = null
        if (createExpense) {
            val account = accountId ?: loan.accountId ?: return reject("Choose the account the payment was made from.")
            val category = categories.findByName(CategoryKind.EXPENSE, "Loan payments")
                ?: return reject("The \"Loan payments\" category is missing. Record without an expense, or recreate the category.")
            val result = saveTransaction(
                Transaction(
                    type = TransactionType.EXPENSE, amountMinor = amountMinor, title = "${loan.lender} payment",
                    categoryId = category.id, accountId = account, occurredAt = paidOn.atNoon(), notes = "Loan repayment",
                ),
            )
            when (result) {
                is ActionResult.Success -> transactionId = result.value
                is ActionResult.Rejected -> return result
            }
        }
        val interestPart = minOf(amountMinor, interest)
        val principalPart = amountMinor - interestPart
        val closes = row.outstandingMinor - principalPart <= 0
        val next = if (closes) null else Recurrence.nextAfter(loan.startDate, Frequency.MONTHLY, 1, loan.nextPaymentDate)
        repo.recordPayment(
            LoanPayment(loanId = loanId, paidDate = paidOn, amountMinor = amountMinor, principalPartMinor = principalPart, interestPartMinor = interestPart, transactionId = transactionId),
            next, closes,
        )
        return ActionResult.Success(Unit)
    }
}

// ---- recurring ----------------------------------------------------------------------------

class SaveRecurringRuleUseCase @Inject constructor(private val repo: RecurringRepository, private val clock: Clock) {
    suspend operator fun invoke(rule: RecurringRule): ActionResult<Long> {
        val errors = mutableListOf<String>()
        if (rule.title.trim().isEmpty()) errors += "Enter a title."
        amountProblem(rule.amountMinor)?.let { errors += it }
        if (rule.type != TransactionType.INCOME && rule.type != TransactionType.EXPENSE) errors += "Choose income or expense."
        if (rule.frequency == Frequency.ONCE) errors += "Choose how often it repeats."
        if (rule.frequency == Frequency.CUSTOM && rule.customDays < 1) errors += "Enter the number of days between occurrences."
        if (rule.endDate != null && rule.endDate.isBefore(rule.startDate)) errors += "End date must not be before the start date."
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)
        return ActionResult.Success(repo.save(rule.copy(title = rule.title.trim(), createdAt = if (rule.id == 0L) clock.millis() else rule.createdAt)))
    }
}

/** Recurring items are never recorded automatically: the user confirms or skips each due occurrence. */
class HandleRecurringUseCase @Inject constructor(
    private val repo: RecurringRepository,
    private val saveTransaction: SaveTransactionUseCase,
    private val clock: Clock,
) {
    suspend fun confirm(ruleId: Long, dueDate: LocalDate): ActionResult<Unit> = handle(ruleId, dueDate, record = true)
    suspend fun skip(ruleId: Long, dueDate: LocalDate): ActionResult<Unit> = handle(ruleId, dueDate, record = false)

    private suspend fun handle(ruleId: Long, dueDate: LocalDate, record: Boolean): ActionResult<Unit> {
        val rule = repo.get(ruleId) ?: return reject("This recurring item no longer exists.")
        if (!rule.isActive) return reject("This recurring item is inactive.")
        if (dueDate != rule.nextDate) return reject("Handle the earliest due occurrence first.")
        if (repo.hasEntry(ruleId, dueDate)) return reject("This occurrence has already been handled.")

        var transactionId: Long? = null
        if (record) {
            val result = saveTransaction(
                Transaction(
                    type = rule.type, amountMinor = rule.amountMinor, title = rule.title,
                    categoryId = rule.categoryId, accountId = rule.accountId,
                    occurredAt = dueDate.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    notes = "Recurring",
                ),
            )
            when (result) {
                is ActionResult.Success -> transactionId = result.value
                is ActionResult.Rejected -> return result
            }
        }
        var next = Recurrence.nextAfter(rule.startDate, rule.frequency, rule.customDays, dueDate)
        if (next != null && rule.endDate != null && next.isAfter(rule.endDate)) next = null
        val ok = repo.logEntry(
            RecurringEntry(ruleId = ruleId, dueDate = dueDate, status = if (record) EntryStatus.CONFIRMED else EntryStatus.SKIPPED, transactionId = transactionId, handledAt = clock.millis()),
            next,
        )
        return if (ok) ActionResult.Success(Unit) else reject("This occurrence has already been handled.")
    }
}
