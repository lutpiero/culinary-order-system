package com.culinary.orderapp.domain.repository

import android.net.Uri

interface StorageRepository {
    /**
     * Upload an image to Firebase Storage and return its download URL.
     * @param uri the local content URI of the image
     * @param path the storage path, e.g. "menu_images/abc123.jpg"
     */
    suspend fun uploadImage(uri: Uri, path: String): Result<String>
}
