package com.culinary.orderapp.domain.repository

import com.culinary.orderapp.domain.model.Role
import kotlinx.coroutines.flow.Flow

interface RoleRepository {
    /**
     * Observe all roles
     */
    fun observeRoles(): Flow<List<Role>>
    
    /**
     * Get role by ID
     */
    suspend fun getRoleById(roleId: String): Result<Role?>
    
    /**
     * Create a new custom role
     */
    suspend fun createRole(role: Role): Result<Role>
    
    /**
     * Update existing role (only custom roles can be updated)
     */
    suspend fun updateRole(role: Role): Result<Unit>
    
    /**
     * Delete role (only custom roles can be deleted)
     */
    suspend fun deleteRole(roleId: String): Result<Unit>
    
    /**
     * Initialize system roles (Owner, Finance) if they don't exist
     */
    suspend fun initializeSystemRoles(): Result<Unit>
    
    /**
     * Get all system roles
     */
    suspend fun getSystemRoles(): List<Role>
}
