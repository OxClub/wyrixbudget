package com.wyrix.budget.presentation.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.money.MoneyParseResult
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.data.files.ImageUtils
import com.wyrix.budget.data.files.ReceiptOcr
import com.wyrix.budget.data.files.SecureFileStore
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.data.security.AppLockManager
import com.wyrix.budget.domain.calc.ReceiptTextParser
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.*
import com.wyrix.budget.domain.usecase.*
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class EditorForm(
    val type: TransactionType = TransactionType.EXPENSE,
    val amountText: String = "",
    val title: String = "",
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().truncatedTo(ChronoUnit.MINUTES),
    val merchant: String = "",
    val notes: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val gstTagged: Boolean = false,
    val gstText: String = "",
    val rateText: String = "",
    val errors: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val isLoaded: Boolean = true,
    val loadFailed: Boolean = false,
)

class PendingImage(val id: Int, val bytes: ByteArray)

data class EditorUiState(
    val isEditing: Boolean,
    val form: EditorForm,
    val accounts: List<AccountWithBalance> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accountsLoaded: Boolean = false,
    val effectiveAccountId: Long? = null,
    val effectiveToAccountId: Long? = null,
    val accountCurrency: CurrencyInfo = CurrencyInfo.INR,
    val baseCurrency: CurrencyInfo = CurrencyInfo.INR,
    /** The account is in a foreign currency, so an exchange rate is needed for income and expenses. */
    val needsRate: Boolean = false,
    val savedRate: ExchangeRate? = null,
)

sealed interface EditorEvent {
    data object Done : EditorEvent
    data class Message(val text: String) : EditorEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val rateRepository: ExchangeRateRepository,
    private val attachmentRepository: AttachmentRepository,
    private val files: SecureFileStore,
    private val lock: AppLockManager,
    prefs: UserPreferencesRepository,
    private val saveTransaction: SaveTransactionUseCase,
    private val deleteTransaction: DeleteTransactionUseCase,
    private val duplicateTransaction: DuplicateTransactionUseCase,
    private val createCategoryUseCase: CreateCategoryUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val editingId: Long? = (savedStateHandle.get<Long>(Routes.ARG_ID) ?: -1L).takeIf { it > 0L }
    private val initialType: TransactionType = savedStateHandle.get<String>(Routes.ARG_TYPE)
        ?.let { name -> TransactionType.entries.firstOrNull { it.name == name } }
        ?.takeIf { it != TransactionType.ADJUSTMENT } ?: TransactionType.EXPENSE

    private val form = MutableStateFlow(newForm())
    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()
    private var actionInFlight = false

    private val categoriesFlow: Flow<List<Category>> = form.map { it.type }.distinctUntilChanged().flatMapLatest { type ->
        when (type) {
            TransactionType.INCOME -> categoryRepository.observeByKind(CategoryKind.INCOME)
            TransactionType.EXPENSE -> categoryRepository.observeByKind(CategoryKind.EXPENSE)
            else -> flowOf(emptyList())
        }
    }

