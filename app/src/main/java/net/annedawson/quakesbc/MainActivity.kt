// MainActivity.kt
package net.annedawson.quakesbc

/*

Last updated: Thursday 16th April 2026, 14:22 PT
Date started: Friday 5th December 2025
Programmer: Anne Dawson
App: QuakesBC
Purpose: An earthquake monitor for BC Canada and neighbouring territory
File: MainActivity.kt
Commit #21 - AI updated library dependencies
        and set compileSdk and targetSdk to 37

 */

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.*

// Data Models
data class EarthquakeResponse(
    val features: List<Feature>
)

data class Feature(
    val properties: Properties,
    val geometry: Geometry,
    val id: String
)

data class Properties(
    val mag: Double?,
    val place: String?,
    val time: Long,
    val url: String?,
    val felt: Int?
)

data class Geometry(
    val coordinates: List<Double>
)

// API Interface
interface USGSApi {
    @GET("fdsnws/event/1/query")
    suspend fun getEarthquakes(
        @Query("format") format: String = "geojson",
        @Query("starttime") startTime: String,
        @Query("endtime") endTime: String,
        @Query("minlatitude") minLat: Double,
        @Query("maxlatitude") maxLat: Double,
        @Query("minlongitude") minLon: Double,
        @Query("maxlongitude") maxLon: Double,
        @Query("minmagnitude") minMag: Double
    ): EarthquakeResponse
}

// ViewModel

class EarthquakeViewModel : ViewModel() {
    var earthquakes by mutableStateOf<List<Feature>>(emptyList())
        private set

    var filteredQuakes by mutableStateOf<List<Feature>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var searchTerm by mutableStateOf("")
    var minMagnitude by mutableDoubleStateOf(0.0)
    var timeFilter by mutableStateOf("day")
    var sortBy by mutableStateOf("time")
    var sortOrder by mutableStateOf("desc")
    var selectedQuake by mutableStateOf<Feature?>(null)
    var selectedFromList by mutableStateOf(false)
    var showFilters by mutableStateOf(false)
    var showList by mutableStateOf(true)
    var lastUpdate by mutableStateOf<Date?>(null)

    var maxResults by mutableIntStateOf(500)
        private set

    private val westCanadaBounds = mapOf(
        "minLat" to 48.0,
        "maxLat" to 70.0,
        "minLon" to -141.0,
        "maxLon" to -101.0
    )

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://earthquake.usgs.gov/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(USGSApi::class.java)

    init {
        fetchEarthquakes()
        startAutoRefresh()
    }

    fun fetchEarthquakes() {
        viewModelScope.launch {
            loading = true
            error = null

            try {
                val timeMap = mapOf(
                    "hour" to 1.0/24,
                    "day" to 1.0,
                    "week" to 7.0,
                    "month" to 30.0,
                    "year" to 365.0
                )

                val days = timeMap[timeFilter] ?: 7.0
                val endTime = Date()
                val startTime = Date(endTime.time - (days * 24 * 60 * 60 * 1000).toLong())

                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")

                val response = api.getEarthquakes(
                    startTime = sdf.format(startTime),
                    endTime = sdf.format(endTime),
                    minLat = westCanadaBounds["minLat"]!!,
                    maxLat = westCanadaBounds["maxLat"]!!,
                    minLon = westCanadaBounds["minLon"]!!,
                    maxLon = westCanadaBounds["maxLon"]!!,
                    minMag = minMagnitude
                )

                earthquakes = response.features
                filterAndSortQuakes()
                lastUpdate = Date()
            } catch (e: Exception) {
                error = "Failed to fetch earthquakes: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(5 * 60 * 1000)
                fetchEarthquakes()
            }
        }
    }

    // start of filterAndSortQuakes

