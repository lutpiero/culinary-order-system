plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val hasCiSigningConfig =
    !System.getenv("KEYSTORE_FILE").isNullOrBlank() &&
    !System.getenv("KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "com.culinary.orderapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.culinary.orderapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField(
            "String",
            "CLOUDINARY_CLOUD_NAME",
            "\"${project.findProperty("CLOUDINARY_CLOUD_NAME") ?: System.getenv("CLOUDINARY_CLOUD_NAME") ?: ""}\""
        )
        buildConfigField(
            "String",
            "CLOUDINARY_UPLOAD_PRESET",
            "\"${project.findProperty("CLOUDINARY_UPLOAD_PRESET") ?: System.getenv("CLOUDINARY_UPLOAD_PRESET") ?: ""}\""
        )
        buildConfigField(
            "String",
            "NETLIFY_FUNCTIONS_BASE_URL",
            "\"${project.findProperty("NETLIFY_FUNCTIONS_BASE_URL") ?: System.getenv("NETLIFY_FUNCTIONS_BASE_URL") ?: ""}\""
        )
    }
    
    signingConfigs {
        create("ci") {
            if (hasCiSigningConfig) {
                storeFile = file(System.getenv("KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )

            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    
    // Networking
    implementation(libs.okhttp)
    
    // Google Sign-In
    implementation(libs.play.services.auth)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Image Loading
    implementation(libs.coil.compose)

    // QR Code generation
    implementation(libs.zxing.core)

    // Permissions
    implementation(libs.accompanist.permissions)

    // DataStore
    implementation(libs.androidx.datastore)

    // WorkManager
    implementation(libs.androidx.work)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