    val uiState: StateFlow<EditorUiState> = combine(
        form, accountRepository.observeAccounts(), categoriesFlow, rateRepository.observeAll(), prefs.settings,
    ) { f, accounts, categories, rates, settings ->
        val from = f.accountId ?: accounts.firstOrNull()?.account?.id
        val to = f.toAccountId ?: accounts.firstOrNull { it.account.id != from }?.account?.id
        val accountCode = accounts.firstOrNull { it.account.id == from }?.account?.currencyCode ?: settings.baseCurrency
        val needsRate = (f.type == TransactionType.INCOME || f.type == TransactionType.EXPENSE) && accountCode != settings.baseCurrency
        EditorUiState(
            isEditing = editingId != null, form = f, accounts = accounts, categories = categories, accountsLoaded = true,
            effectiveAccountId = from, effectiveToAccountId = to,
            accountCurrency = CurrencyInfo.byCode(accountCode), baseCurrency = CurrencyInfo.byCode(settings.baseCurrency),
            needsRate = needsRate, savedRate = rates.firstOrNull { it.currencyCode == accountCode },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EditorUiState(isEditing = editingId != null, form = form.value))

    // ---- receipts -------------------------------------------------------------------------
    val attachments: StateFlow<List<Attachment>> = (editingId?.let { attachmentRepository.observe(it) } ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _pending = MutableStateFlow<List<PendingImage>>(emptyList())
    val pending: StateFlow<List<PendingImage>> = _pending
    private val _ocrBusy = MutableStateFlow(false)
    val ocrBusy: StateFlow<Boolean> = _ocrBusy
    private var nextPendingId = 1
    private var captureFile: File? = null

    init {
        val id = editingId
        if (id != null) {
            viewModelScope.launch {
                val t = transactionRepository.getById(id)
                if (t == null) form.update { it.copy(isLoaded = true, loadFailed = true) } else {
                    val local = Instant.ofEpochMilli(t.occurredAt).atZone(zone)
                    val cur = CurrencyInfo.byCode(t.currencyCode)
                    form.value = EditorForm(
                        type = t.type, amountText = MoneyFormatter.formatPlain(t.amountMinor, cur), title = t.title,
                        categoryId = t.categoryId, accountId = t.accountId, toAccountId = t.transferAccountId,
                        date = local.toLocalDate(), time = local.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
                        merchant = t.merchant, notes = t.notes, paymentMethod = t.paymentMethod, gstTagged = t.gstTagged,
                        gstText = t.gstAmountMinor?.let { MoneyFormatter.formatPlain(it, cur) } ?: "", rateText = t.exchangeRate ?: "", isLoaded = true,
                    )
                }
            }
        }
    }

    private fun newForm(): EditorForm {
        val now = LocalDateTime.ofInstant(clock.instant(), zone).truncatedTo(ChronoUnit.MINUTES)
        return EditorForm(type = initialType, date = now.toLocalDate(), time = now.toLocalTime(), isLoaded = editingId == null)
    }

    // ---- form input -------------------------------------------------------------------------
    fun onTypeChange(type: TransactionType) {
        if (editingId != null) return
        form.update { it.copy(type = type, categoryId = null, errors = emptyList()) }
    }

    fun onAmountChange(raw: String) {
        val normalized = raw.replace(',', '.')
        if (AMOUNT_INPUT.matches(normalized)) form.update { it.copy(amountText = normalized, errors = emptyList()) }
    }

    fun onGstAmountChange(raw: String) { val n = raw.replace(',', '.'); if (AMOUNT_INPUT.matches(n)) form.update { it.copy(gstText = n) } }
    fun onRateChange(raw: String) { val n = raw.replace(',', '.'); if (RATE_INPUT.matches(n)) form.update { it.copy(rateText = n, errors = emptyList()) } }
    fun onTitleChange(v: String) = form.update { it.copy(title = v.take(80)) }
    fun onMerchantChange(v: String) = form.update { it.copy(merchant = v.take(80)) }
    fun onNotesChange(v: String) = form.update { it.copy(notes = v.take(500)) }
    fun onCategorySelected(id: Long) = form.update { it.copy(categoryId = id) }
    fun onDateChange(d: LocalDate) = form.update { it.copy(date = d) }
    fun onTimeChange(t: LocalTime) = form.update { it.copy(time = t) }
    fun onPaymentMethod(m: PaymentMethod) = form.update { it.copy(paymentMethod = m) }
    fun onGstTagged(v: Boolean) = form.update { it.copy(gstTagged = v) }
    fun onAccountSelected(id: Long) = form.update { it.copy(accountId = id, toAccountId = if (it.toAccountId == id) null else it.toAccountId, rateText = "") }
    fun onToAccountSelected(id: Long) = form.update { it.copy(toAccountId = id) }

    // ---- receipts -------------------------------------------------------------------------
    fun newCaptureFile(): File { lock.beginExternalFlow(); return files.captureFile().also { captureFile = it } }
    fun beginPicker() = lock.beginExternalFlow()

    fun onPhotoCaptured(success: Boolean) {
        val f = captureFile ?: return
        captureFile = null
        if (!success) { f.delete(); return }
        viewModelScope.launch { addPending(withContext(Dispatchers.IO) { val b = f.readBytes(); f.delete(); b }) }
    }

    fun onImagePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes == null) _events.send(EditorEvent.Message("Couldn't read that image.")) else addPending(bytes)
        }
    }

    private suspend fun addPending(raw: ByteArray) {
        try {
            val compressed = withContext(Dispatchers.Default) { ImageUtils.compressJpeg(raw) }
            _pending.update { it + PendingImage(nextPendingId++, compressed) }
        } catch (e: Exception) { _events.send(EditorEvent.Message("That file isn't a readable image.")) }
    }

    fun removePending(id: Int) = _pending.update { list -> list.filter { it.id != id } }

    fun removeAttachment(a: Attachment) {
        viewModelScope.launch { files.delete(a.filePath); attachmentRepository.remove(a.id) }
    }

    suspend fun thumbnail(a: Attachment): Bitmap? = withContext(Dispatchers.IO) {
        try { ImageUtils.decode(files.read(a.filePath), 320) } catch (e: Exception) { null }
    }

    /** Reads the text on the first receipt image and fills EMPTY fields with suggestions the user must check. */
    fun scanReceipt() {
        if (_ocrBusy.value) return
        _ocrBusy.value = true
        viewModelScope.launch {
            try {
                val bytes = _pending.value.firstOrNull()?.bytes ?: attachments.value.firstOrNull()?.let { withContext(Dispatchers.IO) { files.read(it.filePath) } }
                if (bytes == null) { _events.send(EditorEvent.Message("Add a receipt photo first.")); return@launch }
                val bmp = withContext(Dispatchers.Default) { ImageUtils.decode(bytes, 1600) } ?: run { _events.send(EditorEvent.Message("Couldn't read the image.")); return@launch }
                val parsed = ReceiptTextParser.parse(ReceiptOcr.recognize(bmp))
                val cur = uiState.value.accountCurrency
                val filled = ArrayList<String>()
                form.update { f ->
                    var n = f
                    if (n.merchant.isBlank() && parsed.merchant != null) { n = n.copy(merchant = parsed.merchant.take(80)); filled += "merchant" }
                    if (n.amountText.isBlank() && parsed.totalMinor != null) { n = n.copy(amountText = MoneyFormatter.formatPlain(parsed.totalMinor, cur)); filled += "amount" }
                    if (parsed.date != null) { n = n.copy(date = parsed.date); filled += "date" }
                    if (parsed.taxMinor != null && !n.gstTagged) { n = n.copy(gstTagged = true, gstText = MoneyFormatter.formatPlain(parsed.taxMinor, cur)); filled += "tax" }
                    n
                }
                _events.send(EditorEvent.Message(if (filled.isEmpty()) "Couldn't find anything to fill in. Enter the details yourself." else "Filled in: ${filled.joinToString()}. Please check them against the receipt."))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _events.send(EditorEvent.Message("Text recognition didn't work on this image."))
            } finally { _ocrBusy.value = false }
        }
    }

    // ---- actions ----------------------------------------------------------------------------
    fun save() {
        if (actionInFlight) return
        val state = uiState.value
        val f = state.form
        val cur = state.accountCurrency
        val parsed = MoneyParser.parse(f.amountText, currency = cur)
        if (parsed !is MoneyParseResult.Valid) {
            form.update { it.copy(errors = listOf("Enter a valid amount, for example 250 or 250.50.")) }
            return
        }
        val gst: Long? = if (f.gstTagged && f.gstText.isNotBlank()) (MoneyParser.parse(f.gstText, currency = cur) as? MoneyParseResult.Valid)?.minor else null
        if (f.gstTagged && f.gstText.isNotBlank() && gst == null) { form.update { it.copy(errors = listOf("Enter a valid GST amount.")) }; return }
        actionInFlight = true
        form.update { it.copy(isSaving = true, errors = emptyList()) }

        val isTransfer = f.type == TransactionType.TRANSFER
        val transaction = Transaction(
            id = editingId ?: 0L, type = f.type, amountMinor = parsed.minor, title = f.title,
            categoryId = if (isTransfer) null else f.categoryId,
            accountId = state.effectiveAccountId ?: 0L,
            transferAccountId = if (isTransfer) state.effectiveToAccountId else null,
            occurredAt = LocalDateTime.of(f.date, f.time).atZone(zone).toInstant().toEpochMilli(),
            merchant = if (isTransfer) "" else f.merchant, notes = f.notes,
            paymentMethod = if (isTransfer) PaymentMethod.OTHER else f.paymentMethod,
            gstTagged = f.gstTagged && !isTransfer, gstAmountMinor = gst,
            exchangeRate = if (state.needsRate) f.rateText else null,
        )

        viewModelScope.launch {
            try {
                when (val result = saveTransaction(transaction)) {
                    is ActionResult.Success -> {
                        persistPending(result.value)
                        _events.send(EditorEvent.Done)
                    }
                    is ActionResult.Rejected -> failSave(result.messages)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failSave(listOf("Couldn't save. Nothing was changed, please try again."))
            }
        }
    }

    private suspend fun persistPending(transactionId: Long) {
        for (img in _pending.value) {
            try {
                val path = withContext(Dispatchers.IO) { files.save(img.bytes) }
                attachmentRepository.add(Attachment(transactionId = transactionId, filePath = path, mimeType = "image/jpeg", createdAt = clock.millis()))
            } catch (e: Exception) { /* the transaction itself is already saved; the receipt can be added again by editing it */ }
        }
        _pending.value = emptyList()
    }

    private fun failSave(messages: List<String>) {
        actionInFlight = false
        form.update { it.copy(isSaving = false, errors = messages) }
    }

    fun delete() {
        val id = editingId ?: return
        viewModelScope.launch { attachments.value.forEach { files.delete(it.filePath) } }
        runExclusive { deleteTransaction(id) }
    }

    fun duplicate() {
        val id = editingId ?: return
        runExclusive { duplicateTransaction(id) }
    }

    private fun runExclusive(block: suspend () -> ActionResult<*>) {
        if (actionInFlight) return
        actionInFlight = true
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is ActionResult.Success -> _events.send(EditorEvent.Done)
                    is ActionResult.Rejected -> { actionInFlight = false; _events.send(EditorEvent.Message(result.messages.first())) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                actionInFlight = false
                _events.send(EditorEvent.Message("That didn't work. Nothing was changed."))
            }
        }
    }

    fun createCategory(name: String) {
        val kind = if (form.value.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        viewModelScope.launch {
            try {
                when (val result = createCategoryUseCase(name, kind)) {
                    is ActionResult.Success -> form.update { it.copy(categoryId = result.value) }
                    is ActionResult.Rejected -> _events.send(EditorEvent.Message(result.messages.first()))
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _events.send(EditorEvent.Message("Couldn't create the category.")) }
        }
    }

    private companion object {
        val AMOUNT_INPUT = Regex("""^\d{0,10}(\.\d{0,2})?$""")
        val RATE_INPUT = Regex("""^\d{0,6}(\.\d{0,6})?$""")
    }
}