    fun filterAndSortQuakes() {
        var filtered = earthquakes.toList()

        val trimmedSearch = searchTerm.trim()
        if (trimmedSearch.isNotEmpty()) {
            filtered = filtered.filter { quake ->
                quake.properties.place?.contains(trimmedSearch, ignoreCase = true) == true
            }
        }

        filtered = when (sortBy) {
            "magnitude" -> {
                if (sortOrder == "asc")
                    filtered.sortedBy { it.properties.mag ?: 0.0 }
                else
                    filtered.sortedByDescending { it.properties.mag ?: 0.0 }
            }
            "depth" -> {
                if (sortOrder == "asc")
                    filtered.sortedBy { it.geometry.coordinates.getOrNull(2) ?: 0.0 }
                else
                    filtered.sortedByDescending { it.geometry.coordinates.getOrNull(2) ?: 0.0 }
            }
            else -> {
                if (sortOrder == "asc")
                    filtered.sortedBy { it.properties.time }
                else
                    filtered.sortedByDescending { it.properties.time }
            }
        }

        // Limit results to prevent performance issues
        filteredQuakes = filtered.take(maxResults)
    }
    // end of filterAndSortQuakes

    fun getAverageLocationForFiltered(): LatLng? {
        if (filteredQuakes.isEmpty()) return null

        var totalLat = 0.0
        var totalLon = 0.0
        var count = 0

        filteredQuakes.forEach { quake ->
            totalLat += quake.geometry.coordinates[1] // latitude
            totalLon += quake.geometry.coordinates[0] // longitude
            count++
        }

        return if (count > 0) {
            LatLng(totalLat / count, totalLon / count)
        } else {
            null
        }
    }

    fun onMinMagnitudeChange(mag: Double) {
        minMagnitude = mag
        fetchEarthquakes()
    }

    fun onTimeFilterChange(filter: String) {
        timeFilter = filter
        fetchEarthquakes()
    }

    fun onSortByChange(sort: String) {
        sortBy = sort
        filterAndSortQuakes()
    }

    fun onSortOrderChange(order: String) {
        sortOrder = order
        filterAndSortQuakes()
    }
}

// End of ViewModel

// Main Activity
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            createNotificationChannel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            createNotificationChannel()
        }

        setContent {
            QuakeWatchWestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuakesBCApp()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "earthquake_alerts",
            "Earthquake Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for significant earthquakes"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}

// Theme
@Composable
fun QuakeWatchWestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF3B82F6),
            secondary = Color(0xFF1E3A8A),
            background = Color(0xFF111827),
            surface = Color(0xFF1F2937),
            error = Color(0xFFEF4444)
        ),
        content = content
    )
}

// Main App Composable
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuakesBCApp(viewModel: EarthquakeViewModel = viewModel()) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /*LaunchedEffect(viewModel.searchTerm) {
        delay(150) // Wait 150ms after user stops typing
        viewModel.filterAndSortQuakes()
    }*/

    // ── NEW: track whether the info screen is open ──────────────────────────
    var showInfoScreen by remember { mutableStateOf(false) }

    if (showInfoScreen) {
        InfoScreen(onBack = { showInfoScreen = false })
        return   // don't render the rest of the app while info screen is open
    }
    // ────────────────────────────────────────────────────────────────────────

    // Add debounced search filtering
    LaunchedEffect(viewModel.searchTerm) {
        delay(150) // Wait 150ms after user stops typing
        viewModel.filterAndSortQuakes()
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E3A8A))
                    .statusBarsPadding()
                    //CHANGE .padding(16.dp)
                    .padding(horizontal = 16.dp, vertical = if (isLandscape) 4.dp else 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        /*Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(32.dp)
                        )*/
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "App information",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier
                                .size(32.dp)
                                .clickable { showInfoScreen = true }   // ← new
                        )
                        Spacer(modifier = Modifier.width(12.dp)) // unchanged for landscape


                        Column {
                            Text(
                                text = "QuakesBC",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Western Canada Earthquake Monitor",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD1D5DB)
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.fetchEarthquakes() },
                        enabled = !viewModel.loading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                }

                //CHANGE Spacer(modifier = Modifier.height(16.dp))
                Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 16.dp))


                // was an OutlinedTextField

                // Search dropdown

                // ... inside QuakesBCApp Scaffold topBar ...

