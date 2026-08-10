package com.culinary.orderapp.domain.repository

import android.net.Uri

interface StorageRepository {
    /**
     * Upload an image to Firebase Storage and return its download URL.
     * @param uri the local content URI of the image
     * @param path the storage path, e.g. "menu_images/abc123.jpg"
     */
    suspend fun uploadImage(uri: Uri, path: String): Result<String>

    /**
     * Delete an image from Firebase Storage given its download URL.
     * The storage object path is derived from the URL.
     * @param imageUrl a Firebase Storage download URL
     */
    suspend fun deleteImage(imageUrl: String): Result<Unit>
}
