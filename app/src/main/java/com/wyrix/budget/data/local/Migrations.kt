package com.wyrix.budget.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 -> 2: adds currency/base-amount/payment-method/GST columns to transactions and creates the budget,
 * goal, bill, subscription, loan, recurring, attachment and exchange-rate tables. Existing rows are
 * preserved; existing transactions keep their amount as the base amount (they were all INR).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `currencyCode` TEXT NOT NULL DEFAULT 'INR'")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `baseAmountMinor` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `exchangeRate` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `rateDate` INTEGER")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `paymentMethod` TEXT NOT NULL DEFAULT 'OTHER'")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `gstTagged` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `gstAmountMinor` INTEGER")
        db.execSQL("UPDATE `transactions` SET `baseAmountMinor` = `amountMinor`")

        db.execSQL("CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `periodType` TEXT NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER NOT NULL, `totalMinor` INTEGER NOT NULL, `thresholds` TEXT NOT NULL, `countOnlyAllocated` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_startDate` ON `budgets` (`startDate`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `budget_allocations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `budgetId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, FOREIGN KEY(`budgetId`) REFERENCES `budgets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_allocations_budgetId_categoryId` ON `budget_allocations` (`budgetId`, `categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_allocations_categoryId` ON `budget_allocations` (`categoryId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `savings_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `targetMinor` INTEGER NOT NULL, `targetDate` INTEGER, `priority` TEXT NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `savings_contributions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goalId` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `accountId` INTEGER, `occurredAt` INTEGER NOT NULL, `note` TEXT NOT NULL, FOREIGN KEY(`goalId`) REFERENCES `savings_goals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_contributions_goalId` ON `savings_contributions` (`goalId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_savings_contributions_accountId` ON `savings_contributions` (`accountId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `bills` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `categoryId` INTEGER, `accountId` INTEGER, `startDate` INTEGER NOT NULL, `nextDueDate` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `reminderDays` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_categoryId` ON `bills` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_accountId` ON `bills` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bills_nextDueDate` ON `bills` (`nextDueDate`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `bill_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `billId` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `paidAt` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `transactionId` INTEGER, FOREIGN KEY(`billId`) REFERENCES `bills`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_payments_billId_dueDate` ON `bill_payments` (`billId`, `dueDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_payments_transactionId` ON `bill_payments` (`transactionId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `subscriptions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `cycle` TEXT NOT NULL, `startDate` INTEGER NOT NULL, `renewalDate` INTEGER NOT NULL, `accountId` INTEGER, `categoryId` INTEGER, `status` TEXT NOT NULL, `reminderDays` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_accountId` ON `subscriptions` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscriptions_categoryId` ON `subscriptions` (`categoryId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `subscription_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `subscriptionId` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `paidAt` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `transactionId` INTEGER, FOREIGN KEY(`subscriptionId`) REFERENCES `subscriptions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_subscription_payments_subscriptionId_dueDate` ON `subscription_payments` (`subscriptionId`, `dueDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_payments_transactionId` ON `subscription_payments` (`transactionId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `loans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lender` TEXT NOT NULL, `type` TEXT NOT NULL, `principalMinor` INTEGER NOT NULL, `outstandingStartMinor` INTEGER NOT NULL, `rateBps` INTEGER NOT NULL, `emiMinor` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER, `nextPaymentDate` INTEGER NOT NULL, `accountId` INTEGER, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loans_accountId` ON `loans` (`accountId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `loan_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `loanId` INTEGER NOT NULL, `paidDate` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `principalPartMinor` INTEGER NOT NULL, `interestPartMinor` INTEGER NOT NULL, `transactionId` INTEGER, FOREIGN KEY(`loanId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_payments_loanId` ON `loan_payments` (`loanId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_payments_transactionId` ON `loan_payments` (`transactionId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `type` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `accountId` INTEGER NOT NULL, `frequency` TEXT NOT NULL, `customDays` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `nextDate` INTEGER NOT NULL, `endDate` INTEGER, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_categoryId` ON `recurring_rules` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_accountId` ON `recurring_rules` (`accountId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ruleId` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `status` TEXT NOT NULL, `transactionId` INTEGER, `handledAt` INTEGER NOT NULL, FOREIGN KEY(`ruleId`) REFERENCES `recurring_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_entries_ruleId_dueDate` ON `recurring_entries` (`ruleId`, `dueDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_entries_transactionId` ON `recurring_entries` (`transactionId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transactionId` INTEGER NOT NULL, `filePath` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_transactionId` ON `attachments` (`transactionId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `exchange_rates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `currencyCode` TEXT NOT NULL, `rateToBase` TEXT NOT NULL, `asOf` INTEGER NOT NULL, `source` TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exchange_rates_currencyCode` ON `exchange_rates` (`currencyCode`)")
    }
}
