package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.UserDto
import com.culinary.orderapp.domain.model.Permission
import com.culinary.orderapp.domain.model.SystemRoles
import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.repository.AuthRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : AuthRepository {

    private val usersCollection get() = firestore.collection("users")
    private val rolesCollection get() = firestore.collection("roles")

    override fun getCurrentFirebaseUser(): FirebaseUser? {
        return auth.currentUser
    }

    override fun observeAuthState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signInWithGoogle(idToken: String): Result<User> {
        return try {
            Logger.d("Signing in with Google", TAG)
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: return Result.failure(Exception("No user returned"))

            // Check if user exists in Firestore
            val userDoc = usersCollection.document(firebaseUser.uid).get().await()
            
            val user = if (userDoc.exists()) {
                // Existing user - update last login
                updateLastLogin(firebaseUser.uid)
                userDoc.toObject(UserDto::class.java)?.toDomain()
                    ?: return Result.failure(Exception("Failed to parse user data"))
            } else {
                // New user - check if this is the first user (should be owner)
                val usersCount = usersCollection.get().await().size()
                val roleId = if (usersCount == 0) SystemRoles.OWNER else SystemRoles.FINANCE
                val roleName = if (usersCount == 0) "Owner" else "Finance"
                
                // Create new user
                val newUser = User(
                    id = firebaseUser.uid,
                    email = firebaseUser.email ?: "",
                    displayName = firebaseUser.displayName ?: "",
                    photoUrl = firebaseUser.photoUrl?.toString(),
                    roleId = roleId,
                    roleName = roleName,
                    isActive = true,
                    createdAt = Timestamp.now().toDate(),
                    lastLoginAt = Timestamp.now().toDate()
                )
                
                val userDto = UserDto.fromDomain(newUser)
                usersCollection.document(firebaseUser.uid).set(userDto).await()
                Logger.i("Created new user: ${firebaseUser.email} with role: $roleName", TAG)
                newUser
            }

            Logger.i("User signed in successfully: ${user.email}", TAG)
            Result.success(user)
        } catch (e: Exception) {
            Logger.e("Error signing in with Google", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            Logger.d("Signing out user", TAG)
            auth.signOut()
            Logger.i("User signed out successfully", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error signing out", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun getCurrentUser(): Result<User?> {
        return try {
            val firebaseUser = getCurrentFirebaseUser() ?: return Result.success(null)
            
            val userDoc = usersCollection.document(firebaseUser.uid).get().await()
            if (!userDoc.exists()) {
                Logger.w("User document not found for: ${firebaseUser.uid}", TAG)
                return Result.success(null)
            }
            
            val user = userDoc.toObject(UserDto::class.java)?.toDomain()
            Result.success(user)
        } catch (e: Exception) {
            Logger.e("Error getting current user", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun updateLastLogin(userId: String): Result<Unit> {
        return try {
            usersCollection.document(userId)
                .update("lastLoginAt", Timestamp.now())
                .await()
            Logger.d("Updated last login for user: $userId", TAG)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("Error updating last login", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun hasPermission(userId: String, permission: String): Boolean {
        return try {
            val userDoc = usersCollection.document(userId).get().await()
            if (!userDoc.exists()) return false
            
            val user = userDoc.toObject(UserDto::class.java) ?: return false
            if (!user.isActive) return false
            
            // Get user's role
            val roleDoc = rolesCollection.document(user.roleId).get().await()
            if (!roleDoc.exists()) return false
            
            val permissions = roleDoc.get("permissions") as? List<*> ?: return false
            permissions.contains(permission)
        } catch (e: Exception) {
            Logger.e("Error checking permission", e, TAG)
            false
        }
    }

    companion object {
        private const val TAG = "AuthRepository"
    }
}
