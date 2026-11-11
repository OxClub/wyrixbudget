package com.wyrix.budget.data.backup

import com.google.gson.Gson
import com.wyrix.budget.data.local.entity.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String) : Exception(message)
class WrongPasswordException : Exception("Wrong password or the file is damaged")

/** Everything needed to rebuild the database. Receipt image files are not included. */
data class BackupSnapshot(
    val formatVersion: Int,
    val schemaVersion: Int,
    val createdAt: Long,
    val accounts: List<AccountEntity>?,
    val categories: List<CategoryEntity>?,
    val transactions: List<TransactionEntity>?,
    val budgets: List<BudgetEntity>?,
    val allocations: List<BudgetAllocationEntity>?,
    val goals: List<SavingsGoalEntity>?,
    val contributions: List<SavingsContributionEntity>?,
    val bills: List<BillEntity>?,
    val billPayments: List<BillPaymentEntity>?,
    val subscriptions: List<SubscriptionEntity>?,
    val subscriptionPayments: List<SubscriptionPaymentEntity>?,
    val loans: List<LoanEntity>?,
    val loanPayments: List<LoanPaymentEntity>?,
    val rules: List<RecurringRuleEntity>?,
    val entries: List<RecurringEntryEntity>?,
    val rates: List<ExchangeRateEntity>?,
) {
    fun counts(): Map<String, Int> = linkedMapOf(
        "Accounts" to (accounts?.size ?: 0), "Categories" to (categories?.size ?: 0),
        "Transactions" to (transactions?.size ?: 0), "Budgets" to (budgets?.size ?: 0),
        "Savings goals" to (goals?.size ?: 0), "Bills" to (bills?.size ?: 0),
        "Subscriptions" to (subscriptions?.size ?: 0), "Loans" to (loans?.size ?: 0),
        "Recurring items" to (rules?.size ?: 0),
    )
}

/**
 * File layout:
 *  plain:     "WYRIXBK1" 0x00 [json] [sha256(json)]
 *  encrypted: "WYRIXBK1" 0x01 [salt:16][iv:12][AES-256-GCM(json)]  (key = PBKDF2-HMAC-SHA256, 210k iterations)
 */
object BackupCodec {
    const val FORMAT_VERSION = 1
    const val SCHEMA_VERSION = 2
    private val MAGIC = "WYRIXBK1".toByteArray(Charsets.US_ASCII)
    private const val PBKDF2_ITERATIONS = 210_000
    private val gson = Gson()

    fun isEncrypted(file: ByteArray): Boolean = file.size > MAGIC.size && file[MAGIC.size] == 1.toByte()

    fun encode(snapshot: BackupSnapshot, password: CharArray?): ByteArray {
        val json = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        if (password == null || password.isEmpty()) {
            out.write(0)
            out.write(json)
            out.write(MessageDigest.getInstance("SHA-256").digest(json))
        } else {
            out.write(1)
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
            out.write(salt)
            out.write(iv)
            out.write(cipher.doFinal(json))
        }
        return out.toByteArray()
    }

    fun decode(file: ByteArray, password: CharArray?): BackupSnapshot {
        if (file.size < MAGIC.size + 2 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("This is not a Budget backup file.")
        }
        val json: ByteArray = when (file[MAGIC.size].toInt()) {
            0 -> {
                val body = file.copyOfRange(MAGIC.size + 1, file.size - 32)
                val digest = file.copyOfRange(file.size - 32, file.size)
                if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(body))) {
                    throw BackupException("The backup file is damaged (checksum mismatch).")
                }
                body
            }
            1 -> {
                if (password == null || password.isEmpty()) throw WrongPasswordException()
                val buf = ByteBuffer.wrap(file, MAGIC.size + 1, file.size - MAGIC.size - 1)
                val salt = ByteArray(16).also { buf.get(it) }
                val iv = ByteArray(12).also { buf.get(it) }
                val cipherText = ByteArray(buf.remaining()).also { buf.get(it) }
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
                    cipher.doFinal(cipherText)
                } catch (e: AEADBadTagException) {
                    throw WrongPasswordException()
                }
            }
            else -> throw BackupException("Unknown backup format.")
        }
        val snapshot = try {
            gson.fromJson(String(json, Charsets.UTF_8), BackupSnapshot::class.java)
        } catch (e: Exception) {
            throw BackupException("The backup file could not be read.")
        } ?: throw BackupException("The backup file is empty.")
        validate(snapshot)
        return snapshot
    }

    /** Rejects files that are from a newer app, incomplete, or internally inconsistent. */
    fun validate(s: BackupSnapshot) {
        if (s.formatVersion != FORMAT_VERSION) throw BackupException("This backup uses an unsupported format version.")
        if (s.schemaVersion > SCHEMA_VERSION) throw BackupException("This backup was made by a newer version of Budget. Update the app first.")
        val accounts = s.accounts ?: throw BackupException("The backup has no accounts section.")
        val categories = s.categories ?: throw BackupException("The backup has no categories section.")
        val transactions = s.transactions ?: throw BackupException("The backup has no transactions section.")
        val accountIds = accounts.map { it.id }.toSet()
        val categoryIds = categories.map { it.id }.toSet()
        if (accountIds.size != accounts.size || categoryIds.size != categories.size) throw BackupException("The backup has duplicate records.")
        for (t in transactions) {
            if (t.accountId !in accountIds || (t.transferAccountId != null && t.transferAccountId !in accountIds) ||
                (t.categoryId != null && t.categoryId !in categoryIds)
            ) throw BackupException("The backup refers to accounts or categories that are missing from it.")
        }
        if (transactions.map { it.id }.toSet().size != transactions.size) throw BackupException("The backup has duplicate transactions.")
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)).encoded
        return SecretKeySpec(key, "AES")
    }
}
