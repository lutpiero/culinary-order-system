package com.culinary.orderapp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Utility object for handling runtime permissions in the app.
 */
object PermissionUtils {
    
    val CAMERA_PERMISSION = Manifest.permission.CAMERA
    
    val NOTIFICATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    
    /**
     * Checks if the app has camera permission.
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            CAMERA_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Checks if the app has notification permission.
     * Returns true for Android versions below 13 (TIRAMISU) as permission is not required.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required for older versions
        }
    }
    
    /**
     * Returns a list of all required permissions for the app.
     */
    fun getRequiredPermissions(): List<String> {
        return buildList {
            add(CAMERA_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    /**
     * Checks if all required permissions are granted.
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasCameraPermission(context) && hasNotificationPermission(context)
    }
}
