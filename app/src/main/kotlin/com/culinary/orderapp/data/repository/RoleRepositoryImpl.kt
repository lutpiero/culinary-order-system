package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.RoleDto
import com.culinary.orderapp.domain.model.Role
import com.culinary.orderapp.domain.model.SystemRoles
import com.culinary.orderapp.domain.repository.RoleRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoleRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : RoleRepository {

    private val rolesCollection get() = firestore.collection("roles")

    override fun observeRoles(): Flow<List<Role>> = callbackFlow {
        val listener = rolesCollection
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Logger.e("Error observing roles", error, TAG)
                    close(error)
                    return@addSnapshotListener
                }
                
                val roles = snapshot?.documents?.mapNotNull { doc ->
                    runCatching {
                        doc.toObject(RoleDto::class.java)?.toDomain()
                    }.onFailure { e ->
                        Logger.e("Error parsing role document ${doc.id}", e, TAG)
                    }.getOrNull()
                } ?: emptyList()
                
                Logger.d("Loaded ${roles.size} roles", TAG)
                trySend(roles)
            }
        awaitClose { listener.remove() }
    }

    override suspend fun getRoleById(roleId: String): Result<Role?> {
        return try {
            Logger.d("Fetching role: $roleId", TAG)
            val doc = rolesCollection.document(roleId).get().await()
            
            if (!doc.exists()) {
                Logger.w("Role not found: $roleId", TAG)
                return Result.success(null)
            }
            
            val role = doc.toObject(RoleDto::class.java)?.toDomain()
            Result.success(role)
        } catch (e: Exception) {
            Logger.e("Error fetching role $roleId", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun createRole(role: Role): Result<Role> {
        return try {
            Logger.d("Creating role: ${role.name}", TAG)
            
            // Prevent creating system roles
            if (role.isSystemRole) {
                return Result.failure(Exception("Cannot create system roles"))
            }
            
            val roleDto = RoleDto.fromDomain(role)
            rolesCollection.document(role.id).set(roleDto).await()
            
            Logger.i("Role created successfully: ${role.name}", TAG)
            Result.success(role)
        } catch (e: Exception) {
            Logger.e("Error creating role", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun updateRole(role: Role): Result<Unit> {
        return try {
            Logger.d("Updating role: ${role.id}", TAG)
            
            // Check if role is a system role
            val existingDoc = rolesCollection.document(role.id).get().await()
            if (existingDoc.exists()) {
                val existingRole = existingDoc.toObject(RoleDto::class.java)
                if (existingRole?.isSystemRole == true) {
                    return Result.failure(Exception("Cannot update system roles"))
                }
            }
            
            val roleDto = RoleDto.fromDomain(role)
            rolesCollection.document(role.id).set(roleDto).await()
            
            Logger.i("Role updated successfully: ${role.name}", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error updating role", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun deleteRole(roleId: String): Result<Unit> {
        return try {
            Logger.d("Deleting role: $roleId", TAG)
            
            // Check if role is a system role
            val doc = rolesCollection.document(roleId).get().await()
            if (doc.exists()) {
                val role = doc.toObject(RoleDto::class.java)
                if (role?.isSystemRole == true) {
                    return Result.failure(Exception("Cannot delete system roles"))
                }
            }
            
            // Check if any users have this role
            val usersWithRole = firestore.collection("users")
                .whereEqualTo("roleId", roleId)
                .limit(1)
                .get()
                .await()
            
            if (!usersWithRole.isEmpty) {
                return Result.failure(Exception("Cannot delete role that is assigned to users"))
            }
            
            rolesCollection.document(roleId).delete().await()
            
            Logger.i("Role deleted successfully: $roleId", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error deleting role", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun initializeSystemRoles(): Result<Unit> {
        return try {
            Logger.d("Initializing system roles", TAG)
            
            val systemRoles = SystemRoles.getAllSystemRoles()
            
            for (role in systemRoles) {
                val doc = rolesCollection.document(role.id).get().await()
                if (!doc.exists()) {
                    val roleDto = RoleDto.fromDomain(role)
                    rolesCollection.document(role.id).set(roleDto).await()
                    Logger.i("Created system role: ${role.name}", TAG)
                }
            }
            
            Logger.i("System roles initialized successfully", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error initializing system roles", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun getSystemRoles(): List<Role> {
        return SystemRoles.getAllSystemRoles()
    }

    companion object {
        private const val TAG = "RoleRepository"
    }
}
