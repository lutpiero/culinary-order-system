# Android App Fix Plan - Culinary Order System

## Executive Summary

This document outlines critical issues found in the Android application and provides a comprehensive fix plan. The app has several runtime errors that prevent it from functioning properly on Android devices.

## Critical Issues Identified

### 1. **Dependency Injection Issues (CRITICAL - App Crashes on Launch)**

**Problem:** ViewModels inject use cases, but there are no providers in the DI module.

**Impact:** App crashes immediately on launch with `MissingBinding` exception.

**Files Affected:**
- `app/src/main/kotlin/com/culinary/orderapp/di/AppModule.kt`

**Fix Required:**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {
    
    // Order Use Cases
    @Provides
    @Singleton
    fun provideObserveOrdersUseCase(repository: OrderRepository) = 
        ObserveOrdersUseCase(repository)
    
    @Provides
    @Singleton
    fun provideGetOrderByIdUseCase(repository: OrderRepository) = 
        GetOrderByIdUseCase(repository)
    
    @Provides
    @Singleton
    fun provideUpdateOrderStatusUseCase(repository: OrderRepository) = 
        UpdateOrderStatusUseCase(repository)
    
    @Provides
    @Singleton
    fun provideCancelOrderUseCase(repository: OrderRepository) = 
        CancelOrderUseCase(repository)
    
    @Provides
    @Singleton
    fun provideGetSalesSummaryUseCase(repository: OrderRepository) = 
        GetSalesSummaryUseCase(repository)
    
    // Menu Use Cases
    @Provides
    @Singleton
    fun provideObserveMenuItemsUseCase(repository: MenuRepository) = 
        ObserveMenuItemsUseCase(repository)
    
    @Provides
    @Singleton
    fun provideObserveCategoriesUseCase(repository: MenuRepository) = 
        ObserveCategoriesUseCase(repository)
    
    @Provides
    @Singleton
    fun provideSaveMenuItemUseCase(repository: MenuRepository) = 
        SaveMenuItemUseCase(repository)
    
    @Provides
    @Singleton
    fun provideDeleteMenuItemUseCase(repository: MenuRepository) = 
        DeleteMenuItemUseCase(repository)
    
    @Provides
    @Singleton
    fun provideToggleMenuItemAvailabilityUseCase(repository: MenuRepository) = 
        ToggleMenuItemAvailabilityUseCase(repository)
    
    @Provides
    @Singleton
    fun provideSaveCategoryUseCase(repository: MenuRepository) = 
        SaveCategoryUseCase(repository)
}
```

---

### 2. **Firebase Initialization Missing**

**Problem:** No proper Firebase initialization with error handling in Application class.

**Impact:** Firebase operations fail silently or crash the app.

**Files Affected:**
- `app/src/main/kotlin/com/culinary/orderapp/CulinaryApp.kt`

**Fix Required:**
```kotlin
package com.culinary.orderapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CulinaryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Firebase
        initializeFirebase()
        
        // Create notification channels
        createNotificationChannels()
    }
    
    private fun initializeFirebase() {
        try {
            FirebaseApp.initializeApp(this)
            
            // Configure Firestore settings
            val firestore = FirebaseFirestore.getInstance()
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                .build()
            firestore.firestoreSettings = settings
            
            Log.d(TAG, "Firebase initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase", e)
        }
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val orderChannel = NotificationChannel(
                CHANNEL_ORDERS,
                "Order Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new orders and order updates"
                enableVibration(true)
            }
            
            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General app notifications"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(orderChannel)
            notificationManager?.createNotificationChannel(generalChannel)
            
            Log.d(TAG, "Notification channels created")
        }
    }
    
    companion object {
        private const val TAG = "CulinaryApp"
        const val CHANNEL_ORDERS = "orders"
        const val CHANNEL_GENERAL = "general"
    }
}
```

---

### 3. **Firebase Cloud Messaging Service Issues**

**Problem:** FCM service lacks proper notification handling and channel support.

**Files Affected:**
- `app/src/main/kotlin/com/culinary/orderapp/data/remote/FirebaseMessagingService.kt`

**Fix Required:**
```kotlin
package com.culinary.orderapp.data.remote

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.culinary.orderapp.CulinaryApp
import com.culinary.orderapp.MainActivity
import com.culinary.orderapp.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        // TODO: Send token to your server if needed
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        Log.d(TAG, "Message received from: ${message.from}")
        
        // Handle data payload
        if (message.data.isNotEmpty()) {
            Log.d(TAG, "Message data: ${message.data}")
            handleDataMessage(message.data)
        }
        
        // Handle notification payload
        message.notification?.let {
            Log.d(TAG, "Message notification: ${it.title}")
            showNotification(it.title, it.body)
        }
    }
    
    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"] ?: return
        
        when (type) {
            "new_order" -> {
                val orderId = data["orderId"]
                showNotification(
                    "Pesanan Baru",
                    "Pesanan baru telah masuk. Tap untuk melihat detail.",
                    orderId
                )
            }
            "order_update" -> {
                val orderId = data["orderId"]
                val status = data["status"]
                showNotification(
                    "Update Pesanan",
                    "Status pesanan telah diperbarui: $status",
                    orderId
                )
            }
        }
    }
    
    private fun showNotification(
        title: String?,
        body: String?,
        orderId: String? = null
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            orderId?.let { putExtra("orderId", it) }
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            pendingIntentFlags
        )
        
        val notification = NotificationCompat.Builder(this, CulinaryApp.CHANNEL_ORDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: "Culinary Order")
            .setContentText(body ?: "")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
    
    companion object {
        private const val TAG = "FCMService"
        private const val NOTIFICATION_ID = 1001
    }
}
```

---

### 4. **ProGuard Rules Incomplete**

**Problem:** Missing ProGuard rules for critical dependencies.

**Impact:** Release builds crash due to obfuscation removing required classes.

**Files Affected:**
- `app/proguard-rules.pro`

**Fix Required:**
```proguard
# Add project specific ProGuard rules here.

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Firestore
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
}
-keepclassmembers class * {
    @com.google.firebase.firestore.ServerTimestamp <fields>;
}