// Search dropdown
                var expanded by remember { mutableStateOf(false) }
                val townCounts = remember(viewModel.earthquakes) {
                    viewModel.earthquakes
                        .mapNotNull { it.properties.place }
                        .map { place ->
                            val parts = place.split(" of ")
                            if (parts.size > 1) parts[1].trim() else place.trim()
                        }
                        .groupingBy { it }
                        .eachCount()
                }
                val uniqueTowns = remember(townCounts) {
                    townCounts.keys.sorted()
                }

                Column {
                    OutlinedButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1F2937),
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))

                                // --- UPDATED: Shows count on the selected location button ---
                                val displayText = if (viewModel.searchTerm.isEmpty()) {
                                    "Select location..."
                                } else {
                                    "${viewModel.searchTerm} (${townCounts[viewModel.searchTerm] ?: 0})"
                                }

                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (viewModel.searchTerm.isEmpty()) Color(0xFF9CA3AF) else Color.White
                                )
                                // -----------------------------------------------------------
                            }
                            if (viewModel.searchTerm.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        viewModel.searchTerm = ""
                                        viewModel.filterAndSortQuakes()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color(0xFFFBBF24),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(max = 400.dp)
                    ) {
                        if (uniqueTowns.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No locations available") },
                                onClick = { }
                            )
                        } else {
                            uniqueTowns.forEach { town ->
                                DropdownMenuItem(
                                    // --- ALREADY IMPLEMENTED (Ensuring it matches): ---
                                    text = { Text("$town (${townCounts[town] ?: 0})") },
                                    onClick = {
                                        viewModel.searchTerm = town
                                        viewModel.filterAndSortQuakes()
                                        viewModel.showList = false
                                        viewModel.showFilters = false
                                        viewModel.selectedQuake = null
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // end of Search dropdown

                //CHANGE Spacer(modifier = Modifier.height(12.dp))
                Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    /*TextButton(
                        onClick = { viewModel.showFilters = !viewModel.showFilters },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD1D5DB))
                    ) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Filters & Sorting")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (viewModel.showFilters) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }*/

                    TextButton(
                        onClick = { viewModel.showFilters = !viewModel.showFilters },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD1D5DB))
                    ) {
                        Spacer(modifier = Modifier.width(8.dp))
                        //Text("Filters & Sorting")
                        Text(if (viewModel.showFilters) "Hide Filters" else "Display Filters")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (viewModel.showFilters) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    TextButton(
                        onClick = { viewModel.showList = !viewModel.showList },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD1D5DB))
                    ) {
                        Text(if (viewModel.showList) "Hide List" else "Display List")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (viewModel.showList) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (viewModel.showFilters) {
                    //CHANGE Spacer(modifier = Modifier.height(12.dp))
                    Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Time Period",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9CA3AF)
                            )
                            // CHANGE Spacer(modifier = Modifier.height(4.dp))
                            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

                            FilterDropdown(
                                value = viewModel.timeFilter,
                                options = listOf(
                                    "hour" to "Last Hour",
                                    "day" to "Last Day",
                                    "week" to "Last Week",
                                    "month" to "Last Month",
                                    "year" to "Last Year"
                                ),
                                onValueChange = { viewModel.onTimeFilterChange(it) }
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Min Magnitude",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9CA3AF)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FilterDropdown(
                                value = viewModel.minMagnitude.toString(),
                                options = listOf(
                                    "0.0" to "All (0.0+)",
                                    "1.0" to "1.0+",
                                    "2.0" to "2.0+",
                                    "3.0" to "3.0+",
                                    "4.0" to "4.0+",
                                    "5.0" to "5.0+"
                                ),
                                onValueChange = { viewModel.onMinMagnitudeChange(it.toDouble()) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sort By",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9CA3AF)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FilterDropdown(
                                value = viewModel.sortBy,
                                options = listOf(
                                    "time" to "Time",
                                    "magnitude" to "Magnitude",
                                    "depth" to "Depth"
                                ),
                                onValueChange = { viewModel.onSortByChange(it) }
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Order",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9CA3AF)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FilterDropdown(
                                value = viewModel.sortOrder,
                                options = listOf("desc" to "Descending", "asc" to "Ascending"),
                                onValueChange = { viewModel.onSortOrderChange(it) }
                            )
                        }
                    }
                }


                /*  viewModel.lastUpdate?.let { lastUpdate ->
                      Spacer(modifier = Modifier.height(8.dp))
                      Text(
                          text = "Last updated: ${
                              SimpleDateFormat(
                                  "HH:mm:ss",
                                  Locale.getDefault()
                              ).format(lastUpdate)
                          }",
                          style = MaterialTheme.typography.labelSmall,
                          color = Color(0xFF9CA3AF)
                      )
                  }*/

                //CHANGE

                // Last updated — hide in landscape to save space:
                if (!isLandscape) {
                    Spacer(modifier = Modifier.height(8.dp))
                    viewModel.lastUpdate?.let { lastUpdate ->
                        Text(
                            text = "Last updated: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(lastUpdate)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }


            }
        }
        // delete below
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Full-screen map
            MapView(
                earthquakes = viewModel.filteredQuakes,
                selectedQuake = viewModel.selectedQuake,
                onQuakeSelected = { quake ->
                    viewModel.selectedFromList = false
                    viewModel.selectedQuake = if (viewModel.selectedQuake == quake) null else quake
                },
                selectedFromList = viewModel.selectedFromList,
                modifier = Modifier.fillMaxSize(),
                centerLocation = if (viewModel.searchTerm.isNotEmpty())
                    viewModel.getAverageLocationForFiltered()
                else
                    null
            )

            // Floating earthquake list card (bottom right)
            if (viewModel.showList) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(if (isLandscape) 8.dp else 16.dp)
                        .width(if (isLandscape) 300.dp else 350.dp)
                        .heightIn(max = if (isLandscape) 180.dp else 450.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1F2937).copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    EarthquakeList(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // end of card


            }
        }
    }

}


