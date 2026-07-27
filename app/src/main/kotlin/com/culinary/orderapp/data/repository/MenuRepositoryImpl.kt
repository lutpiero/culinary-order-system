package com.culinary.orderapp.data.repository

import com.culinary.orderapp.data.model.CategoryDto
import com.culinary.orderapp.data.model.MenuItemDto
import com.culinary.orderapp.domain.model.Category
import com.culinary.orderapp.domain.model.MenuItem
import com.culinary.orderapp.domain.repository.MenuRepository
import com.culinary.orderapp.util.Logger
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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
                    Logger.e("Error observing categories", error, TAG)
                    close(error)
                    return@addSnapshotListener
                }
                val categories = snapshot?.documents?.mapNotNull { doc ->
                    runCatching {
                        doc.toObject(CategoryDto::class.java)?.copy(id = doc.id)?.toDomain()
                    }.onFailure { e ->
                        Logger.e("Error parsing category document ${doc.id}", e, TAG)
                    }.getOrNull()
                } ?: emptyList()
                Logger.d("Loaded ${categories.size} categories", TAG)
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
                Logger.e("Error observing menu items", error, TAG)
                close(error)
                return@addSnapshotListener
            }
            val items = snapshot?.documents?.mapNotNull { doc ->
                runCatching {
                    doc.toObject(MenuItemDto::class.java)?.copy(id = doc.id)?.toDomain()
                }.onFailure { e ->
                    Logger.e("Error parsing menu item document ${doc.id}", e, TAG)
                }.getOrNull()
            } ?: emptyList()
            Logger.d("Loaded ${items.size} menu items", TAG)
            trySend(items)
        }
        awaitClose { listener.remove() }
    }

    override suspend fun getMenuItemById(id: String): MenuItem? {
        return try {
            Logger.d("Fetching menu item: $id", TAG)
            val item = menuItemsCollection.document(id).get().await()
                .toObject(MenuItemDto::class.java)?.copy(id = id)?.toDomain()
            if (item == null) {
                Logger.w("Menu item not found: $id", TAG)
            }
            item
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error fetching menu item $id", e, TAG)
            null
        } catch (e: Exception) {
            Logger.e("Unexpected error fetching menu item $id", e, TAG)
            null
        }
    }

    override suspend fun saveMenuItem(item: MenuItem): Result<MenuItem> {
        return try {
            Logger.d("Saving menu item: ${item.name}", TAG)
            val dto = MenuItemDto.fromDomain(item)
            val savedItem = if (item.id.isEmpty()) {
                val docRef = menuItemsCollection.add(dto).await()
                Logger.i("Menu item created: ${docRef.id}", TAG)
                item.copy(id = docRef.id)
            } else {
                menuItemsCollection.document(item.id).set(dto).await()
                Logger.i("Menu item updated: ${item.id}", TAG)
                item
            }
            Result.success(savedItem)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error saving menu item", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error saving menu item", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun deleteMenuItem(id: String): Result<Unit> {
        return try {
            Logger.d("Deleting menu item: $id", TAG)
            menuItemsCollection.document(id).delete().await()
            Logger.i("Menu item deleted successfully", TAG)
            Result.success(Unit)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error deleting menu item", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error deleting menu item", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun toggleMenuItemAvailability(
        id: String,
        isAvailable: Boolean
    ): Result<Unit> {
        return try {
            Logger.d("Toggling menu item $id availability to $isAvailable", TAG)
            menuItemsCollection.document(id)
                .update("isAvailable", isAvailable)
                .await()
            Logger.i("Menu item availability updated", TAG)
            Result.success(Unit)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error toggling availability", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error toggling availability", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun saveCategory(category: Category): Result<Category> {
        return try {
            Logger.d("Saving category: ${category.name}", TAG)
            val dto = CategoryDto.fromDomain(category)
            val savedCategory = if (category.id.isEmpty()) {
                val docRef = categoriesCollection.add(dto).await()
                Logger.i("Category created: ${docRef.id}", TAG)
                category.copy(id = docRef.id)
            } else {
                categoriesCollection.document(category.id).set(dto).await()
                Logger.i("Category updated: ${category.id}", TAG)
                category
            }
            Result.success(savedCategory)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error saving category", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error saving category", e, TAG)
            Result.failure(e)
        }
    }

    override suspend fun deleteCategory(id: String): Result<Unit> {
        return try {
            Logger.d("Deleting category: $id", TAG)
            categoriesCollection.document(id).delete().await()
            Logger.i("Category deleted successfully", TAG)
            Result.success(Unit)
        } catch (e: FirebaseFirestoreException) {
            Logger.e("Firestore error deleting category", e, TAG)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e("Unexpected error deleting category", e, TAG)
            Result.failure(e)
        }
    }
    
    companion object {
        private const val TAG = "MenuRepository"
    }
}
