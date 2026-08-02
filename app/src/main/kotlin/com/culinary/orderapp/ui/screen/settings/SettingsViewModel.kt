package com.culinary.orderapp.ui.screen.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.BusinessSettings
import com.culinary.orderapp.domain.repository.StorageRepository
import com.culinary.orderapp.domain.usecase.GetCurrentUserUseCase
import com.culinary.orderapp.domain.usecase.ObserveSettingsUseCase
import com.culinary.orderapp.domain.usecase.SignOutUseCase
import com.culinary.orderapp.domain.usecase.UpdateSettingsUseCase
import com.culinary.orderapp.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

data class SettingsUiState(
    val businessName: String = "",
    val webUrl: String = "",
    val phoneNumber: String = "",
    val address: String = "",
    val currency: String = "Rp",
    val taxPercentage: String = "",
    val serviceChargePercentage: String = "",
    val logoUrl: String? = null,
    val isUploadingLogo: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    private val getCurrentUser: GetCurrentUserUseCase,
    private val signOutUseCase: SignOutUseCase,
    private val storageRepository: StorageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            observeSettings().collect { settings ->
                if (settings != null) {
                    _uiState.value = SettingsUiState(
                        businessName = settings.businessName,
                        webUrl = settings.webUrl,
                        phoneNumber = settings.phoneNumber,
                        address = settings.address,
                        currency = settings.currency,
                        taxPercentage = if (settings.taxPercentage > 0) settings.taxPercentage.toString() else "",
                        serviceChargePercentage = if (settings.serviceChargePercentage > 0) settings.serviceChargePercentage.toString() else "",
                        logoUrl = settings.logoUrl,
                        isLoading = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun updateBusinessName(value: String) { _uiState.value = _uiState.value.copy(businessName = value) }
    fun updateWebUrl(value: String) { _uiState.value = _uiState.value.copy(webUrl = value) }
    fun updatePhoneNumber(value: String) { _uiState.value = _uiState.value.copy(phoneNumber = value) }
    fun updateAddress(value: String) { _uiState.value = _uiState.value.copy(address = value) }
    fun updateCurrency(value: String) { _uiState.value = _uiState.value.copy(currency = value) }
    fun updateTaxPercentage(value: String) { _uiState.value = _uiState.value.copy(taxPercentage = value) }
    fun updateServiceChargePercentage(value: String) { _uiState.value = _uiState.value.copy(serviceChargePercentage = value) }
    fun clearMessage() { _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null) }

    fun uploadBusinessIcon(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploadingLogo = true, errorMessage = null)
            val result = storageRepository.uploadImage(uri, "business_icons/logo.jpg")
            result.fold(
                onSuccess = { url ->
                    _uiState.value = _uiState.value.copy(logoUrl = url, isUploadingLogo = false)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isUploadingLogo = false,
                        errorMessage = e.message ?: "Gagal mengunggah ikon bisnis"
                    )
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            signOutUseCase()
            _uiState.value = _uiState.value.copy(isLoading = true)
        }
    }

    fun save() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null, successMessage = null)
            try {
                val currentUser = getCurrentUser().getOrNull()
                val tax = _uiState.value.taxPercentage.toDoubleOrNull() ?: 0.0
                val serviceCharge = _uiState.value.serviceChargePercentage.toDoubleOrNull() ?: 0.0

                val settings = BusinessSettings(
                    id = "settings",
                    businessName = _uiState.value.businessName,
                    webUrl = _uiState.value.webUrl,
                    phoneNumber = _uiState.value.phoneNumber,
                    address = _uiState.value.address,
                    currency = _uiState.value.currency,
                    taxPercentage = tax,
                    serviceChargePercentage = serviceCharge,
                    logoUrl = _uiState.value.logoUrl,
                    updatedAt = Date(),
                    updatedBy = currentUser?.id ?: ""
                )

                updateSettings(settings).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            successMessage = "Pengaturan berhasil disimpan"
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            errorMessage = e.message ?: "Gagal menyimpan pengaturan"
                        )
                    }
                )
            } catch (e: Exception) {
                Logger.e("Error saving settings", e, TAG)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Gagal menyimpan pengaturan"
                )
            }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
