package com.culinary.orderapp.domain.usecase

import com.culinary.orderapp.domain.model.Role
import com.culinary.orderapp.domain.repository.RoleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveRolesUseCase @Inject constructor(
    private val roleRepository: RoleRepository
) {
    operator fun invoke(): Flow<List<Role>> =
        roleRepository.observeRoles()
}

class GetRoleByIdUseCase @Inject constructor(
    private val roleRepository: RoleRepository
) {
    suspend operator fun invoke(roleId: String): Result<Role?> =
        roleRepository.getRoleById(roleId)
}

class CreateRoleUseCase @Inject constructor(
    private val roleRepository: RoleRepository
) {
    suspend operator fun invoke(role: Role): Result<Role> =
        roleRepository.createRole(role)
}

class UpdateRoleUseCase @Inject constructor(
    private val roleRepository: RoleRepository
) {
    suspend operator fun invoke(role: Role): Result<Unit> =
        roleRepository.updateRole(role)
}

class DeleteRoleUseCase @Inject constructor(
    private val roleRepository: RoleRepository
) {
    suspend operator fun invoke(roleId: String): Result<Unit> =
        roleRepository.deleteRole(roleId)
}

class InitializeSystemRolesUseCase @Inject constructor(
    private val roleRepository: RoleRepository
) {
    suspend operator fun invoke(): Result<Unit> =
        roleRepository.initializeSystemRoles()
}

class GetSystemRolesUseCase @Inject constructor(
    private val roleRepository: RoleRepository
) {
    suspend operator fun invoke(): List<Role> =
        roleRepository.getSystemRoles()
}