// start of Composable MapView

@Composable
fun MapView(
    earthquakes: List<Feature>,
    selectedQuake: Feature?,
    onQuakeSelected: (Feature) -> Unit,
    modifier: Modifier = Modifier,
    selectedFromList: Boolean = false,
    centerLocation: LatLng? = null
) {
    // Western Canada center coordinates (BC focus)
    val westCanadaCenter = LatLng(54.0, -125.0)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(westCanadaCenter, 5.5f)
    }

    // Move camera when centerLocation changes
    LaunchedEffect(centerLocation) {
        centerLocation?.let { location ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(location, 9f),
                durationMs = 1000
            )
        }
    }

    // Optimize: Only show top N earthquakes on map, prioritize by magnitude
    val mapEarthquakes = remember(earthquakes) {
        earthquakes
            .sortedByDescending { it.properties.mag ?: 0.0 }
            .take(200) // Only show top 200 earthquakes on map
    }

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.TERRAIN,
                isMyLocationEnabled = false
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                zoomGesturesEnabled = true,
                scrollGesturesEnabled = true,
                tiltGesturesEnabled = false,
                rotationGesturesEnabled = false,
                compassEnabled = true
            )
        ) {
            // Draw earthquake markers as circles (optimized - fewer earthquakes)
            mapEarthquakes.forEach { quake ->
                val position = LatLng(
                    quake.geometry.coordinates[1], // latitude
                    quake.geometry.coordinates[0]  // longitude
                )
                val mag = quake.properties.mag ?: 0.0
                val isSelected = selectedQuake == quake

                // Outer circle (larger, semi-transparent)
                Circle(
                    center = position,
                    radius = getMagnitudeRadius(mag),
                    fillColor = getMagnitudeColor(mag).copy(alpha = 0.4f),
                    strokeColor = getMagnitudeColor(mag),
                    strokeWidth = if (isSelected) 4f else 2f,
                    clickable = true,
                    onClick = {
                        onQuakeSelected(quake)
                    }
                )

                // Center dot (smaller, solid)
                Circle(
                    center = position,
                    radius = 2000.0,
                    fillColor = getMagnitudeColor(mag),
                    strokeColor = Color.White,
                    strokeWidth = 1f
                )
            }
        }

        // Show warning if not all earthquakes are displayed on map
        if (earthquakes.size > 200) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFCA8A04).copy(alpha = 0.9f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Showing top 200 of ${earthquakes.size} quakes on map",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        // Magnitude legend (bottom left)
        Card(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF111827).copy(alpha = 0.9f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Magnitude Scale",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                MagnitudeLegendItem(color = Color(0xFFDC2626), label = "6.0+ Major")
                MagnitudeLegendItem(color = Color(0xFFEA580C), label = "5.0-5.9 Moderate")
                MagnitudeLegendItem(color = Color(0xFFCA8A04), label = "4.0-4.9 Light")
                MagnitudeLegendItem(color = Color(0xFF2563EB), label = "3.0-3.9 Minor")
                MagnitudeLegendItem(color = Color(0xFF16A34A), label = "<3.0 Micro")
            }
        }

        // Selected earthquake info card (top right) — only shown for map selections
        if (!selectedFromList) selectedQuake?.let { quake ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF111827).copy(alpha = 0.95f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { onQuakeSelected(quake) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF9CA3AF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Magnitude
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(getMagnitudeColor(quake.properties.mag ?: 0.0))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "M${String.format(Locale.US, "%.1f", quake.properties.mag ?: 0.0)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = quake.properties.place ?: "Unknown location",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD1D5DB)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF374151))
                    Spacer(modifier = Modifier.height(12.dp))

                    DetailRow("Time", formatTime(quake.properties.time))
                    DetailRow("Depth", "${String.format(Locale.US, "%.1f", quake.geometry.coordinates.getOrNull(2) ?: 0.0)} km")
                    DetailRow("Coordinates", "${String.format(Locale.US, "%.4f", quake.geometry.coordinates[1])}°, ${String.format(Locale.US, "%.4f", quake.geometry.coordinates[0])}°")

                    quake.properties.felt?.let { felt ->
                        DetailRow("Felt Reports", "$felt people")
                    }
                }
            }
        }
    }
}

