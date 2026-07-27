package com.culinary.orderapp.domain.repository

import com.culinary.orderapp.domain.model.Category
import com.culinary.orderapp.domain.model.MenuItem
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for menu-related operations.
 */
interface MenuRepository {

    /** Emits a live list of all available categories. */
    fun observeCategories(): Flow<List<Category>>

    /** Emits a live list of all menu items, optionally filtered by category. */
    fun observeMenuItems(categoryId: String? = null): Flow<List<MenuItem>>

    /** Fetches a single menu item by its ID. */
    suspend fun getMenuItemById(id: String): MenuItem?

    /** Creates or updates a menu item in the backend. */
    suspend fun saveMenuItem(item: MenuItem): Result<MenuItem>

    /** Removes a menu item from the backend. */
    suspend fun deleteMenuItem(id: String): Result<Unit>

    /** Toggles the availability flag of a menu item. */
    suspend fun toggleMenuItemAvailability(id: String, isAvailable: Boolean): Result<Unit>

    /** Creates or updates a category. */
    suspend fun saveCategory(category: Category): Result<Category>

    /** Removes a category. */
    suspend fun deleteCategory(id: String): Result<Unit>
}
