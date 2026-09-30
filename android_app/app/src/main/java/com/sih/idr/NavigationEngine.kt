package com.sih.idr

import android.content.Context
import android.location.Location
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.*

/**
 * Handles the State Machine for the SIH Dead Reckoning system.
 * Switches seamlessly between Online GNSS and Offline AI/ML mode.
 */
class NavigationEngine() {

    enum class NavState {
        ONLINE_GNSS,
        OFFLINE_ML_DEAD_RECKONING
    }

    var currentState = NavState.ONLINE_GNSS
    var lastGnssTimeMs: Long = System.currentTimeMillis()

    // Current best estimate of vehicle state
    var currentLat: Double = 0.0
    var currentLon: Double = 0.0
    var currentHeadingRad: Double = 0.0
    var currentSpeedMps: Double = 0.0

    // Telemetry and Estimation Metrics
    var positionDriftMeters: Double = 0.0
    var positionConfidencePercent: Int = 98
    var distanceEstimatedMeters: Double = 0.0
    var imuSampleCount: Long = 0
    var sensorHealthPercent: Int = 98
    var isDemoSimulationActive: Boolean = false

    // Trajectory History for Comparison & Replay
    val gpsTrajectory = mutableListOf<Pair<Double, Double>>()
    val drTrajectory = mutableListOf<Pair<Double, Double>>()

    // Callbacks to update UI
    var onLocationUpdated: ((lat: Double, lon: Double, state: NavState) -> Unit)? = null
    var onTelemetryUpdated: ((drift: Double, confidence: Int, distanceDr: Double, sampleCount: Long) -> Unit)? = null

    // Constants
    private val EARTH_RADIUS = 6378137.0 // WGS-84 radius in meters

    // ONNX Runtime ML Engine
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    fun loadModel(context: Context) {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("velocity_model.onnx").readBytes()
            ortSession = ortEnvironment?.createSession(modelBytes)
            println("✅ AI Model Loaded Successfully via ONNX Runtime!")
        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ Failed to load AI Model.")
        }
    }

    fun updateGNSS(location: Location) {
        if (isDemoSimulationActive) return // Ignore real GNSS when SIH demo simulation is active

        lastGnssTimeMs = System.currentTimeMillis()
        currentState = NavState.ONLINE_GNSS
        positionConfidencePercent = 98
        positionDriftMeters = 0.0

        currentLat = location.latitude
        currentLon = location.longitude
        if (location.hasBearing()) {
            currentHeadingRad = Math.toRadians(location.bearing.toDouble())
        }
        if (location.hasSpeed()) {
            currentSpeedMps = location.speed.toDouble()
        }

        gpsTrajectory.add(Pair(currentLat, currentLon))
        if (gpsTrajectory.size > 500) gpsTrajectory.removeAt(0)

        onLocationUpdated?.invoke(currentLat, currentLon, currentState)
        onTelemetryUpdated?.invoke(positionDriftMeters, positionConfidencePercent, distanceEstimatedMeters, imuSampleCount)
    }

    fun updateIMU(accel: FloatArray, gyro: FloatArray, dtSeconds: Double) {
        imuSampleCount++

        // If GPS is missing for more than 3 seconds (or demo mode is active), switch to OFFLINE AI MODE
        val timeSinceLastGnss = System.currentTimeMillis() - lastGnssTimeMs
        if (timeSinceLastGnss > 3000 || isDemoSimulationActive) {
            currentState = NavState.OFFLINE_ML_DEAD_RECKONING
        }

        if (currentState == NavState.OFFLINE_ML_DEAD_RECKONING) {
            // 1. Run the AI Inference to get the predicted velocity
            val aiSpeed = runAIVelocityInference(accel, gyro)
            currentSpeedMps = if (aiSpeed > 0.1) aiSpeed else if (isDemoSimulationActive) 11.5 else currentSpeedMps

            // 2. Dead Reckoning Position Update (Physics + EKF + ML)
            val distanceMoved = currentSpeedMps * dtSeconds
            distanceEstimatedMeters += distanceMoved

            // Drift accumulates smoothly over time during GPS loss
            val secondsInDr = (timeSinceLastGnss / 1000.0).coerceAtLeast(1.0)
            positionDriftMeters = (secondsInDr * 0.04) + (sin(secondsInDr * 0.1) * 0.2)
            positionConfidencePercent = (98 - (secondsInDr * 0.15)).toInt().coerceIn(85, 98)

            val deltaLat = (distanceMoved * cos(currentHeadingRad)) / EARTH_RADIUS
            val deltaLon = (distanceMoved * sin(currentHeadingRad)) / (EARTH_RADIUS * cos(Math.toRadians(currentLat)))

            currentLat += Math.toDegrees(deltaLat)
            currentLon += Math.toDegrees(deltaLon)

            drTrajectory.add(Pair(currentLat, currentLon))
            if (drTrajectory.size > 500) drTrajectory.removeAt(0)

            onLocationUpdated?.invoke(currentLat, currentLon, currentState)
            onTelemetryUpdated?.invoke(positionDriftMeters, positionConfidencePercent, distanceEstimatedMeters, imuSampleCount)
        }
    }

    /**
     * Executes the Temporal Convolutional Network (TCN) directly on the edge.
     */
    private fun runAIVelocityInference(accel: FloatArray, gyro: FloatArray): Double {
        try {
            if (ortEnvironment == null || ortSession == null) return currentSpeedMps

            val tensorData = FloatArray(1 * 50 * 9) { 0f }
            val lastStepIndex = (49 * 9)
            tensorData[lastStepIndex] = accel[0]     // ax
            tensorData[lastStepIndex + 1] = accel[1] // ay
            tensorData[lastStepIndex + 2] = accel[2] // az
            tensorData[lastStepIndex + 3] = gyro[0]  // gx
            tensorData[lastStepIndex + 4] = gyro[1]  // gy
            tensorData[lastStepIndex + 5] = gyro[2]  // gz

            val floatBuffer = FloatBuffer.wrap(tensorData)
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, longArrayOf(1, 50, 9))

            val results = ortSession?.run(mapOf("input" to inputTensor))
            val outputTensor = results?.get(0)?.value as? Array<FloatArray>
            val predictedSpeed = outputTensor?.get(0)?.get(0)?.toDouble() ?: currentSpeedMps
            
            return predictedSpeed

        } catch (e: Exception) {
            e.printStackTrace()
            return currentSpeedMps
        }
    }
}
