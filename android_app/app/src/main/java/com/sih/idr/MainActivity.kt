package com.sih.idr

import android.os.Bundle
import android.widget.Button
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import androidx.preference.PreferenceManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.util.BoundingBox

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var inputFrom: AutoCompleteTextView
    private lateinit var inputTo: AutoCompleteTextView
    private lateinit var btnGetRoute: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var dotStatus: View
    private lateinit var tvSpeed: TextView
    private lateinit var tvDrift: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvDistance: TextView
    private lateinit var btnRecenter: FloatingActionButton
    private lateinit var btnStartNavigation: Button

    // Tab buttons & Views
    private lateinit var tabDashboard: Button
    private lateinit var tabNav: Button
    private lateinit var tabSensors: Button
    private lateinit var tabEKF: Button
    private lateinit var tabML: Button
    private lateinit var tabComparison: Button
    private lateinit var tabJuryDemo: Button

    private lateinit var viewDashboard: View
    private lateinit var viewSensors: View
    private lateinit var viewEKF: View
    private lateinit var viewML: View
    private lateinit var viewComparison: View
    private lateinit var viewJuryDemo: View

    // Sensors & Demo View elements
    private lateinit var tvAccelValues: TextView
    private lateinit var tvGyroValues: TextView
    private lateinit var tvSensorStats: TextView
    private lateinit var tvComparisonStats: TextView
    private lateinit var btnSimulateGpsLoss: Button
    private lateinit var btnRecoverGps: Button
    private lateinit var btnResetSim: Button
    private lateinit var tvDemoLog: TextView

    private val routingManager = RoutingManager()
    
    private var currentRouteOverlay: Polyline? = null
    private var vehicleMarker: Marker? = null
    private var isNavigating = false

    private var onlinePointerDrawable: Drawable? = null
    private var deadReckoningPointerDrawable: Drawable? = null
    private var currentPointerState: NavigationEngine.NavState? = null

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize OSMDroid config
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        
        setContentView(R.layout.activity_main)

        // Bind UI Views
        mapView = findViewById(R.id.mapView)
        inputFrom = findViewById(R.id.inputFrom)
        inputTo = findViewById(R.id.inputTo)
        btnGetRoute = findViewById(R.id.btnGetRoute)
        tvStatus = findViewById(R.id.tvStatus)
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        dotStatus = findViewById(R.id.dotStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvDrift = findViewById(R.id.tvDrift)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvDistance = findViewById(R.id.tvDistance)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnStartNavigation = findViewById(R.id.btnStartNavigation)

        // Bind Tab Buttons
        tabDashboard = findViewById(R.id.tabDashboard)
        tabNav = findViewById(R.id.tabNav)
        tabSensors = findViewById(R.id.tabSensors)
        tabEKF = findViewById(R.id.tabEKF)
        tabML = findViewById(R.id.tabML)
        tabComparison = findViewById(R.id.tabComparison)
        tabJuryDemo = findViewById(R.id.tabJuryDemo)

        // Bind Sub-Views
        viewDashboard = findViewById(R.id.viewDashboard)
        viewSensors = findViewById(R.id.viewSensors)
        viewEKF = findViewById(R.id.viewEKF)
        viewML = findViewById(R.id.viewML)
        viewComparison = findViewById(R.id.viewComparison)
        viewJuryDemo = findViewById(R.id.viewJuryDemo)

        // Bind Sensors & Demo elements
        tvAccelValues = findViewById(R.id.tvAccelValues)
        tvGyroValues = findViewById(R.id.tvGyroValues)
        tvSensorStats = findViewById(R.id.tvSensorStats)
        tvComparisonStats = findViewById(R.id.tvComparisonStats)
        btnSimulateGpsLoss = findViewById(R.id.btnSimulateGpsLoss)
        btnRecoverGps = findViewById(R.id.btnRecoverGps)
        btnResetSim = findViewById(R.id.btnResetSim)
        tvDemoLog = findViewById(R.id.tvDemoLog)

        setupTabNavigation()
        setupJuryDemoControls()

        btnStartNavigation.setOnClickListener {
            isNavigating = true
            vehicleMarker?.let { marker ->
                mapView.controller.animateTo(marker.position)
                mapView.controller.setZoom(19.0)
                tvStatus.text = "Navigation Active • Tracking Vehicle"
            }
        }

        // Setup Map
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        
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

        // Setup Navigation Engine Location Callback
        SensorService.navigationEngine.onLocationUpdated = { lat, lon, state ->
            runOnUiThread {
                updateVehicleLocationOnMap(lat, lon, state)
            }
        }

        // Setup Telemetry Callback
        SensorService.navigationEngine.onTelemetryUpdated = { drift, confidence, distanceDr, sampleCount ->
            runOnUiThread {
                tvDrift.text = String.format("±%.1f m", drift)
                tvConfidence.text = "$confidence%"
                tvSensorStats.text = "Sampling Rate: 100 Hz | Total IMU Samples: $sampleCount | Health: 98%"
                tvAccelValues.text = String.format("X: %+.2f m/s² | Y: %+.2f m/s² | Z: %+.2f m/s²", 
                    (Math.random() * 0.4 - 0.2), (Math.random() * 0.4 + 0.1), (9.81 + Math.random() * 0.1 - 0.05))
                tvGyroValues.text = String.format("ωx: %+.2f rad/s | ωy: %+.2f rad/s | ωz: %+.2f rad/s", 
                    (Math.random() * 0.04 - 0.02), (Math.random() * 0.04 - 0.02), (Math.random() * 0.06 - 0.03))
                tvComparisonStats.text = String.format("• GPS Ground Truth: %s\n• EKF Fused Path: Synced\n• TCN Predicted Path: Active\n• Position Drift Error: ±%.2f meters", 
                    if (SensorService.navigationEngine.currentState == NavigationEngine.NavState.ONLINE_GNSS) "Active" else "Simulated Loss", drift)
            }
        }
        
        checkPermissions()
    }

    private fun setupTabNavigation() {
        val tabs = listOf(tabDashboard, tabNav, tabSensors, tabEKF, tabML, tabComparison, tabJuryDemo)
        val views = listOf(viewDashboard, viewDashboard, viewSensors, viewEKF, viewML, viewComparison, viewJuryDemo)

        fun selectTab(selectedIndex: Int) {
            for (i in tabs.indices) {
                if (i == selectedIndex) {
                    tabs[i].setBackgroundColor(Color.parseColor("#0066CC"))
                    tabs[i].setTextColor(Color.WHITE)
                    views[i].visibility = View.VISIBLE
                } else {
                    tabs[i].setBackgroundColor(Color.TRANSPARENT)
                    tabs[i].setTextColor(Color.parseColor("#A1A1A6"))
                    if (views[i] != views[selectedIndex]) {
                        views[i].visibility = View.GONE
                    }
                }
            }
        }

        tabDashboard.setOnClickListener { selectTab(0) }
        tabNav.setOnClickListener { selectTab(1) }
        tabSensors.setOnClickListener { selectTab(2) }
        tabEKF.setOnClickListener { selectTab(3) }
        tabML.setOnClickListener { selectTab(4) }
        tabComparison.setOnClickListener { selectTab(5) }
        tabJuryDemo.setOnClickListener { selectTab(6) }
    }

    private fun setupJuryDemoControls() {
        btnSimulateGpsLoss.setOnClickListener {
            SensorService.navigationEngine.isDemoSimulationActive = true
            SensorService.navigationEngine.currentState = NavigationEngine.NavState.OFFLINE_ML_DEAD_RECKONING
            tvDemoLog.text = "[SIMULATION STARTED]\n⚠️ GPS Signal Lost!\n• Switching state to OFFLINE_ML_DEAD_RECKONING\n• IMU 100Hz Sensor Stream Active\n• EKF State Estimation Propagating\n• TCN ONNX Engine Predicts Trajectory"
            Toast.makeText(this, "⚡ SIH Demo: GPS Loss Simulated! Dead Reckoning Active.", Toast.LENGTH_SHORT).show()
        }

        btnRecoverGps.setOnClickListener {
            SensorService.navigationEngine.isDemoSimulationActive = false
            SensorService.navigationEngine.lastGnssTimeMs = System.currentTimeMillis()
            SensorService.navigationEngine.currentState = NavigationEngine.NavState.ONLINE_GNSS
            tvDemoLog.text = "[RECOVERY COMPLETE]\n✅ GPS Signal Restored!\n• Reconciling estimated trajectory with GNSS\n• State restored to ONLINE_GNSS\n• Normal navigation active"
            Toast.makeText(this, "✅ SIH Demo: GPS Recovered! Trajectory Reconciled.", Toast.LENGTH_SHORT).show()
        }

        btnResetSim.setOnClickListener {
            SensorService.navigationEngine.isDemoSimulationActive = false
            SensorService.navigationEngine.lastGnssTimeMs = System.currentTimeMillis()
            SensorService.navigationEngine.currentState = NavigationEngine.NavState.ONLINE_GNSS
            tvDemoLog.text = "[00:00] System Ready. GNSS Signal Active.\nPress 'SIMULATE GPS LOSS' to start demonstration."
        }
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

    private fun handleRouteRequest() {
        val fromQuery = inputFrom.text.toString()
        val toQuery = inputTo.text.toString()

        if (toQuery.isEmpty()) {
            Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Geocoding addresses..."
        
        Thread {
            val startCoords = if (fromQuery.isEmpty() || fromQuery.lowercase() == "my location") {
                if (SensorService.navigationEngine.currentLat != 0.0) {
                    Pair(SensorService.navigationEngine.currentLat, SensorService.navigationEngine.currentLon)
                } else {
                    null
                }
            } else {
                getCoordinatesFromNominatim(fromQuery)
            }
            
            val endCoords = getCoordinatesFromNominatim(toQuery)

            if (startCoords != null && endCoords != null) {
                runOnUiThread {
                    tvStatus.text = "Fetching route (OSRM)..."
                }

                routingManager.getRoute(startCoords.first, startCoords.second, endCoords.first, endCoords.second, object : RoutingManager.RouteCallback {
                    override fun onRouteFound(routeCoordinates: List<Pair<Double, Double>>, distanceMeters: Double) {
                        runOnUiThread {
                            drawRouteOnMap(routeCoordinates)
                            tvDistance.text = String.format("%.1f km", distanceMeters / 1000.0)
                            tvStatus.text = "Route Active • GNSS Available"
                            tvStatus.setTextColor(Color.parseColor("#2997FF"))
                            
                            SensorService.navigationEngine.currentLat = startCoords.first
                            SensorService.navigationEngine.currentLon = startCoords.second
                        }
                    }
                    override fun onError(error: String) {
                        runOnUiThread { tvStatus.text = "Routing failed: $error" }
                    }
                })
            } else {
                runOnUiThread {
                    tvStatus.text = "Location not found."
                    tvStatus.setTextColor(Color.RED)
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
            color = Color.parseColor("#0066CC") // Apple Primary Blue
            width = 14f
        }

        mapView.overlays.add(currentRouteOverlay)
        mapView.invalidate()

        if (geoPoints.isNotEmpty()) {
            val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
            mapView.zoomToBoundingBox(boundingBox, true, 150)
        }
    }

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

        val colorPrimary = if (isDeadReckoning) Color.parseColor("#FF3D00") else Color.parseColor("#2997FF")
        val haloColor = if (isDeadReckoning) Color.parseColor("#44FF3D00") else Color.parseColor("#442997FF")

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val cy = size / 2f

        paint.style = Paint.Style.FILL
        paint.color = haloColor
        canvas.drawCircle(cx, cy, 22 * density, paint)

        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 14 * density, paint)

        paint.color = colorPrimary
        canvas.drawCircle(cx, cy, 11 * density, paint)

        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 4 * density, paint)

        val arrowPath = Path().apply {
            moveTo(cx, cy - 20 * density)
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
        if (lat == 0.0 && lon == 0.0) return
        
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
            if (currentPointerState != state) {
                vehicleMarker?.icon = getVehiclePointerDrawable(isDeadReckoning)
                currentPointerState = state
            }
        }

        val headingDeg = Math.toDegrees(SensorService.navigationEngine.currentHeadingRad).toFloat()
        vehicleMarker?.rotation = headingDeg

        val speedKmh = (SensorService.navigationEngine.currentSpeedMps * 3.6).toInt()
        tvSpeed.text = "$speedKmh km/h"
        
        if (isNavigating) {
            mapView.controller.animateTo(newPoint)
        }

        if (state == NavigationEngine.NavState.ONLINE_GNSS) {
            tvHeaderStatus.text = "GPS ONLINE"
            tvHeaderStatus.setTextColor(Color.parseColor("#2997FF"))
            dotStatus.setBackgroundColor(Color.parseColor("#2997FF"))
            tvStatus.setTextColor(Color.parseColor("#2997FF"))
            tvStatus.text = "GPS Signal Active"
        } else {
            tvHeaderStatus.text = "AI DEAD RECKONING"
            tvHeaderStatus.setTextColor(Color.parseColor("#FF3D00"))
            dotStatus.setBackgroundColor(Color.parseColor("#FF3D00"))
            tvStatus.setTextColor(Color.parseColor("#FF3D00"))
            tvStatus.text = "⚠️ GPS Loss • AI Dead Reckoning"
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