# Hilt
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Data classes (used with Firestore)
-keep class com.culinary.orderapp.data.model.** { *; }
-keep class com.culinary.orderapp.domain.model.** { *; }
-keepclassmembers class com.culinary.orderapp.data.model.** { *; }
-keepclassmembers class com.culinary.orderapp.domain.model.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coil
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class coil.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Retrofit (if used in future)
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Gson (if used)
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ZXing (QR Code)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Accompanist
-keep class com.google.accompanist.** { *; }

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Room (if used)
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.InputMerger
-keep class androidx.work.** { *; }

# DataStore
-keep class androidx.datastore.*.** { *; }

# General
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keepattributes Exceptions
```

---

### 5. **Runtime Permission Handling Missing**

**Problem:** No runtime permission handling for Camera and Notifications.

**Impact:** Features requiring permissions fail silently.

**Solution:** Create a permission utility class.

**New File:** `app/src/main/kotlin/com/culinary/orderapp/util/PermissionUtils.kt`

```kotlin
package com.culinary.orderapp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {
    
    val CAMERA_PERMISSION = Manifest.permission.CAMERA
    
    val NOTIFICATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            CAMERA_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required for older versions
        }
    }
    
    fun getRequiredPermissions(): List<String> {
        return buildList {
            add(CAMERA_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
```

---

### 6. **Error Handling in Repositories**

**Problem:** Repositories don't handle network errors, offline scenarios, or Firestore exceptions properly.

**Impact:** App crashes when network is unavailable or Firestore operations fail.

**Fix Required:** Add try-catch blocks and proper error handling in all repository methods.

**Example for OrderRepositoryImpl:**

```kotlin
override suspend fun getOrderById(id: String): Order? {
    return try {
        ordersCollection.document(id).get().await()
            .toObject(OrderDto::class.java)?.copy(id = id)?.toDomain()
    } catch (e: Exception) {
        Log.e("OrderRepository", "Error fetching order $id", e)
        null
    }
}

override suspend fun createOrder(order: Order): Result<Order> {
    return try {
        val dto = OrderDto.fromDomain(order)
        val docRef = ordersCollection.add(dto).await()
        Result.success(order.copy(id = docRef.id))
    } catch (e: Exception) {
        Log.e("OrderRepository", "Error creating order", e)
        Result.failure(e)
    }
}
```

---

### 7. **ViewModel Error Handling**

**Problem:** ViewModels don't properly handle errors from repositories.

**Impact:** Users see no feedback when operations fail.

**Fix Required:** Add proper error state handling in all ViewModels.

**Example:**

```kotlin
fun loadOrders(status: OrderStatus? = null) {
    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        try {
            observeOrders(status)
                .catch { e ->
                    Log.e("OrdersViewModel", "Error observing orders", e)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Gagal memuat pesanan: ${e.message}"
                    )
                }
                .collect { orders ->
                    _uiState.value = _uiState.value.copy(
                        orders = orders,
                        isLoading = false,
                        errorMessage = null
                    )
                }
        } catch (e: Exception) {
            Log.e("OrdersViewModel", "Unexpected error", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Terjadi kesalahan: ${e.message}"
            )
        }
    }
}
```

---

### 8. **Missing UI Error States**

**Problem:** UI screens don't show loading states or error messages properly.

**Impact:** Poor user experience, users don't know what's happening.

**Fix Required:** Add error and loading UI components to all screens.

**Example Component:**

```kotlin
@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("Coba Lagi")
        }
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
```

---

### 9. **Data Validation Missing**

**Problem:** No validation in domain models or before saving to Firestore.

**Impact:** Invalid data can be saved, causing crashes or data corruption.

**Fix Required:** Add validation methods to domain models.

**Example:**

```kotlin
data class MenuItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val price: Long = 0L,
    val categoryId: String = "",
    val imageUrl: String = "",
    val isAvailable: Boolean = true,
    val toppingGroups: List<ToppingGroup> = emptyList(),
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
) {
    fun validate(): Result<Unit> {
        return when {
            name.isBlank() -> Result.failure(
                IllegalArgumentException("Nama menu tidak boleh kosong")
            )
            price <= 0 -> Result.failure(
                IllegalArgumentException("Harga harus lebih dari 0")
            )
            categoryId.isBlank() -> Result.failure(
                IllegalArgumentException("Kategori harus dipilih")
            )
            else -> Result.success(Unit)
        }
    }
}
```

---

### 10. **Logging Infrastructure**

**Problem:** No consistent logging throughout the app.

**Impact:** Difficult to debug issues in production.

**Solution:** Create a logging utility.

**New File:** `app/src/main/kotlin/com/culinary/orderapp/util/Logger.kt`

```kotlin
package com.culinary.orderapp.util