// end of Composable MapView


// Helper function to calculate circle radius based on magnitude
fun getMagnitudeRadius(mag: Double): Double {
    return when {
        mag >= 6.0 -> 50000.0  // 50 km radius
        mag >= 5.0 -> 35000.0  // 35 km radius
        mag >= 4.0 -> 25000.0  // 25 km radius
        mag >= 3.0 -> 15000.0  // 15 km radius
        mag >= 2.0 -> 10000.0  // 10 km radius
        else -> 5000.0         // 5 km radius
    }
}

@Composable
fun FilterDropdown(
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == value }?.second ?: value

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFF1F2937),
                contentColor = Color.White
            )
        ) {
            Text(
                text = selectedLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onValueChange(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun MagnitudeLegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFD1D5DB)
        )
    }
}

@Composable
fun EarthquakeList(viewModel: EarthquakeViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF1F2937))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Earthquakes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${viewModel.filteredQuakes.size} event${if (viewModel.filteredQuakes.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9CA3AF)
            )
        }

        // add code here

        // Add this warning if results are limited
        if (viewModel.earthquakes.size > viewModel.maxResults) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFCA8A04).copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "From top ${viewModel.maxResults} of ${viewModel.earthquakes.size} total quakes. Use filters to narrow results.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD1D5DB)
                    )
                }
            }
        }

        // end of added code



        when {
            viewModel.loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            viewModel.error != null -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF7F1D1D).copy(
                            alpha = 0.5f
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = viewModel.error ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.fetchEarthquakes() }) {
                            Text("Check your internet connection and try again")
                        }
                    }
                }
            }

            viewModel.filteredQuakes.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color(0xFF4B5563)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No earthquakes found",
                            color = Color(0xFF9CA3AF)
                        )
                        Text(
                            text = "Try adjusting your filters",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(viewModel.filteredQuakes) { quake ->
                        EarthquakeCard(
                            quake = quake,
                            isSelected = viewModel.selectedQuake == quake,
                            onClick = {
                                viewModel.selectedFromList = true
                                viewModel.selectedQuake =
                                    if (viewModel.selectedQuake == quake) null else quake
                            }
                        )
                    }
                }
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About QuakesBC") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E3A8A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF111827)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // App description card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "QuakesBC",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Western Canada Earthquake Monitor tracks seismic activity " +
                                    "across British Columbia, Alberta, and neighbouring regions " +
                                    "using live data from the USGS Earthquake Hazards Program.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFD1D5DB)
                        )
                    }
                }
            }

            item {
                // Data source card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Data Source",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Earthquake data is provided by the United States Geological " +
                                    "Survey (USGS) via the FDSN Web Services API. Data refreshes " +
                                    "automatically every 5 minutes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFD1D5DB)
                        )
                    }
                }
            }

            item {
                // Magnitude scale card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Magnitude Scale",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        listOf(
                            Triple(Color(0xFFDC2626), "6.0+",    "Major"),
                            Triple(Color(0xFFEA580C), "5.0–5.9", "Moderate"),
                            Triple(Color(0xFFCA8A04), "4.0–4.9", "Light"),
                            Triple(Color(0xFF2563EB), "3.0–3.9", "Minor"),
                            Triple(Color(0xFF16A34A), "< 3.0",   "Micro")
                        ).forEach { (color, range, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "$range  —  $label",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFD1D5DB)
                                )
                            }
                        }
                    }
                }
            }

            item {
                // Coverage area card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Coverage Area",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Latitude 48°N – 70°N  ·  Longitude 141°W – 101°W\n" +
                                    "Covers BC, Alberta, Yukon, and adjacent Pacific and Arctic regions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFD1D5DB)
                        )
                    }
                }
            }
        }
    }
}



