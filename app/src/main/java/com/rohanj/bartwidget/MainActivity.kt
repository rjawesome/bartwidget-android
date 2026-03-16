package com.rohanj.bartwidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

const val API_BASE_URL = "http://192.168.0.193:3000" // TODO: Change this to your actual API server URL

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Push notifications permission is required to receive updates.", Toast.LENGTH_LONG).show()
        }
        else {
            Toast.makeText(this, "Notification permissions succesfully granted. Select station again.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StationSelectionScreen(
                        onStationSelected = { station ->
                            registerStation(station)
                        }
                    )
                }
            }
        }
    }

    private fun registerStation(stationName: String) {
        // Enforce push permissions at registration checkpoint
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                
                var responseData: String? = null
                val success = withContext(Dispatchers.IO) {
                    val url = URL("$API_BASE_URL/register")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.doOutput = true

                    val jsonParam = JSONObject().apply {
                        put("fcmId", token)
                        put("stationName", stationName)
                    }

                    val writer = OutputStreamWriter(connection.outputStream)
                    writer.write(jsonParam.toString())
                    writer.flush()
                    writer.close()

                    val responseCode = connection.responseCode
                    if (responseCode in 200..299) {
                        responseData = connection.inputStream.bufferedReader().use { it.readText() }
                        true
                    } else {
                        false
                    }
                }

                if (success) {
                    Toast.makeText(this@MainActivity, "Registered for $stationName!", Toast.LENGTH_SHORT).show()
                    
                    // Safely lock stationName into the App Widget DataStore
                    val manager = GlanceAppWidgetManager(this@MainActivity)
                    val glanceIds = manager.getGlanceIds(BartWidget::class.java)
                    
                    glanceIds.forEach { glanceId ->
                        updateAppWidgetState(this@MainActivity, glanceId) { prefs ->
                            prefs[BartWidget.stationNameKey] = stationName
                            
                            if (responseData != null) {
                                try {
                                    val responseJson = JSONObject(responseData!!)
                                    val widgetData = JSONObject()
                                    val keys = responseJson.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        val value = responseJson.get(key)
                                        widgetData.put(key, if (value is JSONObject || value is org.json.JSONArray) value.toString() else value.toString())
                                    }
                                    prefs[BartWidget.widgetDataKey] = widgetData.toString()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                    
                    // Manually force a widget re-render from here
                    BartWidget().updateAll(this@MainActivity)
                    finish()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to register.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error during registration.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSelectionScreen(onStationSelected: (String) -> Unit) {
    var stations by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$API_BASE_URL/stations")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val stationsArray = json.optJSONArray("stations")
                    val fetchedStations = mutableListOf<String>()
                    if (stationsArray != null) {
                        for (i in 0 until stationsArray.length()) {
                            fetchedStations.add(stationsArray.getString(i))
                        }
                    }
                    stations = fetchedStations
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                    Divider()
                }
            }
        }
    }
}