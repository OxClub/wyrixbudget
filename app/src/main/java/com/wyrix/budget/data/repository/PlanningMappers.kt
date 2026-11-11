package com.wyrix.budget.data.repository

import com.wyrix.budget.data.local.entity.*
import com.wyrix.budget.domain.calc.BudgetCalculator
import com.wyrix.budget.domain.model.*
import java.time.LocalDate

internal fun Long.toDate(): LocalDate = LocalDate.ofEpochDay(this)
internal fun LocalDate.toDay(): Long = toEpochDay()
internal fun List<Int>.toCsv(): String = joinToString(",")
internal fun String.toDays(): List<Int> = split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }

internal fun BudgetEntity.toDomain() = Budget(id, name, periodType, startDate.toDate(), endDate.toDate(), totalMinor, BudgetCalculator.parseThresholds(thresholds), countOnlyAllocated, createdAt)
internal fun Budget.toEntity() = BudgetEntity(id, name, periodType, startDate.toDay(), endDate.toDay(), totalMinor, thresholds.toCsv(), countOnlyAllocated, createdAt)

internal fun SavingsGoalEntity.toDomain() = SavingsGoal(id, name, targetMinor, targetDate?.toDate(), priority, description, status, createdAt)
internal fun SavingsGoal.toEntity() = SavingsGoalEntity(id, name, targetMinor, targetDate?.toDay(), priority, description, status, createdAt)
internal fun SavingsContributionEntity.toDomain() = GoalContribution(id, goalId, amountMinor, accountId, occurredAt, note)
internal fun GoalContribution.toEntity() = SavingsContributionEntity(id, goalId, amountMinor, accountId, occurredAt, note)

internal fun BillEntity.toDomain() = Bill(id, name, amountMinor, categoryId, accountId, startDate.toDate(), nextDueDate.toDate(), frequency, reminderDays.toDays(), isActive, createdAt)
internal fun Bill.toEntity() = BillEntity(id, name, amountMinor, categoryId, accountId, startDate.toDay(), nextDueDate.toDay(), frequency, reminderDays.toCsv(), isActive, createdAt)
internal fun BillPaymentEntity.toDomain() = BillPayment(id, billId, dueDate.toDate(), paidAt, amountMinor, transactionId)
internal fun BillPayment.toEntity() = BillPaymentEntity(id, billId, dueDate.toDay(), paidAt, amountMinor, transactionId)

internal fun SubscriptionEntity.toDomain() = Subscription(id, name, amountMinor, cycle, startDate.toDate(), renewalDate.toDate(), accountId, categoryId, status, reminderDays.toDays(), createdAt)
internal fun Subscription.toEntity() = SubscriptionEntity(id, name, amountMinor, cycle, startDate.toDay(), renewalDate.toDay(), accountId, categoryId, status, reminderDays.toCsv(), createdAt)
internal fun SubscriptionPaymentEntity.toDomain() = SubscriptionPayment(id, subscriptionId, dueDate.toDate(), paidAt, amountMinor, transactionId)
internal fun SubscriptionPayment.toEntity() = SubscriptionPaymentEntity(id, subscriptionId, dueDate.toDay(), paidAt, amountMinor, transactionId)

internal fun LoanEntity.toDomain() = Loan(id, lender, type, principalMinor, outstandingStartMinor, rateBps, emiMinor, frequency, startDate.toDate(), endDate?.toDate(), nextPaymentDate.toDate(), accountId, isActive, createdAt)
internal fun Loan.toEntity() = LoanEntity(id, lender, type, principalMinor, outstandingStartMinor, rateBps, emiMinor, frequency, startDate.toDay(), endDate?.toDay(), nextPaymentDate.toDay(), accountId, isActive, createdAt)
internal fun LoanRow.toDomain() = LoanWithOutstanding(loan.toDomain(), outstandingMinor, loan.outstandingStartMinor - outstandingMinor)
internal fun LoanPaymentEntity.toDomain() = LoanPayment(id, loanId, paidDate.toDate(), amountMinor, principalPartMinor, interestPartMinor, transactionId)
internal fun LoanPayment.toEntity() = LoanPaymentEntity(id, loanId, paidDate.toDay(), amountMinor, principalPartMinor, interestPartMinor, transactionId)

internal fun RecurringRuleEntity.toDomain() = RecurringRule(id, title, type, amountMinor, categoryId, accountId, frequency, customDays, startDate.toDate(), nextDate.toDate(), endDate?.toDate(), isActive, createdAt)
internal fun RecurringRule.toEntity() = RecurringRuleEntity(id, title, type, amountMinor, categoryId, accountId, frequency, customDays, startDate.toDay(), nextDate.toDay(), endDate?.toDay(), isActive, createdAt)
internal fun RecurringEntryEntity.toDomain() = RecurringEntry(id, ruleId, dueDate.toDate(), status, transactionId, handledAt)
internal fun RecurringEntry.toEntity() = RecurringEntryEntity(id, ruleId, dueDate.toDay(), status, transactionId, handledAt)

internal fun AttachmentEntity.toDomain() = Attachment(id, transactionId, filePath, mimeType, createdAt)
internal fun Attachment.toEntity() = AttachmentEntity(id, transactionId, filePath, mimeType, createdAt)
internal fun ExchangeRateEntity.toDomain() = ExchangeRate(currencyCode, rateToBase, asOf, source)
