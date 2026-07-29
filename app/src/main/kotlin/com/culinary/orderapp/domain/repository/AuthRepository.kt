package com.culinary.orderapp.domain.repository

import com.culinary.orderapp.domain.model.User
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    /**
     * Get current authenticated Firebase user
     */
    fun getCurrentFirebaseUser(): FirebaseUser?
    
    /**
     * Observe authentication state changes
     */
    fun observeAuthState(): Flow<FirebaseUser?>
    
    /**
     * Sign in with Google
     */
    suspend fun signInWithGoogle(idToken: String): Result<User>
    
    /**
     * Sign out current user
     */
    suspend fun signOut(): Result<Unit>
    
    /**
     * Get current user details from Firestore
     */
    suspend fun getCurrentUser(): Result<User?>
    
    /**
     * Update user's last login timestamp
     */
    suspend fun updateLastLogin(userId: String): Result<Unit>
    
    /**
     * Check if user has specific permission
     */
    suspend fun hasPermission(userId: String, permission: String): Boolean
}
