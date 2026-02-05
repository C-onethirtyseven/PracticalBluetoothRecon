package com.example.wifibtlogger

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.time.Instant
import java.util.Locale

class DistanceAlertsActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var mode: FilterMode = FilterMode.DIST_4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_distance_alerts)
        title = "Co Travelers"

        listView = findViewById(R.id.distanceList)
        emptyView = findViewById(R.id.distanceEmpty)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter
        listView.emptyView = emptyView

        loadDistanceAlerts()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.co_travelers_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        mode = when (item.itemId) {
            R.id.action_most_seen -> FilterMode.MOST_SEEN
            R.id.action_dist_2 -> FilterMode.DIST_2
            R.id.action_dist_4 -> FilterMode.DIST_4
            R.id.action_dist_6 -> FilterMode.DIST_6
            R.id.action_dist_8 -> FilterMode.DIST_8
            R.id.action_dist_10 -> FilterMode.DIST_10_PLUS
            else -> mode
        }
        loadDistanceAlerts()
        return true
    }

    private fun loadDistanceAlerts() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val csvValue = prefs.getString(PREF_LAST_CSV, null)
        val uri = if (csvValue != null) {
            if (csvValue.startsWith("content://")) Uri.parse(csvValue) else Uri.fromFile(java.io.File(csvValue))
        } else null

        val records = CsvUtils.readCsvRecords(this, uri, 200000)
        val grouped = LinkedHashMap<String, DeviceAgg>()
        for (r in records) {
            val key = "${r.type}|${r.id}"
            val agg = grouped.getOrPut(key) { DeviceAgg(r.type, r.id) }
            agg.count += 1
            val ts = parseInstant(r.timestamp)
            if (ts >= agg.lastSeen) {
                agg.lastSeen = ts
                agg.name = r.name
                agg.rssi = r.rssi
            }
            if (r.latitude != null && r.longitude != null) {
                agg.addPoint(r.latitude, r.longitude)
            }
        }

        val results = ArrayList<DeviceAgg>()
        for (agg in grouped.values) {
            if (agg.count < 2) continue
            if (mode == FilterMode.MOST_SEEN) {
                results.add(agg)
                continue
            }
            val maxDist = agg.maxDistanceMiles
            val include = when (mode) {
                FilterMode.DIST_2 -> maxDist >= 2.0 && maxDist < 4.0
                FilterMode.DIST_4 -> maxDist >= 4.0 && maxDist < 6.0
                FilterMode.DIST_6 -> maxDist >= 6.0 && maxDist < 8.0
                FilterMode.DIST_8 -> maxDist >= 8.0 && maxDist < 10.0
                FilterMode.DIST_10_PLUS -> maxDist >= 10.0
                FilterMode.MOST_SEEN -> true
            }
            if (include) results.add(agg)
        }

        results.sortByDescending { it.count }
        val lines = results.map { agg ->
            String.format(
                Locale.US,
                "%s | %s | RSSI %d | Seen %d | Max %.1f mi",
                agg.name,
                agg.id,
                agg.rssi,
                agg.count,
                agg.maxDistanceMiles
            )
        }
        adapter.clear()
        adapter.addAll(lines)
        adapter.notifyDataSetChanged()
    }

    private fun parseInstant(value: String): Long {
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    companion object {
        private const val PREFS_NAME = "wifi_bt_prefs"
        private const val PREF_LAST_CSV = "last_run_csv"
    }
}

private enum class FilterMode {
    MOST_SEEN,
    DIST_2,
    DIST_4,
    DIST_6,
    DIST_8,
    DIST_10_PLUS
}
