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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (appWidgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
                        InstructionsScreen()
                    } else {
                        StationSelectionScreen(
                            onStationSelected = { station ->
                                registerStation(station, appWidgetId)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun registerStation(stationName: String, appWidgetId: Int) {   
        if (appWidgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
            Toast.makeText(this, "Please add a widget to the home screen and press Select Station on the widget.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val manager = GlanceAppWidgetManager(this@MainActivity)
                val targetGlanceId = manager.getGlanceIdBy(appWidgetId)
                
                var errorMessage: String? = null
                var responseData: String? = null
                val success = try {
                    responseData = fetchRealtimeData(stationName)
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorMessage = e.localizedMessage
                    false
                }

                if (success) {
                    try {
                        val topic = "BART_${stationNameToTopic(stationName)}"
                        FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    updateAppWidgetState(this@MainActivity, targetGlanceId) { prefs ->
                        prefs[BartWidget.stationNameKey] = stationName
                        
                        if (responseData != null) {
                            try {
                                updateWidgetDataIfNewer(prefs, responseData)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    BartWidget().update(this@MainActivity, targetGlanceId)

                    Toast.makeText(this@MainActivity, "Subscribed to $stationName!", Toast.LENGTH_SHORT).show()
                    
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
                   "3. Tap 'Widgets' and find 'BART Widget'.\n" +
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
    var stations by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val db = Libsql.open(
                    url = TURSO_URL,
                    authToken = TURSO_TOKEN
                )

                var dataStr: String? = null
                db.connect().use { conn ->
                    val rs = conn.query("SELECT data FROM stations WHERE system = 'BART'")
                    val row = rs.firstOrNull()
                    if (row != null) {
                        dataStr = row[0] as? String
                    }
                }
                
                if (dataStr != null) {
                    val stationsArray = JSONArray(dataStr)
                    val fetchedStations = mutableListOf<String>()
                    for (i in 0 until stationsArray.length()) {
                        fetchedStations.add(stationsArray.getString(i))
                    }
                    stations = fetchedStations
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No stations found in database", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "An issue occurred fetching stations: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search Station") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            val filteredStations = stations.filter { it.contains(searchQuery, ignoreCase = true) }
            LazyColumn {
                items(filteredStations) { station ->
                    Text(
                        text = station,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStationSelected(station) }
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}