@Composable
fun EarthquakeCard(quake: Feature, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF1E3A8A) else Color(0xFF111827)
        ),
        border = if (isSelected) BorderStroke(2.dp, Color(0xFF3B82F6)) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(getMagnitudeColor(quake.properties.mag ?: 0.0))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "M${String.format(Locale.US, "%.1f", quake.properties.mag ?: 0.0)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = formatTime(quake.properties.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = quake.properties.place ?: "Unknown location",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD1D5DB)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Depth: ${
                        String.format(
                            Locale.US,
                            "%.1f",
                            quake.geometry.coordinates.getOrNull(2) ?: 0.0
                        )
                    } km",
                    style = MaterialTheme.typography.labelSmall,
                    //color = Color(0xFF6B7280)
                    color = Color(0xFFD1D5DB)
                )
                quake.properties.felt?.let { felt ->
                    Text(
                        text = "$felt reports",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFBBF24)
                    )
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF374151))
                Spacer(modifier = Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow(
                        "Coordinates",
                        "${
                            String.format(
                                Locale.US,
                                "%.4f",
                                quake.geometry.coordinates[1]
                            )
                        }, ${String.format(Locale.US, "%.4f", quake.geometry.coordinates[0])}"
                    )
                    DetailRow(
                        "Date",
                        SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(
                            Date(quake.properties.time)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9CA3AF)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White
        )
    }
}

fun getMagnitudeColor(mag: Double): Color {
    return when {
        mag >= 6.0 -> Color(0xFFDC2626)
        mag >= 5.0 -> Color(0xFFEA580C)
        mag >= 4.0 -> Color(0xFFCA8A04)
        mag >= 3.0 -> Color(0xFF2563EB)
        else -> Color(0xFF16A34A)
    }
}

fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = diff / 60000
    val hours = minutes / 60
    val days = hours / 24

    return when {
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
    }
}

fun showNotification(context: Context, quake: Feature) {
    if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    ) {
        return
    }

    val notification = NotificationCompat.Builder(context, "earthquake_alerts")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("Significant Earthquake Detected!")
        .setContentText(
            "M${
                String.format(
                    Locale.US,
                    "%.1f",
                    quake.properties.mag ?: 0.0
                )
            } - ${quake.properties.place}"
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context).notify(quake.id.hashCode(), notification)
}
