package com.rohanj.bartwidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.updateAll
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import tech.turso.libsql.Libsql


const val API_BASE_URL = "http://192.168.0.193:3000" // TODO: Change this to your actual API server URL
const val TURSO_URL = "libsql://realtimedata-aonedtop.aws-us-west-2.turso.io"
const val TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicm8iLCJpYXQiOjE3NzM4ODcxMzQsImlkIjoiMDE5ZDAzZTctNDgwMS03NDRkLWI5MDYtOTA4ZjFkNTQxN2ExIiwicmlkIjoiZmUxMGRmYTktN2E0Mi00OTI1LThmNDItOGNiNzEzZGE5YzcyIn0._8CKu3Uv6uH6BZ_DZksTmogtw9Xprr5v-gwADzXkK1Ma2pLnWB97BeMHfwLcAZbPlTnjjSz0TqkC2kY_eVDeCQ"

data class Station(val name: String, val lat: Double, val lon: Double)

fun parseStationsData(dataStr: String): List<Station> {
    return try {
        val stationsMap = JSONObject(dataStr)
        val fetchedStations = mutableListOf<Station>()
        val keys = stationsMap.keys()
        val addedNames = mutableSetOf<String>()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = stationsMap.getJSONObject(key)
            val name = obj.getString("name")
            if (!addedNames.contains(name)) {
                val lat = obj.optString("lat", "0").toDoubleOrNull() ?: 0.0
                val lon = obj.optString("lon", "0").toDoubleOrNull() ?: 0.0
                fetchedStations.add(Station(name, lat, lon))
                addedNames.add(name)
            }
        }
        fetchedStations.sortedBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }
}

val BART_LINES = mapOf(
    "Red-N" to "Red - Richmond",
    "Red-S" to "Red - Millbrae",
    "Yellow-N" to "Yellow - Antioch",
    "Yellow-S" to "Yellow - SFO",
    "Green-N" to "Green - Berryessa",
    "Green-S" to "Green - Daly City",
    "Orange-N" to "Orange - Richmond",
    "Orange-S" to "Orange - Berryessa",
    "Blue-N" to "Blue - Dublin/Pleasanton",
    "Blue-S" to "Blue - Daly City"
)

suspend fun fetchRealtimeData(stationName: String): String? {
    return withContext(Dispatchers.IO) {
        val db = Libsql.open(
            url = TURSO_URL,
            authToken = TURSO_TOKEN
        )
        db.connect().use { conn ->
            val safeStationName = stationName.replace("'", "''")
            val rs = conn.query("SELECT data FROM realtime_data WHERE station = '$safeStationName'")
            val row = rs.firstOrNull()
            if (row != null) row[0] as? String else null
        }
    }
}

fun formatWidgetData(responseData: String): String {
    val responseJson = JSONObject(responseData)
    val widgetData = JSONObject()
    val keys = responseJson.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = responseJson.get(key)
        widgetData.put(key, if (value is JSONObject || value is org.json.JSONArray) value.toString() else value.toString())
    }
    return widgetData.toString()
}

fun extractTimestamp(json: JSONObject): Long {
    val tsString = json.optString("timestamp", "")
    if (tsString.isNotEmpty()) {
        try {
            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            return isoFormat.parse(tsString)?.time ?: 0L
        } catch (e: Exception) {
            val ts = json.optLong("timestamp", 0L)
            if (ts != 0L) return ts
            return tsString.toLongOrNull() ?: 0L
        }
    }
    return 0L
}

