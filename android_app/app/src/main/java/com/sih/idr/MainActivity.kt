package com.sih.idr

import android.location.Geocoder
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.util.BoundingBox

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var inputFrom: AutoCompleteTextView
    private lateinit var inputTo: AutoCompleteTextView
    private lateinit var btnGetRoute: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var btnRecenter: FloatingActionButton
    private lateinit var btnStartNavigation: Button

    private val routingManager = RoutingManager()
    
    private var currentRouteOverlay: Polyline? = null
    private var vehicleMarker: Marker? = null
    private var isNavigating = false
    private var targetLat: Double = 0.0
    private var targetLon: Double = 0.0

    // Pointer drawables and state tracking for GPS vs AI Dead Reckoning
    private var onlinePointerDrawable: Drawable? = null
    private var deadReckoningPointerDrawable: Drawable? = null
    private var currentPointerState: NavigationEngine.NavState? = null

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Global Exception Handler
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("FATAL_ERROR", "Uncaught exception", throwable)
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                putExtra("CRASH_ERROR", throwable.toString() + "\n\n" + throwable.stackTraceToString().take(500))
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            kotlin.system.exitProcess(1)
        }
        
        intent.getStringExtra("CRASH_ERROR")?.let { errorMsg ->
            android.app.AlertDialog.Builder(this)
                .setTitle("App Crashed!")
                .setMessage(errorMsg)
                .setPositiveButton("OK", null)
                .show()
        }
        
        // Initialize OSMDroid config
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        inputFrom = findViewById(R.id.inputFrom)
        inputTo = findViewById(R.id.inputTo)
        btnGetRoute = findViewById(R.id.btnGetRoute)
        tvStatus = findViewById(R.id.tvStatus)
        tvDistance = findViewById(R.id.tvDistance)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnStartNavigation = findViewById(R.id.btnStartNavigation)

                btnStartNavigation.setOnClickListener {
            isNavigating = true
            vehicleMarker?.let { marker ->
                mapView.controller.animateTo(marker.position)
                mapView.controller.setZoom(19.0)
                tvStatus.text = "Navigation Started! Following vehicle..."
            }
        }

        // Setup Map
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        
        // Set default location to India (center)
        val defaultPoint = GeoPoint(20.5937, 78.9629)
        mapView.controller.setCenter(defaultPoint)

        btnGetRoute.setOnClickListener {
            handleRouteRequest()
        }

        btnRecenter.setOnClickListener {
            vehicleMarker?.let { marker ->
                mapView.controller.animateTo(marker.position)
                mapView.controller.setZoom(18.0)
            }
        }

        setupAutoComplete(inputFrom)
        setupAutoComplete(inputTo)

        // Setup Navigation Engine Callback
        SensorService.navigationEngine.onLocationUpdated = { lat, lon, state ->
            runOnUiThread {
                updateVehicleLocationOnMap(lat, lon, state)
            }
        }
        
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            startSensorService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                startSensorService()
            } else {
                Toast.makeText(this, "Location permissions are required for this app to work.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startSensorService() {
        val intent = android.content.Intent(this, SensorService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private var routeInfo: String = ""

    private fun handleRouteRequest() {
        val fromQuery = inputFrom.text.toString()
        val toQuery = inputTo.text.toString()

        if (toQuery.isEmpty()) {
            android.widget.Toast.makeText(this, "Please enter a destination", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Geocoding addresses..."
        
        Thread {
            val startCoords = if (fromQuery.isEmpty() || fromQuery.lowercase() == "my location") {
                if (SensorService.navigationEngine.currentLat != 0.0) {
                    Pair(SensorService.navigationEngine.currentLat, SensorService.navigationEngine.currentLon)
                } else {
                    null // Will trigger error below
                }
            } else {
                getCoordinatesFromNominatim(fromQuery)
            }
            
            val endCoords = getCoordinatesFromNominatim(toQuery)

                        if (startCoords != null && endCoords != null) {
                runOnUiThread {
                    tvStatus.text = "Fetching best route (Google Maps style)..."
                }

                // Online Route fetching
                routingManager.getRoute(startCoords.first, startCoords.second, endCoords.first, endCoords.second, object : RoutingManager.RouteCallback {
                    override fun onRouteFound(routeCoordinates: List<Pair<Double, Double>>, distanceMeters: Double) {
                        runOnUiThread {
                            drawRouteOnMap(routeCoordinates)
                            tvDistance.text = String.format("%.1f km", distanceMeters / 1000.0)
                            tvStatus.text = "Route Found. Online (GNSS)"
                            tvStatus.setTextColor(android.graphics.Color.parseColor("#555555"))
                            
                            // Initialize AI Engine Position to start of route
                            SensorService.navigationEngine.currentLat = startCoords.first
                            SensorService.navigationEngine.currentLon = startCoords.second
                            targetLat = endCoords.first
                            targetLon = endCoords.second
                        }
                    }
                    override fun onError(error: String) {
                        runOnUiThread { tvStatus.text = "Routing failed: $error" }
                    }
                })
            } else {
                runOnUiThread {
                    tvStatus.text = "Location not found! Try being more specific."
                    tvStatus.setTextColor(android.graphics.Color.RED)
                }
            }
            }.start()
    }

    private fun setupAutoComplete(autoCompleteTextView: AutoCompleteTextView) {
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        autoCompleteTextView.setAdapter(adapter)

        autoCompleteTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString()
                if (query.length >= 3) {
                    Thread {
                        try {
                                                        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                            val url = java.net.URL("https://photon.komoot.io/api/?q=$encodedQuery&limit=5&bbox=68.1,6.7,97.4,35.5")
                            val connection = url.openConnection() as java.net.HttpURLConnection
                            connection.setRequestProperty("User-Agent", "SIH-IDR-App")
                            if (connection.responseCode == 200) {
                                val response = connection.inputStream.bufferedReader().readText()
                                val jsonObject = org.json.JSONObject(response)
                                val features = jsonObject.getJSONArray("features")
                                val suggestions = mutableListOf<String>()
                                for (i in 0 until features.length()) {
                                    val feature = features.getJSONObject(i)
                                    val props = feature.getJSONObject("properties")
                                    val name = props.optString("name", "")
                                    val city = props.optString("city", props.optString("state", ""))
                                    val displayName = if (name.isNotEmpty() && city.isNotEmpty()) "$name, $city" else if (name.isNotEmpty()) name else city
                                    if (displayName.isNotEmpty()) suggestions.add(displayName)

                                    
                                }
                                runOnUiThread {
                                    adapter.clear()
                                    adapter.addAll(suggestions)
                                    adapter.notifyDataSetChanged()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.start()
                }
            }
        })
    }

        private fun getCoordinatesFromNominatim(query: String): Pair<Double, Double>? {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = java.net.URL("https://photon.komoot.io/api/?q=$encodedQuery&limit=1&bbox=68.1,6.7,97.4,35.5")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "SIH-IDR-App")
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonObject = org.json.JSONObject(response)
                val features = jsonObject.getJSONArray("features")
                if (features.length() > 0) {
                    val coords = features.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                    // Photon returns [lon, lat]
                    return Pair(coords.getDouble(1), coords.getDouble(0))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun drawRouteOnMap(routeCoordinates: List<Pair<Double, Double>>) {
        if (currentRouteOverlay != null) {
            mapView.overlays.remove(currentRouteOverlay)
        }

        val geoPoints = routeCoordinates.map { GeoPoint(it.first, it.second) }
        
        currentRouteOverlay = Polyline().apply {
            setPoints(geoPoints)
            color = android.graphics.Color.parseColor("#1976D2") // Google Maps Blue
            width = 12f
        }

        mapView.overlays.add(currentRouteOverlay)
        mapView.invalidate()

        if (geoPoints.isNotEmpty()) {
            val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
            // Zoom to fit the route with a 150-pixel padding
            mapView.zoomToBoundingBox(boundingBox, true, 150)
        }
    }

    /**
     * Generates a navigation pointer icon with high visibility:
     * - Blue (#1E88E5) when GPS is Online & Active.
     * - Bright Alert Orange/Red (#FF3D00) when GPS Blackout occurs and AI Dead Reckoning is Active.
     */
    private fun getVehiclePointerDrawable(isDeadReckoning: Boolean): Drawable {
        if (isDeadReckoning && deadReckoningPointerDrawable != null) {
            return deadReckoningPointerDrawable!!
        }
        if (!isDeadReckoning && onlinePointerDrawable != null) {
            return onlinePointerDrawable!!
        }

        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colorPrimary = if (isDeadReckoning) {
            Color.parseColor("#FF3D00") // Bright Orange/Red for AI Dead Reckoning
        } else {
            Color.parseColor("#1E88E5") // Vibrant Blue for Online GNSS / GPS
        }
        val haloColor = if (isDeadReckoning) {
            Color.parseColor("#44FF3D00") // Translucent red alert halo
        } else {
            Color.parseColor("#441E88E5") // Translucent blue halo
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val cy = size / 2f

        // 1. Soft pulse / halo ring around pointer
        paint.style = Paint.Style.FILL
        paint.color = haloColor
        canvas.drawCircle(cx, cy, 22 * density, paint)

        // 2. White outer border ring for crisp contrast on any map background
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 14 * density, paint)

        // 3. Main colored center circle
        paint.color = colorPrimary
        canvas.drawCircle(cx, cy, 11 * density, paint)

        // 4. White center core dot
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 4 * density, paint)

        // 5. Direction heading arrow / pointer
        val arrowPath = Path().apply {
            moveTo(cx, cy - 20 * density) // Tip pointing forward
            lineTo(cx - 7 * density, cy - 10 * density)
            lineTo(cx + 7 * density, cy - 10 * density)
            close()
        }
        paint.color = colorPrimary
        canvas.drawPath(arrowPath, paint)

        val drawable = BitmapDrawable(resources, bitmap)
        if (isDeadReckoning) {
            deadReckoningPointerDrawable = drawable
        } else {
            onlinePointerDrawable = drawable
        }
        return drawable
    }

    private fun updateVehicleLocationOnMap(lat: Double, lon: Double, state: NavigationEngine.NavState) {
        if (lat == 0.0 && lon == 0.0) return // Ignore uninitialized coordinates
        
        val newPoint = GeoPoint(lat, lon)
        val isDeadReckoning = (state == NavigationEngine.NavState.OFFLINE_ML_DEAD_RECKONING)

        if (vehicleMarker == null) {
            vehicleMarker = Marker(mapView).apply {
                position = newPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = getVehiclePointerDrawable(isDeadReckoning)
                title = if (isDeadReckoning) "Offline (AI/ML Dead Reckoning)" else "Online (GNSS)"
                mapView.overlays.add(this)
            }
            currentPointerState = state
        } else {
            vehicleMarker?.position = newPoint
            // Dynamically change pointer icon color when switching between GPS and AI Dead Reckoning
            if (currentPointerState != state) {
                vehicleMarker?.icon = getVehiclePointerDrawable(isDeadReckoning)
                currentPointerState = state
            }
        }

        // Update heading rotation (in degrees clockwise from North)
        val headingDeg = Math.toDegrees(SensorService.navigationEngine.currentHeadingRad).toFloat()
        vehicleMarker?.rotation = headingDeg
        
        // Follow the vehicle if navigation started!
        if (isNavigating) {
            mapView.controller.animateTo(newPoint)
            
            // Trim the blue line behind the vehicle
            currentRouteOverlay?.let { polyline ->
                val points = polyline.actualPoints
                if (points.size > 1) {
                    val windowSize = kotlin.math.min(20, points.size)
                    var closestIdx = 0
                    var minDistance = Float.MAX_VALUE
                    for (i in 0 until windowSize) {
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(lat, lon, points[i].latitude, points[i].longitude, results)
                        if (results[0] < minDistance) {
                            minDistance = results[0]
                            closestIdx = i
                        }
                    }
                    if (closestIdx > 0) {
                        val newPoints = ArrayList(points.subList(closestIdx, points.size))
                        polyline.setPoints(newPoints)
                    }
                }
            }
        }

        // Change UI based on whether we are online or offline AI/ML mode
        if (state == NavigationEngine.NavState.ONLINE_GNSS) {
            vehicleMarker?.title = "Online (GNSS)"
            vehicleMarker?.snippet = "GPS Signal Normal"
            tvStatus.setTextColor(Color.parseColor("#1E88E5"))
            tvStatus.text = "System Status: Online (GPS Active)"
        } else {
            vehicleMarker?.title = "Offline (AI/ML Dead Reckoning)"
            vehicleMarker?.snippet = "GPS Blackout - AI Dead Reckoning Active"
            tvStatus.setTextColor(Color.parseColor("#FF3D00"))
            tvStatus.text = "⚠️ GPS Blackout! AI Dead Reckoning Active"
        }

        mapView.invalidate()
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
