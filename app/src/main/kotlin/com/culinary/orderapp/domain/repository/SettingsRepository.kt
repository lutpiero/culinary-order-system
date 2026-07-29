package com.culinary.orderapp.domain.repository

import com.culinary.orderapp.domain.model.BusinessSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    /**
     * Observe business settings changes
     */
    fun observeSettings(): Flow<BusinessSettings?>
    
    /**
     * Get current business settings
     */
    suspend fun getSettings(): Result<BusinessSettings?>
    
    /**
     * Update business settings
     */
    suspend fun updateSettings(settings: BusinessSettings): Result<Unit>
    
    /**
     * Initialize default settings if they don't exist
     */
    suspend fun initializeDefaultSettings(): Result<Unit>
}
