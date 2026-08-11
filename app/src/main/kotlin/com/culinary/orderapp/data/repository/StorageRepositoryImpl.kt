package com.culinary.orderapp.data.repository

import android.content.Context
import android.net.Uri
import com.culinary.orderapp.BuildConfig
import com.culinary.orderapp.domain.repository.StorageRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseAuth: FirebaseAuth
) : StorageRepository {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun uploadImage(uri: Uri, path: String): Result<String> {
        return try {
            val cloudName = BuildConfig.CLOUDINARY_CLOUD_NAME
            val preset = BuildConfig.CLOUDINARY_UPLOAD_PRESET
            if (cloudName.isBlank() || preset.isBlank()) {
                return Result.failure(Exception("Cloudinary cloud name / upload preset is not configured"))
            }

            val folder = path.substringBefore('/')
            val extension = path.substringAfterLast('.', "jpg")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(Exception("Cannot open image file"))
            val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"

            val filePart = MultipartBody.Part.createFormData(
                "file",
                "image.$extension",
                bytes.toRequestBody(contentType.toMediaType())
            )
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("upload_preset", preset)
                .addFormDataPart("folder", folder)
                .addPart(filePart)
                .build()

            val request = Request.Builder()
                .url("https://api.cloudinary.com/v1_1/$cloudName/image/upload")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Logger.e("Cloudinary upload failed: HTTP ${response.code} $responseBody", tag = TAG)
                    return Result.failure(Exception("Upload failed (HTTP ${response.code}): ${parseCloudinaryError(responseBody)}"))
                }
                val json = JSONObject(responseBody)
                val url = json.optString("secure_url").ifBlank { json.optString("url") }
                if (url.isBlank()) {
                    return Result.failure(Exception("Upload failed: no URL in response"))
                }
                Logger.i("Image uploaded successfully: $url", TAG)
                Result.success(url)
            }
        } catch (e: Exception) {
            Logger.e("Error uploading image to $path", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun deleteImage(imageUrl: String): Result<Unit> {
        val baseUrl = BuildConfig.NETLIFY_FUNCTIONS_BASE_URL
        if (baseUrl.isBlank()) {
            Logger.w("NETLIFY_FUNCTIONS_BASE_URL not configured; skipping delete of $imageUrl", TAG)
            return Result.success(Unit)
        }
        if (!imageUrl.contains("/image/upload/")) {
            return Result.failure(IllegalArgumentException("Not a Cloudinary URL"))
        }
        return try {
            val token = firebaseAuth.currentUser?.getIdToken(false)?.await()?.token
                ?: return Result.failure(Exception("User is not authenticated"))
            val url = "$baseUrl/.netlify/functions/delete-image?url=${Uri.encode(imageUrl)}"
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Logger.i("Image deleted: $imageUrl", TAG)
                    Result.success(Unit)
                } else {
                    val message = response.body?.string().orEmpty()
                    Logger.e("Delete failed HTTP ${response.code}: $message", tag = TAG)
                    Result.failure(Exception("Delete failed (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Logger.e("Error deleting image $imageUrl", e, TAG)
            Result.failure(e)
        }
    }

    private fun parseCloudinaryError(body: String): String {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body
        } catch (e: Exception) {
            body
        }
    }

    companion object {
        private const val TAG = "StorageRepository"
    }
}
