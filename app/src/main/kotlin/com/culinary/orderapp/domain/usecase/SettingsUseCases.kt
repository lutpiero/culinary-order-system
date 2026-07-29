package com.culinary.orderapp.domain.usecase

import com.culinary.orderapp.domain.model.BusinessSettings
import com.culinary.orderapp.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<BusinessSettings?> =
        settingsRepository.observeSettings()
}

class GetSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(): Result<BusinessSettings?> =
        settingsRepository.getSettings()
}

class UpdateSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(settings: BusinessSettings): Result<Unit> =
        settingsRepository.updateSettings(settings)
}

class InitializeDefaultSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(): Result<Unit> =
        settingsRepository.initializeDefaultSettings()
}
