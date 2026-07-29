package com.culinary.orderapp.domain.usecase

import com.culinary.orderapp.domain.model.User
import com.culinary.orderapp.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveUsersUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    operator fun invoke(): Flow<List<User>> =
        userRepository.observeUsers()
}

class GetUserByIdUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(userId: String): Result<User?> =
        userRepository.getUserById(userId)
}

class CreateUserUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(user: User): Result<User> =
        userRepository.createUser(user)
}

class UpdateUserUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(user: User): Result<Unit> =
        userRepository.updateUser(user)
}

class DeleteUserUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(userId: String): Result<Unit> =
        userRepository.deleteUser(userId)
}

class ToggleUserStatusUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    suspend operator fun invoke(userId: String, isActive: Boolean): Result<Unit> =
        userRepository.toggleUserStatus(userId, isActive)
}
