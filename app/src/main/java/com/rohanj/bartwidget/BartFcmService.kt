package com.rohanj.bartwidget

import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rohanj.bartwidget.BartWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BartFcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Convert the data payload map to a JSON string
        val json = org.json.JSONObject()
        message.data.forEach { (key, value) ->
            json.put(key, value)
        }
        val dataPayload = json.toString()
        val newTimestamp = message.data["timestamp"]?.toLongOrNull() ?: 0L
        Log.d("BartFcmService", "Received payload with timestamp $newTimestamp: $dataPayload")

        // Dispatch an update to all instances of our widget
        CoroutineScope(Dispatchers.IO).launch {
            val manager = GlanceAppWidgetManager(applicationContext)
            val widget = BartWidget()
            val glanceIds = manager.getGlanceIds(widget.javaClass)
            var shouldUpdateWidget = false

            glanceIds.forEach { glanceId ->
                updateAppWidgetState(applicationContext, glanceId) { prefs ->
                    val existingData = prefs[BartWidget.widgetDataKey]
                    var existingTimestamp = 0L
                    if (existingData != null) {
                        try {
                            existingTimestamp = org.json.JSONObject(existingData).optLong("timestamp", 0L)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (newTimestamp > existingTimestamp || newTimestamp == 0L) {
                        prefs[BartWidget.widgetDataKey] = dataPayload
                        shouldUpdateWidget = true
                    } else {
                        Log.d("BartFcmService", "Ignoring payload. New timestamp ($newTimestamp) <= existing ($existingTimestamp)")
                    }
                }
            }
            
            if (shouldUpdateWidget) {
                widget.updateAll(applicationContext)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("BartFcmService", "New FCM token: $token")
    }
}