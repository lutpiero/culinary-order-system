package com.culinary.orderapp.ui.screen.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.culinary.orderapp.domain.model.SalesSummary
import com.culinary.orderapp.domain.usecase.GetSalesSummaryUseCase
import com.culinary.orderapp.domain.usecase.ObserveSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

enum class FinancePeriod(val label: String) {
    DAILY("Hari Ini"),
    WEEKLY("7 Hari Terakhir"),
    MONTHLY("Bulan Ini")
}

data class FinanceUiState(
    val period: FinancePeriod = FinancePeriod.DAILY,
    val summary: SalesSummary? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val businessName: String = "",
    val logoUrl: String? = null
)

@HiltViewModel
class FinanceViewModel @Inject constructor(
    private val getSalesSummary: GetSalesSummaryUseCase,
    private val observeSettings: ObserveSettingsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FinanceUiState(isLoading = true))
    val uiState: StateFlow<FinanceUiState> = _uiState.asStateFlow()

    init {
        loadSummary(FinancePeriod.DAILY)
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

    fun loadSummary(period: FinancePeriod) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(period = period, isLoading = true, errorMessage = null)
            val (from, to) = getDateRange(period)
            val result = getSalesSummary(from, to)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                summary = result.getOrNull(),
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    private fun getDateRange(period: FinancePeriod): Pair<Date, Date> {
        val calendar = Calendar.getInstance()
        val to = calendar.time
        when (period) {
            FinancePeriod.DAILY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
            FinancePeriod.WEEKLY -> {
                calendar.add(Calendar.DAY_OF_YEAR, -7)
            }
            FinancePeriod.MONTHLY -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
            }
        }
        return calendar.time to to
    }
}
