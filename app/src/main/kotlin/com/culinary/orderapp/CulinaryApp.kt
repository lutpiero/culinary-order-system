package com.culinary.orderapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class. Hilt uses this as the root of the dependency graph.
 */
@HiltAndroidApp
class CulinaryApp : Application()
