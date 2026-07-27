package com.culinary.orderapp.util

import android.util.Log
import com.culinary.orderapp.BuildConfig

/**
 * Centralized logging utility for the application.
 * Automatically disables debug and info logs in release builds.
 */
object Logger {
    
    private const val TAG = "CulinaryApp"
    
    /**
     * Log a debug message. Only shown in debug builds.
     */
    fun d(message: String, tag: String = TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }
    
    /**
     * Log an error message with optional throwable. Always shown.
     */
    fun e(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
    
    /**
     * Log an info message. Only shown in debug builds.
     */
    fun i(message: String, tag: String = TAG) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }
    }
    
    /**
     * Log a warning message. Always shown.
     */
    fun w(message: String, tag: String = TAG) {
        Log.w(tag, message)
    }
    
    /**
     * Log a warning message with throwable. Always shown.
     */
    fun w(message: String, throwable: Throwable, tag: String = TAG) {
        Log.w(tag, message, throwable)
    }
}
