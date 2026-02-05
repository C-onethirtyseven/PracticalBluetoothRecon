package com.example.wifibtlogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val dwell = prefs.getBoolean(PREF_DWELL_MODE, false)
                val serviceIntent = Intent(context, ScanService::class.java).apply {
                    action = ScanService.ACTION_START
                    putExtra(ScanService.EXTRA_DWELL, dwell)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            ACTION_STOP -> {
                val serviceIntent = Intent(context, ScanService::class.java).apply {
                    action = ScanService.ACTION_STOP
                }
                context.startService(serviceIntent)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.wifibtlogger.SCHEDULE_START"
        const val ACTION_STOP = "com.example.wifibtlogger.SCHEDULE_STOP"

        private const val PREFS_NAME = "wifi_bt_prefs"
        private const val PREF_DWELL_MODE = "dwell_mode"
    }
}
