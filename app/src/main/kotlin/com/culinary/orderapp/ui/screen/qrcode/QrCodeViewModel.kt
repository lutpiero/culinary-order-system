package com.culinary.orderapp.ui.screen.qrcode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QrCodeUiState(
    val businessName: String = "",
    val webUrl: String = ""
)

@HiltViewModel
class QrCodeViewModel @Inject constructor(
    private val observeSettings: ObserveSettingsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(QrCodeUiState())
    val uiState: StateFlow<QrCodeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeSettings().collect { settings ->
                if (settings != null) {
                    _uiState.value = QrCodeUiState(
                        businessName = settings.businessName,
                        webUrl = settings.webUrl
                    )
                }
            }
        }
    }
}
