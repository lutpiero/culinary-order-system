package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.BusinessSettingsDto
import com.culinary.orderapp.domain.model.BusinessSettings
import com.culinary.orderapp.domain.repository.SettingsRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : SettingsRepository {

    private val settingsCollection get() = firestore.collection("settings")
    private val settingsDocId = "settings"

    override fun observeSettings(): Flow<BusinessSettings?> = callbackFlow {
        val listener = settingsCollection.document(settingsDocId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Logger.e("Error observing settings", error, TAG)
                    close(error)
                    return@addSnapshotListener
                }
                
                val settings = snapshot?.toObject(BusinessSettingsDto::class.java)?.toDomain()
                Logger.d("Settings updated", TAG)
                trySend(settings)
            }
        awaitClose { listener.remove() }
    }

    override suspend fun getSettings(): Result<BusinessSettings?> {
        return try {
            Logger.d("Fetching business settings", TAG)
            val doc = settingsCollection.document(settingsDocId).get().await()
            
            if (!doc.exists()) {
                Logger.w("Settings document not found", TAG)
                return Result.success(null)
            }
            
            val settings = doc.toObject(BusinessSettingsDto::class.java)?.toDomain()
            Result.success(settings)
        } catch (e: Exception) {
            Logger.e("Error fetching settings", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun updateSettings(settings: BusinessSettings): Result<Unit> {
        return try {
            Logger.d("Updating business settings", TAG)
            val settingsDto = BusinessSettingsDto.fromDomain(settings)
            settingsCollection.document(settingsDocId).set(settingsDto).await()
            
            Logger.i("Settings updated successfully", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error updating settings", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun initializeDefaultSettings(): Result<Unit> {
        return try {
            Logger.d("Initializing default settings", TAG)
            
            val doc = settingsCollection.document(settingsDocId).get().await()
            if (!doc.exists()) {
                val defaultSettings = BusinessSettings(
                    id = settingsDocId,
                    businessName = "My Restaurant",
                    webUrl = "",
                    phoneNumber = "",
                    address = "",
                    currency = "Rp",
                    taxPercentage = 0.0,
                    serviceChargePercentage = 0.0,
                    logoUrl = null
                )
                
                val settingsDto = BusinessSettingsDto.fromDomain(defaultSettings)
                settingsCollection.document(settingsDocId).set(settingsDto).await()
                Logger.i("Default settings created", TAG)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error initializing default settings", e, TAG)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"
    }
}
