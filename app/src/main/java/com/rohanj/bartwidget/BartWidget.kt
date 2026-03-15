package com.rohanj.bartwidget

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rohanj.bartwidget.MainActivity
import org.json.JSONObject

data class Departure(
    val routeName: String,
    val destination: String,
    val time: Long,
    val delay: Long? = null
)

class BartWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val notificationData = prefs[widgetDataKey] ?: "Waiting for notification..."

            var stationName = "Waiting for data..."
            val departuresList = mutableListOf<Pair<String, List<Departure>>>()

            try {
                val json = JSONObject(notificationData)
                if (json.has("station")) {
                    stationName = json.optString("station", "Unknown Station")
                }
                
                val departuresStr = json.optString("departures", "{}")
                val departuresJson = JSONObject(departuresStr)
                
                val currentTimeMs = System.currentTimeMillis()
                val keys = departuresJson.keys()
                while (keys.hasNext()) {
                    val lineName = keys.next()
                    val depsArray = departuresJson.optJSONArray(lineName)
                    val deps = mutableListOf<Departure>()
                    if (depsArray != null) {
                        for (i in 0 until depsArray.length()) {
                            val depObj = depsArray.optJSONObject(i)
                            if (depObj != null) {
                                val delayOpt = if (depObj.has("delay")) depObj.optLong("delay") else null
                                val time = depObj.optLong("time", 0L)
                                val timeMs = if (time > 20000000000L) time else time * 1000
                                val delayMs = (delayOpt ?: 0L) * 1000L
                                
                                if (timeMs >= currentTimeMs) {
                                    deps.add(
                                        Departure(
                                            routeName = depObj.optString("routeName", ""),
                                            destination = depObj.optString("destination", ""),
                                            time = time,
                                            delay = delayOpt
                                        )
                                    )
                                }
                            }
                        }
                    }
                    if (deps.isNotEmpty()) {
                        departuresList.add(lineName to deps)
                    }
                }
            } catch (e: Exception) {
                Log.e("MyCoolProcess", "Failed to update widget state", e)
            }

            // Calculate the device boot time offset to keep Chronometer baseTime perfectly stable across push updates
            val bootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

            Column(
                modifier = GlanceModifier.fillMaxSize().background(Color.White).padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = stationName,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    modifier = GlanceModifier.padding(bottom = 8.dp)
                )

                // Replaced LazyColumn with a standard Column to prevent Chronometer recycling bugs
                Column {
                    departuresList.forEach { (lineName, deps) ->
                        Column(modifier = GlanceModifier.padding(bottom = 8.dp)) {
                            Text(
                                text = lineName,
                                style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp)
                            )
                            deps.forEach { dep ->
                                // Gracefully handle both seconds and milliseconds Unix timestamps
                                val timeMs = if (dep.time > 20000000000L) dep.time else dep.time * 1000
                                val baseTime = timeMs - bootTimeMs
                                
                                Row(
                                    modifier = GlanceModifier.padding(start = 8.dp, top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "-> ${dep.destination}: ", style = TextStyle(fontSize = 14.sp))
                                    val remoteViews = RemoteViews(context.packageName, R.layout.chronometer_item).apply {
                                        // Explicitly passing "%s" prevents a known Android bug where Chronometers freeze on RemoteViews update
                                        setChronometer(R.id.chronometer, baseTime, "%s", true)
                                    }
                                    AndroidRemoteViews(
                                        remoteViews = remoteViews,
                                        modifier = GlanceModifier.height(36.dp)
                                    )
                                    
                                    if (dep.delay != null && dep.delay > 0) {
                                        val delayMins = (dep.delay + 59) / 60
                                        Text(
                                            text = " ($delayMins min delay)",
                                            style = TextStyle(
                                                fontSize = 12.sp,
                                                color = ColorProvider(Color.Red)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    text = "Register",
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
                    onClick = actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            action = "REGISTER_ACTION"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                )
            }
        }
    }

    companion object {
        val widgetDataKey = stringPreferencesKey("notification_data")
    }
}