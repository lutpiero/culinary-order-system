package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.CategoryDto
import com.culinary.orderapp.data.model.MenuItemDto
import com.culinary.orderapp.domain.model.Category
import com.culinary.orderapp.domain.model.MenuItem
import com.culinary.orderapp.domain.repository.MenuRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MenuRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore
) : MenuRepository {

    private val categoriesCollection get() = firestore.collection("categories")
    private val menuItemsCollection get() = firestore.collection("menuItems")

    override fun observeCategories(): Flow<List<Category>> = callbackFlow {
        val listener = categoriesCollection
            .orderBy("displayOrder", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val categories = snapshot?.documents?.mapNotNull { doc ->
                    runCatching {
                        doc.toObject(CategoryDto::class.java)?.copy(id = doc.id)?.toDomain()
                    }.getOrNull()
                } ?: emptyList()
                trySend(categories)
            }
        awaitClose { listener.remove() }
    }

    override fun observeMenuItems(categoryId: String?): Flow<List<MenuItem>> = callbackFlow {
        val query = if (categoryId != null) {
            menuItemsCollection.whereEqualTo("categoryId", categoryId)
        } else {
            menuItemsCollection
        }
        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val items = snapshot?.documents?.mapNotNull { doc ->
                runCatching {
                    doc.toObject(MenuItemDto::class.java)?.copy(id = doc.id)?.toDomain()
                }.getOrNull()
            } ?: emptyList()
            trySend(items)
        }
        awaitClose { listener.remove() }
    }

    override suspend fun getMenuItemById(id: String): MenuItem? {
        return runCatching {
            menuItemsCollection.document(id).get().await()
                .toObject(MenuItemDto::class.java)?.copy(id = id)?.toDomain()
        }.getOrNull()
    }

    override suspend fun saveMenuItem(item: MenuItem): Result<MenuItem> {
        return runCatching {
            val dto = MenuItemDto.fromDomain(item)
            if (item.id.isEmpty()) {
                val docRef = menuItemsCollection.add(dto).await()
                item.copy(id = docRef.id)
            } else {
                menuItemsCollection.document(item.id).set(dto).await()
                item
            }
        }
    }

    override suspend fun deleteMenuItem(id: String): Result<Unit> {
        return runCatching {
            menuItemsCollection.document(id).delete().await()
        }
    }

    override suspend fun toggleMenuItemAvailability(
        id: String,
        isAvailable: Boolean
    ): Result<Unit> {
        return runCatching {
            menuItemsCollection.document(id)
                .update("isAvailable", isAvailable)
                .await()
        }
    }

    override suspend fun saveCategory(category: Category): Result<Category> {
        return runCatching {
            val dto = CategoryDto.fromDomain(category)
            if (category.id.isEmpty()) {
                val docRef = categoriesCollection.add(dto).await()
                category.copy(id = docRef.id)
            } else {
                categoriesCollection.document(category.id).set(dto).await()
                category
            }
        }
    }

    override suspend fun deleteCategory(id: String): Result<Unit> {
        return runCatching {
            categoriesCollection.document(id).delete().await()
        }
    }
}
