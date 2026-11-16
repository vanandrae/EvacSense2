package com.evacsense.auth

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonParseException
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class NavigationActivity : AppCompatActivity() {

    private lateinit var statusBanner: TextView
    private lateinit var offlineWarningBanner: TextView
    private lateinit var routeTitleText: TextView
    private lateinit var routeMetricsText: TextView
    private lateinit var directionsContainer: LinearLayout
    private lateinit var distressButton: Button
    private lateinit var backButton: Button

    private lateinit var authService: AuthService
    private val handler = Handler(Looper.getMainLooper())
    private var dynamicPollRunnable: Runnable? = null
    
    // Default fallback room if no baseline localized room exists
    private var detectedOriginRoomId = "ROOM-101" 
    private var detectedOriginRoomName = "CS Lab 1 (Room 401)"
    private lateinit var currentStudentId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // Bind Views
        statusBanner = findViewById(R.id.statusBanner)
        offlineWarningBanner = findViewById(R.id.offlineWarningBanner)
        routeTitleText = findViewById(R.id.routeTitleText)
        routeMetricsText = findViewById(R.id.routeMetricsText)
        directionsContainer = findViewById(R.id.directionsContainer)
        distressButton = findViewById(R.id.distressButton)
        backButton = findViewById(R.id.backButton)
        statusBanner.text = "Finding a safe route..."

        // Setup dynamic API Service
        authService = ApiClient.getService(this)

        backButton.setOnClickListener { finish() }
        distressButton.setOnClickListener { handleDistressAlert() }

        // Read detected origin room extra if passed from dashboard auto-localization trigger
        detectedOriginRoomId = intent.getStringExtra("DETECTED_ROOM_ID") ?: "ROOM-101"
        detectedOriginRoomName = intent.getStringExtra("DETECTED_ROOM_NAME") ?: "CS Lab 1 (Room 401)"
        
        val sharedPref = getSharedPreferences("evacsense_prefs", Context.MODE_PRIVATE)
        currentStudentId = sharedPref.getString("user_id", "USR-001") ?: "USR-001"

        // Start route loading
        loadRouteDirections()

        // Set up repeating dynamic blockage/routing polling every 4 seconds
        setupDynamicRoutingPolling()
    }

    private fun loadRouteDirections() {
        val sharedPref = getSharedPreferences("evacsense_prefs", Context.MODE_PRIVATE)
        val token = sharedPref.getString("auth_token", null)

        if (token == null) {
            Toast.makeText(this, "Session expired. Re-authentication required.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Verify internet connectivity
        if (!isNetworkConnected()) {
            activateOfflineMode()
            return
        }

        // Attempt to flush offline distress queue
        flushDistressQueue(token)

        // Fetch routing data
        authService.getEvacuationRoute("Bearer $token", detectedOriginRoomId).enqueue(object : Callback<RouteResponse> {
            override fun onResponse(call: Call<RouteResponse>, response: Response<RouteResponse>) {
                val body = response.body()
                if (response.isSuccessful && body?.status == "success" && body.route != null) {
                    renderEvacuationRoute(body.route)
                    
                    // Cache the route payload for offline fallback synchronization
                    cacheRoutePayload(body.route)
                } else if (response.code() == 404) {
                    handleNoSafeRoute()
                } else {
                    showRouteUnavailable("The server could not provide directions (HTTP ${response.code()}).")
                }
            }

            override fun onFailure(call: Call<RouteResponse>, t: Throwable) {
                activateOfflineMode()
            }
        })
    }

    private fun renderEvacuationRoute(route: EvacuationRouteDetails, fromCache: Boolean = false) {
        statusBanner.text = if (fromCache) {
            "CACHED ROUTE — VERIFY BEFORE FOLLOWING"
        } else {
            "ROUTE READY — FOLLOW THE STEPS"
        }
        statusBanner.setBackgroundColor(
            Color.parseColor(if (fromCache) "#B45309" else "#166534")
        )
        if (!fromCache) {
            offlineWarningBanner.visibility = View.GONE
        }

        routeTitleText.text = "Origin: $detectedOriginRoomName → Exit: ${route.destination}"
        val floors = route.path.map { it.floor }.distinct()
        val floorSummary = when (floors.size) {
            0 -> "Unknown"
            1 -> floors.first().toString()
            else -> "${floors.first()} → ${floors.last()}"
        }
        routeMetricsText.text = String.format(
            Locale.getDefault(),
            "Distance: %.1f m | Floors: %s",
            route.totalDistance,
            floorSummary
        )

        directionsContainer.removeAllViews()

        if (route.instructions.isEmpty()) {
            showGuidanceMessage(
                "No step-by-step directions were provided. Follow marked exits and safety marshal instructions."
            )
            return
        }

        for (inst in route.instructions) {
            val stepText = TextView(this)
            stepText.text = "${inst.step}. ${inst.text}"
            stepText.contentDescription = "Step ${inst.step}: ${inst.text}"
            stepText.setTextColor(Color.WHITE)
            stepText.textSize = 16f
            stepText.setLineSpacing(dp(2).toFloat(), 1.1f)
            stepText.setPadding(dp(16), dp(14), dp(16), dp(14))
            stepText.background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = dp(12).toFloat()
            }
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams.bottomMargin = dp(10)
            directionsContainer.addView(stepText, layoutParams)
        }
    }

    private fun showGuidanceMessage(message: String) {
        val guidanceText = TextView(this)
        guidanceText.text = message
        guidanceText.contentDescription = message
        guidanceText.setTextColor(Color.WHITE)
        guidanceText.textSize = 16f
        guidanceText.setLineSpacing(dp(3).toFloat(), 1.1f)
        guidanceText.setPadding(dp(12), dp(12), dp(12), dp(12))
        directionsContainer.addView(guidanceText)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun handleDistressAlert() {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val timestamp = sdf.format(Date())
        val locationSim = detectedOriginRoomName

        if (!isNetworkConnected()) {
            cacheOfflineDistress(currentStudentId, locationSim, timestamp)
            Toast.makeText(this, "DISTRESS SIGNAL QUEUED LOCALLY! Retransmitting aggressively...", Toast.LENGTH_LONG).show()
            return
        }

        distressButton.isEnabled = false
        val request = DistressRequest(currentStudentId, locationSim, timestamp)
        val token = getSharedPreferences("evacsense_prefs", Context.MODE_PRIVATE).getString("auth_token", "") ?: ""

        authService.submitDistressAlert("Bearer $token", request).enqueue(object : Callback<DistressResponse> {
            override fun onResponse(call: Call<DistressResponse>, response: Response<DistressResponse>) {
                distressButton.isEnabled = true
                val body = response.body()
                if (response.isSuccessful && body?.status == "success") {
                    Toast.makeText(this@NavigationActivity, "Distress beacon broadcasted successfully to safety marshals!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@NavigationActivity, body?.message ?: "Failed to trigger.", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<DistressResponse>, t: Throwable) {
                distressButton.isEnabled = true
                cacheOfflineDistress(currentStudentId, locationSim, timestamp)
                Toast.makeText(this@NavigationActivity, "Signal queued locally.", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun cacheOfflineDistress(studentId: String, location: String, timestamp: String) {
        val sharedPref = getSharedPreferences("evacsense_prefs", Context.MODE_PRIVATE)
        val gson = Gson()

        val queueJson = sharedPref.getString("offline_distress_queue", "[]")
        val itemType = object : TypeToken<MutableList<CachedDistress>>() {}.type
        val queue: MutableList<CachedDistress> = gson.fromJson(queueJson, itemType)

        queue.add(CachedDistress(studentId, location, timestamp))
        sharedPref.edit().putString("offline_distress_queue", gson.toJson(queue)).apply()
    }

    private fun flushDistressQueue(token: String) {
        val sharedPref = getSharedPreferences("evacsense_prefs", Context.MODE_PRIVATE)
        val gson = Gson()

        val distressJson = sharedPref.getString("offline_distress_queue", "[]")
        val distressType = object : TypeToken<MutableList<CachedDistress>>() {}.type
        val distressQueue: MutableList<CachedDistress> = gson.fromJson(distressJson, distressType)

        if (distressQueue.isNotEmpty()) {
            val iterator = distressQueue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val request = DistressRequest(item.studentId, item.location, item.timestamp)
                authService.submitDistressAlert("Bearer $token", request).enqueue(object : Callback<DistressResponse> {
                    override fun onResponse(call: Call<DistressResponse>, response: Response<DistressResponse>) {
                        if (response.isSuccessful) {
                            iterator.remove()
                            sharedPref.edit().putString("offline_distress_queue", gson.toJson(distressQueue)).apply()
                        }
                    }
                    override fun onFailure(call: Call<DistressResponse>, t: Throwable) {}
                })
            }
        }
    }

    private fun handleNoSafeRoute() {
        statusBanner.text = "NO CLEAR ROUTE AVAILABLE"
        statusBanner.setBackgroundColor(Color.parseColor("#B91C1C"))
        offlineWarningBanner.visibility = View.GONE
        routeTitleText.text = "No clear route from $detectedOriginRoomName"
        routeMetricsText.text = "Follow manual directions from a safety marshal."
        directionsContainer.removeAllViews()
        showGuidanceMessage(
            "The server found no clear route from your location. Follow posted emergency signs and safety marshal instructions. Use the distress alert if you need help."
        )
    }

    private fun showRouteUnavailable(message: String) {
        statusBanner.text = "DIRECTIONS UNAVAILABLE"
        statusBanner.setBackgroundColor(Color.parseColor("#B45309"))
        offlineWarningBanner.visibility = View.GONE
        routeTitleText.text = "No verified route available"
        routeMetricsText.text = "Contact a safety marshal for directions."
        directionsContainer.removeAllViews()
        showGuidanceMessage(
            "$message Follow posted emergency signs and safety marshal instructions. Do not rely on an old route."
        )
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            else -> false
        }
    }

    private fun activateOfflineMode() {
        offlineWarningBanner.visibility = View.VISIBLE
        
        // Retrieve offline cached route directions from SharedPreferences
        val sharedPref = getSharedPreferences("evacsense_prefs", Context.MODE_PRIVATE)
        val cachedJson = sharedPref.getString("cached_evac_route", null)

        if (cachedJson != null) {
            try {
                val route: EvacuationRouteDetails? =
                    Gson().fromJson(cachedJson, EvacuationRouteDetails::class.java)
                if (route == null) {
                    showCachedRouteUnavailable()
                    return
                }
                val savedAt = sharedPref.getLong("cached_evac_route_at", 0L)
                val savedTime = if (savedAt > 0L) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(savedAt))
                } else {
                    "unknown time"
                }
                offlineWarningBanner.text =
                    "Offline. Showing a route saved at $savedTime; it may be outdated. Verify with a safety marshal."
                renderEvacuationRoute(route, fromCache = true)
            } catch (e: JsonParseException) {
                Log.e("NavigationActivity", "Unable to read the cached evacuation route", e)
                showCachedRouteUnavailable()
            }
        } else {
            statusBanner.text = "OFFLINE — NO VERIFIED ROUTE"
            statusBanner.setBackgroundColor(Color.parseColor("#B45309"))
            offlineWarningBanner.text =
                "No connection to the server and no saved route is available."
            renderDefaultOfflineInstructions()
        }
    }

    private fun showCachedRouteUnavailable() {
        statusBanner.text = "CACHED ROUTE UNAVAILABLE"
        statusBanner.setBackgroundColor(Color.parseColor("#B45309"))
        offlineWarningBanner.text =
            "Saved directions could not be read. Follow posted emergency signs and safety marshal instructions."
        renderDefaultOfflineInstructions()
    }

    private fun renderDefaultOfflineInstructions() {
        routeTitleText.text = "Emergency guidance (offline)"
        routeMetricsText.text = "No verified route is available."

        directionsContainer.removeAllViews()
        showGuidanceMessage(
            "Follow official evacuation signs, avoid elevators, and ask a safety marshal for help. Do not rely on an unverified route."
        )
    }

    private fun cacheRoutePayload(route: EvacuationRouteDetails) {
        val sharedPref = getSharedPreferences("evacsense_prefs", Context.MODE_PRIVATE)
        sharedPref.edit()
            .putString("cached_evac_route", Gson().toJson(route))
            .putLong("cached_evac_route_at", System.currentTimeMillis())
            .apply()
    }

    private fun setupDynamicRoutingPolling() {
        dynamicPollRunnable = object : Runnable {
            override fun run() {
                loadRouteDirections()
                // Repeat every 4 seconds
                handler.postDelayed(this, 4000)
            }
        }
        handler.post(dynamicPollRunnable!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        dynamicPollRunnable?.let { handler.removeCallbacks(it) }
    }
}
