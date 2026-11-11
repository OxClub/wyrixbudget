package com.wyrix.budget.data

import com.wyrix.budget.data.backup.BackupCodec
import com.wyrix.budget.data.backup.BackupException
import com.wyrix.budget.data.backup.BackupSnapshot
import com.wyrix.budget.data.backup.WrongPasswordException
import com.wyrix.budget.data.local.entity.AccountEntity
import com.wyrix.budget.data.local.entity.CategoryEntity
import com.wyrix.budget.data.local.entity.TransactionEntity
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.PaymentMethod
import com.wyrix.budget.domain.model.TransactionType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private val account = AccountEntity(1, "Bank", AccountType.BANK, "INR", 500_000, "", 0, false, 0, 1)
    private val category = CategoryEntity(1, "Food", CategoryKind.EXPENSE, null, 0, true)
    private fun tx(id: Long, account: Long = 1, category: Long? = 1) = TransactionEntity(
        id, TransactionType.EXPENSE, 12_345, "Lunch ₹ \"quoted\", with commas", category, account, null, 1_000, "Café", "line1\nline2", 1, 1,
        "INR", 12_345, null, null, PaymentMethod.UPI, true, 600,
    )
    private fun snapshot(vararg txs: TransactionEntity, schema: Int = BackupCodec.SCHEMA_VERSION, format: Int = BackupCodec.FORMAT_VERSION) = BackupSnapshot(
        format, schema, 99, listOf(account), listOf(category), txs.toList(), null, null, null, null, null, null, null, null, null, null, null, null, null,
    )

    @Test fun plainRoundTripKeepsEveryField() {
        val original = snapshot(tx(1), tx(2))
        val decoded = BackupCodec.decode(BackupCodec.encode(original, null), null)
        assertEquals(original.transactions, decoded.transactions)
        assertEquals(original.accounts, decoded.accounts)
        assertEquals(mapOf("Accounts" to 1, "Categories" to 1, "Transactions" to 2), decoded.counts().filterValues { it > 0 })
    }

    @Test fun encryptedRoundTripNeedsTheRightPassword() {
        val bytes = BackupCodec.encode(snapshot(tx(1)), "correct horse".toCharArray())
        assertTrue(BackupCodec.isEncrypted(bytes))
        assertEquals(1, BackupCodec.decode(bytes, "correct horse".toCharArray()).transactions!!.size)
        try { BackupCodec.decode(bytes, "wrong".toCharArray()); fail() } catch (e: WrongPasswordException) { /* expected */ }
        try { BackupCodec.decode(bytes, null); fail() } catch (e: WrongPasswordException) { /* expected */ }
    }

    @Test fun encryptedFilesDoNotLeakPlainText() {
        val bytes = BackupCodec.encode(snapshot(tx(1)), "pw".toCharArray())
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Lunch"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Bank"))
    }

    @Test fun eachEncryptionUsesFreshRandomness() {
        val a = BackupCodec.encode(snapshot(tx(1)), "pw".toCharArray())
        val b = BackupCodec.encode(snapshot(tx(1)), "pw".toCharArray())
        assertFalse(a.contentEquals(b))
    }

    @Test fun tamperingWithAPlainBackupIsDetected() {
        val bytes = BackupCodec.encode(snapshot(tx(1)), null)
        bytes[40] = (bytes[40] + 1).toByte()
        try { BackupCodec.decode(bytes, null); fail() } catch (e: BackupException) { /* expected */ }
    }

    @Test fun tamperingWithAnEncryptedBackupIsDetected() {
        val bytes = BackupCodec.encode(snapshot(tx(1)), "pw".toCharArray())
        bytes[bytes.size - 5] = (bytes[bytes.size - 5] + 1).toByte()
        try { BackupCodec.decode(bytes, "pw".toCharArray()); fail() } catch (e: WrongPasswordException) { /* GCM tag mismatch */ }
    }

    @Test fun rejectsFilesThatAreNotBackups() {
        try { BackupCodec.decode("hello world, not a backup".toByteArray(), null); fail() } catch (e: BackupException) { /* expected */ }
        try { BackupCodec.decode(ByteArray(0), null); fail() } catch (e: BackupException) { /* expected */ }
    }

    @Test fun rejectsBackupsFromNewerVersions() {
        try { BackupCodec.decode(BackupCodec.encode(snapshot(tx(1), schema = 99), null), null); fail() } catch (e: BackupException) { assertTrue(e.message!!.contains("newer")) }
        try { BackupCodec.decode(BackupCodec.encode(snapshot(tx(1), format = 7), null), null); fail() } catch (e: BackupException) { /* expected */ }
    }

    @Test fun rejectsInconsistentData() {
        try { BackupCodec.decode(BackupCodec.encode(snapshot(tx(1, account = 42)), null), null); fail() } catch (e: BackupException) { /* dangling account */ }
        try { BackupCodec.decode(BackupCodec.encode(snapshot(tx(1, category = 77)), null), null); fail() } catch (e: BackupException) { /* dangling category */ }
        try { BackupCodec.decode(BackupCodec.encode(snapshot(tx(1), tx(1)), null), null); fail() } catch (e: BackupException) { /* duplicate ids */ }
    }

    @Test fun emptyPasswordMeansAPlainBackup() {
        val bytes = BackupCodec.encode(snapshot(tx(1)), CharArray(0))
        assertFalse(BackupCodec.isEncrypted(bytes))
    }
}