fun updateWidgetDataIfNewer(prefs: MutablePreferences, newDataStr: String): Boolean {
    val expectedStation = prefs[BartWidget.stationNameKey]
    val newFormattedData = formatWidgetData(newDataStr)
    val newJson = JSONObject(newFormattedData)
    val newTimestamp = extractTimestamp(newJson)
    val newStation = newJson.optString("station", "")

    if (expectedStation != null && newStation.isNotEmpty() && expectedStation != newStation) {
        android.util.Log.d("WidgetData", "Ignoring payload. Station mismatch: expected $expectedStation, got $newStation")
        return false
    }

    val existingData = prefs[BartWidget.widgetDataKey]
    var existingTimestamp = 0L
    var existingStation = ""
    if (existingData != null) {
        try {
            val existingJson = JSONObject(existingData)
            existingTimestamp = extractTimestamp(existingJson)
            existingStation = existingJson.optString("station", "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val isExistingStationWrong = expectedStation != null && existingStation.isNotEmpty() && existingStation != expectedStation

    if (isExistingStationWrong || newTimestamp > existingTimestamp || newTimestamp == 0L) {
        prefs[BartWidget.widgetDataKey] = newFormattedData
        return true
    } else {
        android.util.Log.d("WidgetData", "Ignoring payload. New timestamp ($newTimestamp) <= existing ($existingTimestamp)")
        return false
    }
}

fun stationNameToTopic(stationName: String): String {
    return stationName.replace(Regex("[^a-zA-Z0-9-_.~%]"), "_")
}

class MainActivity : ComponentActivity() {

    private var appWidgetId: Int = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
            android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        
        val passedStation = intent?.getStringExtra("station_name")
        val passedFilters = intent?.getStringArrayExtra("filtered_lines")?.toSet()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (appWidgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
                        InstructionsScreen()
                    } else {
                        var selectedStation by remember { mutableStateOf<String?>(passedStation) }
                        if (selectedStation == null) {
                            StationSelectionScreen(
                                onStationSelected = { station ->
                                    selectedStation = station
                                }
                            )
                        } else {
                            FilterLinesScreen(
                                initialFilters = passedFilters,
                                onFiltersComplete = { selectedLines ->
                                    registerStation(selectedStation!!, selectedLines, appWidgetId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun registerStation(stationName: String, selectedLines: Set<String>, appWidgetId: Int) {   
        if (appWidgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            Toast.makeText(this, "Please add a widget to the home screen and press Select Station on the widget.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val isSameStation = stationName == intent?.getStringExtra("station_name")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val manager = GlanceAppWidgetManager(this@MainActivity)
                val targetGlanceId = manager.getGlanceIdBy(appWidgetId)
                
                var errorMessage: String? = null
                var responseData: String? = null
                val success = if (isSameStation) {
                    true
                } else {
                    try {
                        responseData = fetchRealtimeData(stationName)
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        errorMessage = e.localizedMessage
                        false
                    }
                }

                if (success) {
                    if (!isSameStation) {
                        try {
                            val topic = "BART_${stationNameToTopic(stationName)}"
                            FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    updateAppWidgetState(this@MainActivity, targetGlanceId) { prefs ->
                        prefs[BartWidget.stationNameKey] = stationName
                        
                        val filteredLinesKey = stringSetPreferencesKey("filtered_lines")
                        if (selectedLines.size == BART_LINES.size) {
                            prefs.remove(filteredLinesKey)
                        } else {
                            prefs[filteredLinesKey] = selectedLines
                        }
                        
                        if (responseData != null) {
                            try {
                                updateWidgetDataIfNewer(prefs, responseData)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    BartWidget().update(this@MainActivity, targetGlanceId)

                    if (!isSameStation) {
                        Toast.makeText(this@MainActivity, "Subscribed to $stationName!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Filters updated!", Toast.LENGTH_SHORT).show()
                    }
                    
                    val resultValue = Intent().putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    setResult(RESULT_OK, resultValue)
                    finish()
                } else {
                    Toast.makeText(this@MainActivity, "An issue occurred: ${errorMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "An issue occurred: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun FilterLinesScreen(initialFilters: Set<String>?, onFiltersComplete: (Set<String>) -> Unit) {
    var selectedLines by remember { mutableStateOf(initialFilters ?: BART_LINES.keys.toSet()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Filter Lines",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(BART_LINES.entries.toList()) { entry ->
                val lineId = entry.key
                val lineName = entry.value
                
                val lineColor = when {
                    lineName.startsWith("Blue") -> Color(0xFF00AEEF)
                    lineName.startsWith("Red") -> Color(0xFFED1C24)
                    lineName.startsWith("Yellow") -> Color(0xFFFFD100)
                    lineName.startsWith("Green") -> Color(0xFF4DB848)
                    lineName.startsWith("Orange") -> Color(0xFFF8A81D)
                    else -> Color.Gray
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedLines = if (selectedLines.contains(lineId)) {
                                selectedLines - lineId
                            } else {
                                selectedLines + lineId
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedLines.contains(lineId),
                        onCheckedChange = null
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(lineColor, RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = lineName, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
        }
        
        Button(
            onClick = { onFiltersComplete(selectedLines) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("Save & Complete")
        }
    }
}

@Composable
fun InstructionsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Welcome to BART Widget",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Text(
            text = "This app runs primarily as a Home Screen widget.\n\n" +
                   "To get started:\n\n" +
                   "1. Go to your Android Home Screen.\n" +
                   "2. Long-press on any empty space.\n" +
                   "3. Tap 'Widgets' and find 'Widget for BART'.\n" +
                   "4. Drag the widget onto your Home Screen.\n" +
                   "5. Tap the 'Select a Station' text on the widget to configure it.\n\n" +
                   "You can resize the widget to fit more or fewer train lines.",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSelectionScreen(onStationSelected: (String) -> Unit) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("bart_stations_cache", android.content.Context.MODE_PRIVATE)

    var stations by remember {
        mutableStateOf<List<Station>>(
            sharedPrefs.getString("stations_v2", null)?.let {
                parseStationsData(it)
            } ?: emptyList()
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(stations.isEmpty()) }
    var userLocation by remember { mutableStateOf<android.location.Location?>(null) }

    val locationPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)) {
            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            try {
                val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    userLocation = loc
                } else {
                    Toast.makeText(context, "Could not determine location", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) { e.printStackTrace() }
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            try {
                val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    userLocation = loc
                }
            } catch (e: SecurityException) { e.printStackTrace() }
        }

        withContext(Dispatchers.IO) {
            try {
                val db = Libsql.open(
                    url = TURSO_URL,
                    authToken = TURSO_TOKEN
                )

                var dataStr: String? = null
                db.connect().use { conn ->
                    val rs = conn.query("SELECT data FROM stations WHERE system = 'BART2'")
                    val row = rs.firstOrNull()
                    if (row != null) {
                        dataStr = row[0] as? String
                    }
                }
                
                if (dataStr != null) {
                    sharedPrefs.edit().putString("stations_v2", dataStr).apply()
                    val fetchedStations = parseStationsData(dataStr!!)
                    withContext(Dispatchers.Main) {
                        stations = fetchedStations
                    }
                } else if (stations.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No stations found in database", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (stations.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "An issue occurred fetching stations: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Station") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                        try {
                            val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                                ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                            if (loc != null) {
                                userLocation = loc
                            } else {
                                Toast.makeText(context, "Could not determine location", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: SecurityException) { e.printStackTrace() }
                    } else {
                        locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            ) {
                Text("Nearby")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            val filteredStations = stations.filter { it.name.contains(searchQuery, ignoreCase = true) }.let { list ->
                if (userLocation != null) {
                    list.sortedBy { station ->
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(userLocation!!.latitude, userLocation!!.longitude, station.lat, station.lon, results)
                        results[0]
                    }
                } else list
            }
            LazyColumn {
                items(filteredStations) { station ->
                    var distanceText = ""
                    if (userLocation != null) {
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(userLocation!!.latitude, userLocation!!.longitude, station.lat, station.lon, results)
                        val distanceMiles = results[0] / 1609.344f
                        if (distanceMiles < 10f) {
                            distanceText = String.format(java.util.Locale.getDefault(), "%.2f mi", distanceMiles)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStationSelected(station.name) }
                            .padding(16.dp)
                    ) {
                        Text(
                            text = station.name,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (distanceText.isNotEmpty()) {
                            Text(
                                text = distanceText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}