import android.util.Log
import com.culinary.orderapp.BuildConfig

object Logger {
    
    private const val TAG = "CulinaryApp"
    
    fun d(message: String, tag: String = TAG) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }
    
    fun e(message: String, throwable: Throwable? = null, tag: String = TAG) {
        Log.e(tag, message, throwable)
    }
    
    fun i(message: String, tag: String = TAG) {
        if (BuildConfig.DEBUG) {
            Log.i(tag, message)
        }
    }
    
    fun w(message: String, tag: String = TAG) {
        Log.w(tag, message)
    }
}
```

---

## Implementation Priority

### Phase 1: Critical Fixes (Must be done first)
1. ✅ Add Use Case providers to DI module
2. ✅ Add Firebase initialization in CulinaryApp
3. ✅ Fix FCM service with notification channels
4. ✅ Update ProGuard rules

### Phase 2: Essential Improvements
5. ✅ Add permission handling utilities
6. ✅ Implement error handling in repositories
7. ✅ Add error handling in ViewModels
8. ✅ Create error/loading UI components

### Phase 3: Quality & Polish
9. ✅ Add data validation
10. ✅ Implement logging infrastructure
11. Add comprehensive error messages
12. Add offline support indicators

---

## Testing Checklist

After implementing fixes, test the following:

- [ ] App launches without crashes
- [ ] Firebase connection works
- [ ] Orders screen loads and displays data
- [ ] Menu management works
- [ ] QR code generation works
- [ ] Notifications are received
- [ ] Camera permission is requested when needed
- [ ] Notification permission is requested (Android 13+)
- [ ] App works offline (cached data)
- [ ] Error messages are user-friendly
- [ ] Release build works (ProGuard doesn't break anything)

---

## Additional Recommendations

### 1. Add Crashlytics
Add Firebase Crashlytics for production crash reporting:

```kotlin
// In build.gradle.kts
implementation("com.google.firebase:firebase-crashlytics-ktx")

// In CulinaryApp.kt
FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
```

### 2. Add Analytics
Track user behavior with Firebase Analytics:

```kotlin
implementation("com.google.firebase:firebase-analytics-ktx")
```

### 3. Add Network Monitoring
Monitor network connectivity:

```kotlin
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isConnected: Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }
            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager?.unregisterNetworkCallback(callback) }
    }
}
```

### 4. Add Retry Logic
Implement exponential backoff for failed operations:

```kotlin
suspend fun <T> retryWithExponentialBackoff(
    maxRetries: Int = 3,
    initialDelay: Long = 1000,
    maxDelay: Long = 10000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(maxRetries - 1) {
        try {
            return block()
        } catch (e: Exception) {
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return block() // Last attempt
}
```

---

## Conclusion

The Android app has several critical issues that prevent it from running properly. The most critical issue is the missing Dependency Injection providers for use cases, which causes immediate crashes. 

All fixes are documented above with code examples. Implementation should follow the priority order to ensure the app becomes functional as quickly as possible.

After implementing Phase 1 fixes, the app should launch and basic functionality should work. Phases 2 and 3 will improve stability, user experience, and maintainability.
