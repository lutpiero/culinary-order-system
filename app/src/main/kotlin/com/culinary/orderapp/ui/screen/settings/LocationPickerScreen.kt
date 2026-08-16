package com.culinary.orderapp.ui.screen.settings

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.culinary.orderapp.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.coroutines.resume

/**
 * Full-screen OpenStreetMap (osmdroid) location picker for the business
 * location. The seller taps the map (or uses their current location) to
 * place a pin; the radius circle (if already configured) is drawn for
 * reference. Confirming returns the picked coordinates via [onLocationPicked].
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerScreen(
    initialLatitude: Double?,
    initialLongitude: Double?,
    initialRadiusMeters: Int?,
    onLocationPicked: (Double, Double) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mapView by remember { mutableStateOf<OsmMapView?>(null) }
    var latitude by remember { mutableStateOf(initialLatitude) }
    var longitude by remember { mutableStateOf(initialLongitude) }
    var isLocating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val finePermission = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION) {
        if (!it) {
            isLocating = false
            errorMessage = "Izin lokasi diperlukan untuk memilih lokasi bisnis."
        }
    }
    val coarsePermission = rememberPermissionState(Manifest.permission.ACCESS_COARSE_LOCATION) {
        if (!it) {
            isLocating = false
            errorMessage = "Izin lokasi diperlukan untuk memilih lokasi bisnis."
        }
    }
    val hasLocationPermission =
        finePermission.status is PermissionStatus.Granted ||
            coarsePermission.status is PermissionStatus.Granted

    suspend fun locate() {
        val point = getCurrentLocation(context)
        isLocating = false
        if (point != null) {
            latitude = point.latitude
            longitude = point.longitude
            mapView?.moveTo(point)
        } else {
            errorMessage = "Tidak dapat memperoleh lokasi. Pastikan GPS aktif."
        }
    }

    fun useMyLocation() {
        errorMessage = null
        if (!hasLocationPermission) {
            isLocating = true
            finePermission.launchPermissionRequest()
            return
        }
        scope.launch {
            isLocating = true
            locate()
        }
    }

    fun confirm() {
        val lat = latitude
        val lng = longitude
        if (lat != null && lng != null) {
            onLocationPicked(lat, lng)
            onBack()
        } else {
            errorMessage = "Pilih titik lokasi di peta, lalu tekan Simpan."
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && isLocating) {
            locate()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Pilih Lokasi Bisnis", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorMessage != null) {
                    Text(
                        errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { useMyLocation() },
                        modifier = Modifier.weight(1f),
                        enabled = !isLocating
                    ) {
                        if (isLocating) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 4.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                        }
                        Text("Lokasi Saya", modifier = Modifier.padding(start = 4.dp))
                    }
                    Button(
                        onClick = { confirm() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("Simpan", modifier = Modifier.padding(start = 4.dp))
                    }
                }

                if (latitude != null && longitude != null) {
                    Text(
                        text = String.format("%.6f, %.6f", latitude, longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        AndroidView(
            factory = {
                OsmMapView(
                    context = context,
                    initialLatitude = initialLatitude,
                    initialLongitude = initialLongitude,
                    initialRadiusMeters = initialRadiusMeters,
                    onLocationChanged = { lat, lng ->
                        latitude = lat
                        longitude = lng
                    }
                ).also { mapView = it }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

private suspend fun getCurrentLocation(context: Context): GeoPoint? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    for (provider in providers) {
        val last = try {
            locationManager.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            null
        }
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000L) {
            return last.toGeoPoint()
        }
    }

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var resolved = false
            var active = false
            val handler = Handler(Looper.getMainLooper())
            var cleanup: (() -> Unit)? = null

            val listener = LocationListener { location ->
                if (!resolved) {
                    resolved = true
                    cleanup?.invoke()
                    if (!cont.isCancelled) cont.resume(location.toGeoPoint())
                }
            }
            val timeout = Runnable {
                if (!resolved) {
                    resolved = true
                    cleanup?.invoke()
                    if (!cont.isCancelled) cont.resume(null)
                }
            }

            cleanup = {
                handler.removeCallbacks(timeout)
                if (active) {
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }
            }

            for (provider in providers) {
                try {
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    active = true
                } catch (_: SecurityException) {
                } catch (_: IllegalArgumentException) {
                }
            }
            if (!active) {
                cleanup?.invoke()
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            handler.postDelayed(timeout, 8000)
            cont.invokeOnCancellation { cleanup?.invoke() }
        }
    }
}

private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

private class OsmMapView(
    context: Context,
    initialLatitude: Double?,
    initialLongitude: Double?,
    initialRadiusMeters: Int?,
    onLocationChanged: (Double, Double) -> Unit
) : MapView(context) {

    private val marker = Marker(this)
    private var radiusCircle: Polygon? = null

    init {
        Configuration.getInstance()
            .load(context, android.preference.PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.icon = ContextCompat.getDrawable(context, R.drawable.ic_location_pin)
        overlays.add(marker)

        if (initialLatitude != null && initialLongitude != null) {
            val point = GeoPoint(initialLatitude, initialLongitude)
            controller.setCenter(point)
            controller.setZoom(17.0)
            updateMarker(point, onLocationChanged)
            setRadius(initialRadiusMeters)
        } else {
            controller.setZoom(15.0)
        }

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                point?.let { updateMarker(it, onLocationChanged) }
                return true
            }

            override fun longPressHelper(point: GeoPoint?): Boolean = false
        })
        overlays.add(eventsOverlay)
    }

    fun moveTo(point: GeoPoint) {
        updateMarker(point, null)
        controller.animateTo(point)
    }

    private fun updateMarker(point: GeoPoint, onLocationChanged: ((Double, Double) -> Unit)?) {
        marker.position = point
        marker.title = String.format("%.6f, %.6f", point.latitude, point.longitude)
        onLocationChanged?.invoke(point.latitude, point.longitude)
        invalidate()
    }

    fun setRadius(radiusMeters: Int?) {
        radiusCircle?.let { overlays.remove(it) }
        radiusCircle = null
        val center = marker.position ?: return
        if (radiusMeters != null && radiusMeters > 0) {
            val circle = Polygon(this).apply {
                setPoints(Polygon.pointsAsCircle(center, radiusMeters.toDouble()))
                fillPaint.color = 0x2200A050.toInt()
                outlinePaint.color = 0xFF00A050.toInt()
                outlinePaint.strokeWidth = 2f
            }
            overlays.add(circle)
            radiusCircle = circle
        }
        invalidate()
    }
}
