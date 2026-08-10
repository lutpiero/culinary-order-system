package com.culinary.orderapp.data.repository

import android.content.Context
import android.net.Uri
import com.culinary.orderapp.domain.repository.StorageRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor(
    private val storage: FirebaseStorage,
    @ApplicationContext private val context: Context
) : StorageRepository {

    override suspend fun uploadImage(uri: Uri, path: String): Result<String> {
        return try {
            Logger.d("Uploading image to $path", TAG)
            val ref = storage.reference.child(path)
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Cannot open image file"))
            inputStream.use { stream ->
                ref.putStream(stream).await()
            }
            val downloadUrl = ref.downloadUrl.await().toString()
            Logger.i("Image uploaded successfully: $downloadUrl", TAG)
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Logger.e("Error uploading image to $path", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun deleteImage(imageUrl: String): Result<Unit> {
        return try {
            val path = storagePathFromUrl(imageUrl)
            if (path == null) {
                Logger.w("Cannot delete image, not a Firebase Storage URL: $imageUrl", TAG)
                return Result.failure(IllegalArgumentException("Invalid image URL"))
            }
            Logger.d("Deleting image at $path", TAG)
            storage.reference.child(path).delete().await()
            Logger.i("Image deleted: $path", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error deleting image $imageUrl", e, TAG)
            Result.failure(e)
        }
    }

    private fun storagePathFromUrl(imageUrl: String): String? {
        return try {
            val uri = Uri.parse(imageUrl)
            val segments = uri.pathSegments
            val oIndex = segments.indexOf("o")
            if (oIndex == -1 || oIndex >= segments.size - 1) return null
            Uri.decode(segments[oIndex + 1])
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "StorageRepository"
    }
}
