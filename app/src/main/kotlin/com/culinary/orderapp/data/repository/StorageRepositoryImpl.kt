package com.culinary.orderapp.data.repository

import android.net.Uri
import com.culinary.orderapp.domain.repository.StorageRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor(
    private val storage: FirebaseStorage
) : StorageRepository {

    override suspend fun uploadImage(uri: Uri, path: String): Result<String> {
        return try {
            Logger.d("Uploading image to $path", TAG)
            val ref = storage.reference.child(path)
            ref.putFile(uri).await()
            val downloadUrl = ref.downloadUrl.await().toString()
            Logger.i("Image uploaded successfully: $downloadUrl", TAG)
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Logger.e("Error uploading image to $path", e, TAG)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "StorageRepository"
    }
}
