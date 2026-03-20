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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.cornerRadius
import androidx.glance.action.clickable
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rohanj.bartwidget.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONObject

data class Departure(
    val routeName: String,
    val destination: String,
    val time: Long,
    val delay: Long? = null
)

class BartWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        } catch (e: Exception) {
            android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        }

        provideContent {
            val size = LocalSize.current
            Log.d("BartFcmService", size.height.value.toString())
            val maxLinesPerDirection = when {
                size.height < 250.dp -> 1
                size.height < 330.dp -> 2
                else -> 5
            }
            
            val prefs = currentState<Preferences>()
            val isRefreshing = prefs[isRefreshingKey] ?: false
            val notificationData = prefs[widgetDataKey] ?: "Waiting for notification..."
            val savedStation = prefs[stationNameKey]

            val displayStation = if (savedStation != null) "$savedStation \u25BE" else "Select a Station \u25BE"
            val departuresList = mutableListOf<Pair<String, List<Departure>>>()

            val currentTimeMs = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
            val lastRenderedStr = dateFormat.format(Date(currentTimeMs))
            var lastSyncedStr = "Unknown"

            try {
                Log.d("MyCoolProcess", "Notification data: $notificationData")
                val json = JSONObject(notificationData)
                if (json.has("timestamp")) {
                    val tsString = json.optString("timestamp", "")
                    if (tsString.isNotEmpty()) {
                        try {
                            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC")
                            }
                            val parsedDate = isoFormat.parse(tsString)
                            if (parsedDate != null) {
                                lastSyncedStr = dateFormat.format(parsedDate)
                            } else {
                                throw Exception("Parsed date null")
                            }
                        } catch (e: Exception) {
                            val ts = json.optLong("timestamp", 0L)
                            if (ts > 0) {
                                val tsMs = if (ts > 20000000000L) ts else ts * 1000
                                lastSyncedStr = dateFormat.format(Date(tsMs))
                            }
                        }
                    }
                }
                
                val departuresStr = json.optString("departures", "{}")
                val departuresJson = JSONObject(departuresStr)
                
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

            val stationLines = departuresList.map { it.first }
            val condition1 = stationLines.none { it.startsWith("Orange") } || stationLines.any { it.startsWith("Blue") } || stationLines.any { it.startsWith("Green") }

            val outboundLines = mutableListOf<Pair<String, List<Departure>>>()
            val inboundLines = mutableListOf<Pair<String, List<Departure>>>()

            departuresList.forEach { (lineName, deps) ->
                val isOutbound = if (condition1) {
                    lineName in listOf("Blue-N", "Green-N", "Red-N", "Yellow-N", "Orange-S")
                } else {
                    lineName.endsWith("-N")
                }
                
                val isInbound = if (condition1) {
                    lineName in listOf("Blue-S", "Green-S", "Red-S", "Yellow-S", "Orange-N")
                } else {
                    lineName.endsWith("-S")
                }
                
                if (isOutbound) {
                    outboundLines.add(lineName to deps)
                } else if (isInbound) {
                    inboundLines.add(lineName to deps)
                }
            }

            outboundLines.sortBy { it.second.firstOrNull()?.time ?: Long.MAX_VALUE }
            val displayOutboundLines = outboundLines.take(maxLinesPerDirection)
            inboundLines.sortBy { it.second.firstOrNull()?.time ?: Long.MAX_VALUE }
            val displayInboundLines = inboundLines.take(maxLinesPerDirection)

            Column(
                modifier = GlanceModifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayStation,
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ColorProvider(Color.White)),
                        modifier = GlanceModifier.defaultWeight().clickable(
                            actionStartActivity(
                                Intent(context, MainActivity::class.java).apply {
                                    action = "SELECT_STATION"
                                    putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                }
                            )
                        )
                    )

                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = GlanceModifier.size(24.dp),
                            color = ColorProvider(Color.White)
                        )
                    } else {
                        Image(
                            provider = ImageProvider(R.drawable.ic_refresh),
                            contentDescription = "Refresh",
                            modifier = GlanceModifier
                                .size(24.dp)
                                .clickable(actionRunCallback<RefreshAction>())
                        )
                    }
                }

                if (outboundLines.isNotEmpty()) {
                    Text(
                        text = "Destination",
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ColorProvider(Color.Gray)),
                        modifier = GlanceModifier.padding(bottom = 8.dp)
                    )
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Column(modifier = GlanceModifier.padding(end = 8.dp)) {
                            displayOutboundLines.forEach { (lineName, deps) ->
                                val lineColor = when {
                                    lineName.startsWith("Blue") -> Color(0xFF00AEEF)
                                    lineName.startsWith("Red") -> Color(0xFFED1C24)
                                    lineName.startsWith("Yellow") -> Color(0xFFFFD100)
                                    lineName.startsWith("Green") -> Color(0xFF4DB848)
                                    lineName.startsWith("Orange") -> Color(0xFFF8A81D)
                                    else -> Color.Gray
                                }
                                Row(
                                    modifier = GlanceModifier.padding(bottom = 6.dp).height(32.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = GlanceModifier
                                            .size(12.dp)
                                            .background(lineColor)
                                            .cornerRadius(6.dp)
                                    ) {}
                                    Text(
                                        text = deps.first().destination,
                                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                        modifier = GlanceModifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                        Column {
                            displayOutboundLines.forEach { (_, deps) ->
                                Row(
                                    modifier = GlanceModifier.padding(bottom = 6.dp).height(32.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    deps.forEachIndexed { index, dep ->
                                        val timeMs = if (dep.time > 20000000000L) dep.time else dep.time * 1000
                                        val baseTime = timeMs - bootTimeMs
                                        val remoteViews = RemoteViews(context.packageName, R.layout.split_chronometer).apply {
                                            setChronometer(R.id.chronometer_min, baseTime, "%s", true)
                                            setChronometer(R.id.chronometer_sec, baseTime, "%s", true)
                                            if (dep.delay != null && dep.delay > 0) {
                                                val delayMins = (dep.delay + 59) / 60
                                                setViewVisibility(R.id.delay_text, android.view.View.VISIBLE)
                                                setTextViewText(R.id.delay_text, "(+$delayMins)")
                                            } else {
                                                setViewVisibility(R.id.delay_text, android.view.View.GONE)
                                            }
                                        }
                                        
                                        AndroidRemoteViews(remoteViews = remoteViews)

                                        if (index < deps.size - 1) {
                                            Text(
                                                text = ", ",
                                                style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp),
                                                modifier = GlanceModifier.padding(end = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (displayInboundLines.isNotEmpty()) {
                    Text(
                        text = "Destination",
                        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ColorProvider(Color.Gray)),
                        modifier = GlanceModifier.padding(top = 8.dp, bottom = 8.dp)
                    )
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Column(modifier = GlanceModifier.padding(end = 8.dp)) {
                            displayInboundLines.forEach { (lineName, deps) ->
                                val lineColor = when {
                                    lineName.startsWith("Blue") -> Color(0xFF00AEEF)
                                    lineName.startsWith("Red") -> Color(0xFFED1C24)
                                    lineName.startsWith("Yellow") -> Color(0xFFFFD100)
                                    lineName.startsWith("Green") -> Color(0xFF4DB848)
                                    lineName.startsWith("Orange") -> Color(0xFFF8A81D)
                                    else -> Color.Gray
                                }
                                Row(
                                    modifier = GlanceModifier.padding(bottom = 6.dp).height(32.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = GlanceModifier
                                            .size(12.dp)
                                            .background(lineColor)
                                            .cornerRadius(6.dp)
                                    ) {}
                                    Text(
                                        text = deps.first().destination,
                                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Medium),
                                        modifier = GlanceModifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                        Column {
                            displayInboundLines.forEach { (_, deps) ->
                                Row(
                                    modifier = GlanceModifier.padding(bottom = 6.dp).height(32.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    deps.forEachIndexed { index, dep ->
                                        val timeMs = if (dep.time > 20000000000L) dep.time else dep.time * 1000
                                        val baseTime = timeMs - bootTimeMs
                                        val remoteViews = RemoteViews(context.packageName, R.layout.split_chronometer).apply {
                                            setChronometer(R.id.chronometer_min, baseTime, "%s", true)
                                            setChronometer(R.id.chronometer_sec, baseTime, "%s", true)
                                            if (dep.delay != null && dep.delay > 0) {
                                                val delayMins = (dep.delay + 59) / 60
                                                setViewVisibility(R.id.delay_text, android.view.View.VISIBLE)
                                                setTextViewText(R.id.delay_text, "(+$delayMins)")
                                            } else {
                                                setViewVisibility(R.id.delay_text, android.view.View.GONE)
                                            }
                                        }
                                        
                                        AndroidRemoteViews(remoteViews = remoteViews)

                                        if (index < deps.size - 1) {
                                            Text(
                                                text = ", ",
                                                style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp),
                                                modifier = GlanceModifier.padding(end = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Column(modifier = GlanceModifier.padding(top = 16.dp)) {
                    Text(
                        text = "Last synced: $lastSyncedStr",
                        style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray))
                    )
                    Text(
                        text = "Last rendered: $lastRenderedStr",
                        style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.Gray)),
                        modifier = GlanceModifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }

    companion object {
        val widgetDataKey = stringPreferencesKey("notification_data")
        val isRefreshingKey = booleanPreferencesKey("is_refreshing")
        val forceRefreshKey = longPreferencesKey("force_refresh")
        val stationNameKey = stringPreferencesKey("station_name")
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[BartWidget.isRefreshingKey] = true
        }
        BartWidget().update(context, glanceId)
                
        var stationName: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            stationName = prefs[BartWidget.stationNameKey]
        }
        
        var newData: String? = null
        if (stationName != null) {
            try {
                newData = fetchRealtimeData(stationName!!)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[BartWidget.isRefreshingKey] = false
            prefs[BartWidget.forceRefreshKey] = System.currentTimeMillis() // Forces rapid re-renders effectively bypassing optimization traps
            
            newData?.let { data ->
                try {
                    updateWidgetDataIfNewer(prefs, data)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        BartWidget().update(context, glanceId)
    }
}