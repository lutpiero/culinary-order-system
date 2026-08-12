package com.culinary.orderapp.ui.screen.kasir

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.Order
import com.culinary.orderapp.domain.model.PaymentMethod
import com.culinary.orderapp.domain.repository.StorageRepository
import com.culinary.orderapp.domain.usecase.MarkOrderPaidUseCase
import com.culinary.orderapp.domain.usecase.ObserveSettingsUseCase
import com.culinary.orderapp.domain.usecase.ObserveUnpaidPaymentsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KasirUiState(
    val orders: List<Order> = emptyList(),
    val filter: PaymentMethod? = null,  // null = all unpaid where methods selected
    val selectedMethods: List<PaymentMethod> = listOf(
        PaymentMethod.CASHIER,
        PaymentMethod.BANK_TRANSFER
    ),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val businessName: String = "",
    val logoUrl: String? = null,
    val payOrder: Order? = null,
    val amountText: String = "",
    val proofUri: Uri? = null,
    val isPaying: Boolean = false,
    val dialogError: String? = null
)

@HiltViewModel
class KasirViewModel @Inject constructor(
    private val observeUnpaidPayments: ObserveUnpaidPaymentsUseCase,
    private val markOrderPaid: MarkOrderPaidUseCase,
    private val storageRepository: StorageRepository,
    private val observeSettings: ObserveSettingsUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(KasirUiState())
    val uiState: StateFlow<KasirUiState> = _uiState.asStateFlow()

    init {
        loadPending()
        viewModelScope.launch {
            observeSettings().collect { settings ->
                if (settings != null) {
                    _uiState.value = _uiState.value.copy(
                        businessName = settings.businessName,
                        logoUrl = settings.logoUrl
                    )
                }
            }
        }
    }

    fun loadPending() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            observeUnpaidPayments(_uiState.value.selectedMethods)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Gagal memuat tagihan"
                    )
                }
                .collect { orders ->
                    _uiState.value = _uiState.value.copy(orders = orders, isLoading = false)
                }
        }
    }

    fun selectFilter(filter: PaymentMethod?) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    val filteredOrders: List<Order>
        get() {
            val filter = _uiState.value.filter
            return if (filter == null) _uiState.value.orders
            else _uiState.value.orders.filter { it.paymentMethod == filter }
        }

    fun openPayDialog(order: Order) {
        _uiState.value = _uiState.value.copy(
            payOrder = order,
            amountText = "",
            proofUri = null,
            isPaying = false,
            dialogError = null
        )
    }

    fun closePayDialog() {
        deleteProofFile(_uiState.value.proofUri)
        _uiState.value = _uiState.value.copy(payOrder = null, proofUri = null, dialogError = null)
    }

    fun updateAmountText(value: String) {
        _uiState.value = _uiState.value.copy(amountText = value.filter { it.isDigit() })
    }

    fun setProofUri(uri: Uri?) {
        _uiState.value = _uiState.value.copy(proofUri = uri)
    }

    fun removeProof() {
        deleteProofFile(_uiState.value.proofUri)
        _uiState.value = _uiState.value.copy(proofUri = null)
    }

    fun confirmPayment() {
        val order = _uiState.value.payOrder ?: return
        val isCash = order.paymentMethod == PaymentMethod.CASHIER
        val amount = if (isCash) _uiState.value.amountText.toLongOrNull() else null

        if (isCash) {
            when {
                amount == null -> {
                    _uiState.value = _uiState.value.copy(dialogError = "Masukkan jumlah uang yang diterima")
                    return
                }
                amount < order.totalAmount -> {
                    _uiState.value = _uiState.value.copy(dialogError = "Uang diterima kurang dari total tagihan")
                    return
                }
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPaying = true, dialogError = null)

            var proofUrl: String? = null
            _uiState.value.proofUri?.let { proofUri ->
                val uploadPath = "payment_proofs/${order.id}-${System.currentTimeMillis()}.jpg"
                val uploadResult = storageRepository.uploadImage(proofUri, uploadPath)
                if (uploadResult.isSuccess) {
                    proofUrl = uploadResult.getOrNull()
                    deleteProofFile(proofUri)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isPaying = false,
                        dialogError = uploadResult.exceptionOrNull()?.message ?: "Gagal mengunggah bukti pembayaran"
                    )
                    return@launch
                }
            }

            val result = markOrderPaid(order, amount, proofUrl)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(payOrder = null, proofUri = null, dialogError = null)
            } else {
                _uiState.value = _uiState.value.copy(
                    isPaying = false,
                    dialogError = result.exceptionOrNull()?.message ?: "Gagal menandai pembayaran"
                )
            }
        }
    }

    /** Best-effort cleanup of the temp camera-capture file (FileProvider cache). */
    private fun deleteProofFile(uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}