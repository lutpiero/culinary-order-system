package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.UserDto
import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.repository.UserRepository
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
class UserRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : UserRepository {

    private val usersCollection get() = firestore.collection("users")

    override fun observeUsers(): Flow<List<User>> = callbackFlow {
        val listener = usersCollection
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Logger.e("Error observing users", error, TAG)
                    close(error)
                    return@addSnapshotListener
                }
                
                val users = snapshot?.documents?.mapNotNull { doc ->
                    runCatching {
                        doc.toObject(UserDto::class.java)?.toDomain()
                    }.onFailure { e ->
                        Logger.e("Error parsing user document ${doc.id}", e, TAG)
                    }.getOrNull()
                } ?: emptyList()
                
                Logger.d("Loaded ${users.size} users", TAG)
                trySend(users)
            }
        awaitClose { listener.remove() }
    }

    override suspend fun getUserById(userId: String): Result<User?> {
        return try {
            Logger.d("Fetching user: $userId", TAG)
            val doc = usersCollection.document(userId).get().await()
            
            if (!doc.exists()) {
                Logger.w("User not found: $userId", TAG)
                return Result.success(null)
            }
            
            val user = doc.toObject(UserDto::class.java)?.toDomain()
            Result.success(user)
        } catch (e: Exception) {
            Logger.e("Error fetching user $userId", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun createUser(user: User): Result<User> {
        return try {
            Logger.d("Creating user: ${user.email}", TAG)
            
            // Check if user already exists
            if (userExistsByEmail(user.email)) {
                return Result.failure(Exception("User with email ${user.email} already exists"))
            }
            
            val userDto = UserDto.fromDomain(user)
            usersCollection.document(user.id).set(userDto).await()
            
            Logger.i("User created successfully: ${user.email}", TAG)
            Result.success(user)
        } catch (e: Exception) {
            Logger.e("Error creating user", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun updateUser(user: User): Result<Unit> {
        return try {
            Logger.d("Updating user: ${user.id}", TAG)
            val userDto = UserDto.fromDomain(user)
            usersCollection.document(user.id).set(userDto).await()
            
            Logger.i("User updated successfully: ${user.email}", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error updating user", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun deleteUser(userId: String): Result<Unit> {
        return try {
            Logger.d("Deleting user: $userId", TAG)
            usersCollection.document(userId).delete().await()
            
            Logger.i("User deleted successfully: $userId", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error deleting user", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun toggleUserStatus(userId: String, isActive: Boolean): Result<Unit> {
        return try {
            Logger.d("Toggling user status: $userId to $isActive", TAG)
            usersCollection.document(userId)
                .update("isActive", isActive)
                .await()
            
            Logger.i("User status updated: $userId -> $isActive", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error toggling user status", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun userExistsByEmail(email: String): Boolean {
        return try {
            val snapshot = usersCollection
                .whereEqualTo("email", email)
                .limit(1)
                .get()
                .await()
            
            !snapshot.isEmpty
        } catch (e: Exception) {
            Logger.e("Error checking if user exists", e, TAG)
            false
        }
    }

    companion object {
        private const val TAG = "UserRepository"
    }
}
