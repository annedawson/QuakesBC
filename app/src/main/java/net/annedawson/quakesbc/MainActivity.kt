// MainActivity.kt
package net.annedawson.quakesbc

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
import androidx.compose.material.icons.filled.*
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
    var minMagnitude by mutableStateOf(0.0)
    var timeFilter by mutableStateOf("week")
    var sortBy by mutableStateOf("time")
    var sortOrder by mutableStateOf("desc")
    var selectedQuake by mutableStateOf<Feature?>(null)
    var showFilters by mutableStateOf(false)
    var lastUpdate by mutableStateOf<Date?>(null)

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

    fun filterAndSortQuakes() {
        var filtered = earthquakes.toList()

        if (searchTerm.isNotEmpty()) {
            filtered = filtered.filter { quake ->
                quake.properties.place?.contains(searchTerm.trim(), ignoreCase = true) == true
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

        filteredQuakes = filtered
    }

    fun onSearchChange(term: String) {
        searchTerm = term
        filterAndSortQuakes()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "earthquake_alerts",
                "Earthquake Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for significant earthquakes"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
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
    val context = LocalContext.current

    LaunchedEffect(viewModel.filteredQuakes) {
        viewModel.filteredQuakes.forEach { quake ->
            if ((quake.properties.mag ?: 0.0) >= 5.5) {
                showNotification(context, quake)
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E3A8A))
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
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

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = viewModel.searchTerm,
                    onValueChange = { viewModel.onSearchChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by town...") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1F2937),
                        unfocusedContainerColor = Color(0xFF1F2937),
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF4B5563)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = { viewModel.showFilters = !viewModel.showFilters },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD1D5DB))
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Filters & Sorting")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (viewModel.showFilters) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (viewModel.showFilters) {
                    Spacer(modifier = Modifier.height(12.dp))

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
                            Spacer(modifier = Modifier.height(4.dp))
                            FilterDropdown(
                                value = viewModel.timeFilter,
                                options = listOf("hour" to "Last Hour", "day" to "Last Day", "week" to "Last Week", "month" to "Last Month", "year" to "Last Year"),
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
                                options = listOf("0.0" to "All (0.0+)", "1.0" to "1.0+", "2.0" to "2.0+", "3.0" to "3.0+", "4.0" to "4.0+", "5.0" to "5.0+"),
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
                                options = listOf("time" to "Time", "magnitude" to "Magnitude", "depth" to "Depth"),
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

                viewModel.lastUpdate?.let { lastUpdate ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Last updated: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(lastUpdate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9CA3AF)
                    )
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
                    viewModel.selectedQuake = if (viewModel.selectedQuake == quake) null else quake
                },
                modifier = Modifier.fillMaxSize()
            )

            // Floating earthquake list card (bottom right)
            Card(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .width(350.dp)
                    .heightIn(max = 450.dp),
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
        }
    }
    //delete above
}

@Composable
fun MapView(
    earthquakes: List<Feature>,
    selectedQuake: Feature?,
    onQuakeSelected: (Feature) -> Unit,
    modifier: Modifier = Modifier
) {
    // Western Canada center coordinates (BC focus)
    val westCanadaCenter = LatLng(54.0, -125.0)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(westCanadaCenter, 5.5f)
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
            // Draw earthquake markers as circles
            earthquakes.forEach { quake ->
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

        // Selected earthquake info card (top right)
        selectedQuake?.let { quake ->
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
                            text = "M${String.format("%.1f", quake.properties.mag ?: 0.0)}",
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
                    DetailRow("Depth", "${String.format("%.1f", quake.geometry.coordinates.getOrNull(2) ?: 0.0)} km")
                    DetailRow("Coordinates", "${String.format("%.4f", quake.geometry.coordinates[1])}°, ${String.format("%.4f", quake.geometry.coordinates[0])}°")

                    quake.properties.felt?.let { felt ->
                        DetailRow("Felt Reports", "$felt people")
                    }
                }
            }
        }
    }
}

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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D).copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = viewModel.error ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.fetchEarthquakes() }) {
                            Text("Try again")
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
                            onClick = { viewModel.selectedQuake = if (viewModel.selectedQuake == quake) null else quake }
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
                        text = "M${String.format("%.1f", quake.properties.mag ?: 0.0)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = formatTime(quake.properties.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9CA3AF)
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
                    text = "Depth: ${String.format("%.1f", quake.geometry.coordinates.getOrNull(2) ?: 0.0)} km",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6B7280)
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
                    DetailRow("Coordinates", "${String.format("%.4f", quake.geometry.coordinates[1])}, ${String.format("%.4f", quake.geometry.coordinates[0])}")
                    DetailRow("Date", SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(quake.properties.time)))
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
        ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return
    }

    val notification = NotificationCompat.Builder(context, "earthquake_alerts")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("Significant Earthquake Detected!")
        .setContentText("M${String.format("%.1f", quake.properties.mag ?: 0.0)} - ${quake.properties.place}")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context).notify(quake.id.hashCode(), notification)
}