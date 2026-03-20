package com.rohanj.bartwidget

import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rohanj.bartwidget.BartWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BartFcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Convert the data payload map to a JSON string
        val json = org.json.JSONObject()
        message.data.forEach { (key, value) ->
            json.put(key, value)
        }
        val dataPayload = json.toString()
        
        val incomingStation = json.optString("station", "")
        Log.d("BartFcmService", "Received payload for station $incomingStation: $dataPayload")

        // Dispatch an update to all instances of our widget
        CoroutineScope(Dispatchers.IO).launch {
            val manager = GlanceAppWidgetManager(applicationContext)
            val widget = BartWidget()
            val glanceIds = manager.getGlanceIds(widget.javaClass)
            var shouldUpdateWidget = false
            var isStationTracked = false

            glanceIds.forEach { glanceId ->
                updateAppWidgetState(applicationContext, glanceId) { prefs ->
                    val widgetStation = prefs[BartWidget.stationNameKey]
                    if (widgetStation == incomingStation) {
                        isStationTracked = true
                        if (updateWidgetDataIfNewer(prefs, dataPayload)) {
                            shouldUpdateWidget = true
                        }
                    }
                }
            }
            
            if (shouldUpdateWidget) {
                widget.updateAll(applicationContext)
            }
            
            // Lazy cleanup: If we receive a push for a station that is no longer tracked, unsubscribe from it.
            if (!isStationTracked && incomingStation.isNotEmpty()) {
                try {
                    val topic = "BART_${stationNameToTopic(incomingStation)}"
                    FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
                    Log.d("BartFcmService", "No widgets tracking $incomingStation. Unsubscribed from topic: $topic")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("BartFcmService", "New FCM token: $token")
    }
}