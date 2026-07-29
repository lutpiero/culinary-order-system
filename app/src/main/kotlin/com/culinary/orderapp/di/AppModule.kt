package com.culinary.orderapp.di

import com.culinary.orderapp.data.repository.AuthRepositoryImpl
import com.culinary.orderapp.data.repository.MenuRepositoryImpl
import com.culinary.orderapp.data.repository.OrderRepositoryImpl
import com.culinary.orderapp.data.repository.RoleRepositoryImpl
import com.culinary.orderapp.data.repository.SettingsRepositoryImpl
import com.culinary.orderapp.data.repository.UserRepositoryImpl
import com.culinary.orderapp.domain.repository.AuthRepository
import com.culinary.orderapp.domain.repository.MenuRepository
import com.culinary.orderapp.domain.repository.OrderRepository
import com.culinary.orderapp.domain.repository.RoleRepository
import com.culinary.orderapp.domain.repository.SettingsRepository
import com.culinary.orderapp.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = Firebase.firestore

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = Firebase.auth
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMenuRepository(impl: MenuRepositoryImpl): MenuRepository

    @Binds
    @Singleton
    abstract fun bindOrderRepository(impl: OrderRepositoryImpl): OrderRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindRoleRepository(impl: RoleRepositoryImpl): RoleRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {
    
    // Order Use Cases
    @Provides
    @Singleton
    fun provideObserveOrdersUseCase(repository: OrderRepository) = 
        com.culinary.orderapp.domain.usecase.ObserveOrdersUseCase(repository)
    
    @Provides
    @Singleton
    fun provideGetOrderByIdUseCase(repository: OrderRepository) = 
        com.culinary.orderapp.domain.usecase.GetOrderByIdUseCase(repository)
    
    @Provides
    @Singleton
    fun provideUpdateOrderStatusUseCase(repository: OrderRepository) = 
        com.culinary.orderapp.domain.usecase.UpdateOrderStatusUseCase(repository)
    
    @Provides
    @Singleton
    fun provideCancelOrderUseCase(repository: OrderRepository) = 
        com.culinary.orderapp.domain.usecase.CancelOrderUseCase(repository)
    
    @Provides
    @Singleton
    fun provideGetSalesSummaryUseCase(repository: OrderRepository) = 
        com.culinary.orderapp.domain.usecase.GetSalesSummaryUseCase(repository)
    
    // Menu Use Cases
    @Provides
    @Singleton
    fun provideObserveMenuItemsUseCase(repository: MenuRepository) = 
        com.culinary.orderapp.domain.usecase.ObserveMenuItemsUseCase(repository)
    
    @Provides
    @Singleton
    fun provideObserveCategoriesUseCase(repository: MenuRepository) = 
        com.culinary.orderapp.domain.usecase.ObserveCategoriesUseCase(repository)
    
    @Provides
    @Singleton
    fun provideSaveMenuItemUseCase(repository: MenuRepository) = 
        com.culinary.orderapp.domain.usecase.SaveMenuItemUseCase(repository)
    
    @Provides
    @Singleton
    fun provideDeleteMenuItemUseCase(repository: MenuRepository) = 
        com.culinary.orderapp.domain.usecase.DeleteMenuItemUseCase(repository)
    
    @Provides
    @Singleton
    fun provideToggleMenuItemAvailabilityUseCase(repository: MenuRepository) = 
        com.culinary.orderapp.domain.usecase.ToggleMenuItemAvailabilityUseCase(repository)
    
    @Provides
    @Singleton
    fun provideSaveCategoryUseCase(repository: MenuRepository) = 
        com.culinary.orderapp.domain.usecase.SaveCategoryUseCase(repository)
}
