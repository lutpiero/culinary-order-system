package com.culinary.orderapp.domain.repository

import com.culinary.orderapp.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    /**
     * Observe all users
     */
    fun observeUsers(): Flow<List<User>>
    
    /**
     * Get user by ID
     */
    suspend fun getUserById(userId: String): Result<User?>
    
    /**
     * Create a new user
     */
    suspend fun createUser(user: User): Result<User>
    
    /**
     * Update existing user
     */
    suspend fun updateUser(user: User): Result<Unit>
    
    /**
     * Delete user
     */
    suspend fun deleteUser(userId: String): Result<Unit>
    
    /**
     * Toggle user active status
     */
    suspend fun toggleUserStatus(userId: String, isActive: Boolean): Result<Unit>
    
    /**
     * Check if user exists by email
     */
    suspend fun userExistsByEmail(email: String): Boolean
}
