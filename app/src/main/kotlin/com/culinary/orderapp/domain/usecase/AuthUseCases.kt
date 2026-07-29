package com.culinary.orderapp.domain.usecase

import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.repository.AuthRepository
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveAuthStateUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    operator fun invoke(): Flow<FirebaseUser?> =
        authRepository.observeAuthState()
}

class GetCurrentFirebaseUserUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    operator fun invoke(): FirebaseUser? =
        authRepository.getCurrentFirebaseUser()
}

class GetCurrentUserUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(): Result<User?> =
        authRepository.getCurrentUser()
}

class SignInWithGoogleUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(idToken: String): Result<User> =
        authRepository.signInWithGoogle(idToken)
}

class SignOutUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(): Result<Unit> =
        authRepository.signOut()
}

class SignInWithEmailPasswordUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(email: String, password: String): Result<User> =
        authRepository.signInWithEmailAndPassword(email, password)
}

class CreateUserWithEmailPasswordUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(
        email: String,
        password: String,
        displayName: String,
        roleId: String,
        roleName: String
    ): Result<User> =
        authRepository.createUserWithEmailAndPassword(email, password, displayName, roleId, roleName)
}

class HasPermissionUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(userId: String, permission: String): Boolean =
        authRepository.hasPermission(userId, permission)
}
