package com.example.wifibtlogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import java.time.Instant

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var dwellButton: Button
    private lateinit var highIntensityButton: Button
    private lateinit var lowPowerButton: Button
    private lateinit var trackingSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var countsText: TextView
    private lateinit var locationText: TextView
    private lateinit var alertText: TextView
    private lateinit var filterWifi: CheckBox
    private lateinit var filterBt: CheckBox
    private lateinit var filterBle: CheckBox
    private lateinit var filterCell: CheckBox
    private lateinit var rssiInput: EditText
    private lateinit var sortSpinner: Spinner
    private lateinit var deviceList: RecyclerView
    private lateinit var toolbar: Toolbar

    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = DeviceAdapter { item -> showDeviceDialog(item) }
    private var lastAlert = ""
    private var lastMgrs = ""

    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshList()
            handler.postDelayed(this, 2000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateStatus("Permissions updated")
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val wifi = intent.getIntExtra(ScanService.EXTRA_WIFI, 0)
            val bt = intent.getIntExtra(ScanService.EXTRA_BT, 0)
            val ble = intent.getIntExtra(ScanService.EXTRA_BLE, 0)
            val cell = intent.getIntExtra(ScanService.EXTRA_CELL, 0)
            val alert = intent.getStringExtra(ScanService.EXTRA_ALERT) ?: ""
            val mgrs = intent.getStringExtra(ScanService.EXTRA_MGRS) ?: ""

            if (mgrs.isNotBlank() && mgrs != lastMgrs) {
                lastMgrs = mgrs
                locationText.text = "Location: $mgrs"
            }
            if (alert.isNotBlank() && alert != lastAlert) {
                lastAlert = alert
                alertText.text = alert
            }
            // If list refresh is paused, keep a lightweight count update
            if (!isScanning) {
                countsText.text = "WiFi: $wifi   BT: $bt   BLE: $ble   CELL: $cell"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        dwellButton = findViewById(R.id.dwellButton)
        highIntensityButton = findViewById(R.id.highIntensityButton)
        lowPowerButton = findViewById(R.id.lowPowerButton)
        toolbar = findViewById(R.id.toolbar)
        trackingSwitch = findViewById(R.id.trackingSwitch)
        statusText = findViewById(R.id.statusText)
        countsText = findViewById(R.id.countsText)
        locationText = findViewById(R.id.locationText)
        alertText = findViewById(R.id.alertText)
        filterWifi = findViewById(R.id.filterWifi)
        filterBt = findViewById(R.id.filterBt)
        filterBle = findViewById(R.id.filterBle)
        filterCell = findViewById(R.id.filterCell)
        rssiInput = findViewById(R.id.rssiInput)
        sortSpinner = findViewById(R.id.sortSpinner)
        deviceList = findViewById(R.id.deviceList)

        setSupportActionBar(toolbar)
        supportActionBar?.title = "Practical Bluetooth Recon"
        dwellButton.text = if (isDwellMode()) "Dwell Mode: On" else "Dwell Mode: Off"

        toggleButton.setOnClickListener {
            if (isScanning) {
                stopScanning()
            } else {
                ensurePermissionsThenStart()
            }
        }

        dwellButton.setOnClickListener {
            toggleDwellMode()
        }

        highIntensityButton.setOnClickListener {
            setScanMode(SCAN_MODE_HIGH)
        }

        lowPowerButton.setOnClickListener {
            setScanMode(SCAN_MODE_LOW)
        }

        val filter = IntentFilter(ScanService.ACTION_STATUS)
        registerAppReceiver(statusReceiver, filter)

        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = adapter

        val sortOptions = listOf("RSSI (Strongest)", "Time Seen (Latest)", "Times Seen (Most)")
        sortSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sortSpinner.setSelection(0)
        sortSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { refreshList() })

        filterWifi.setOnCheckedChangeListener { _, _ -> refreshList() }
        filterBt.setOnCheckedChangeListener { _, _ -> refreshList() }
        filterBle.setOnCheckedChangeListener { _, _ -> refreshList() }
        filterCell.setOnCheckedChangeListener { _, _ -> refreshList() }
        rssiInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { refreshList() }
        })

        requestStoragePermissionIfNeeded()
        ensurePinSetup()
        updateScanModeButtons()
    }

    override fun onStop() {
        super.onStop()
        if (isScanning && !trackingSwitch.isChecked) {
            stopScanning()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        handler.removeCallbacks(refreshLoop)
    }

    private fun ensurePermissionsThenStart() {
        val missing = requiredPermissions().filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startScanning()
        }
    }

    private fun requiredPermissions(): List<String> {
        val base = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            base.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            base.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return base
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun startScanning() {
        isScanning = true
        toggleButton.text = "Stop Scanning"
        updateStatus("Scanning")
        val intent = Intent(this, ScanService::class.java).apply {
            action = ScanService.ACTION_START
            putExtra(ScanService.EXTRA_DWELL, isDwellMode())
            putExtra(ScanService.EXTRA_SCAN_MODE, getScanMode())
        }
        ContextCompat.startForegroundService(this, intent)
        handler.post(refreshLoop)
    }

    private fun stopScanning() {
        isScanning = false
        toggleButton.text = "Start Scanning"
        updateStatus("Idle")
        val intent = Intent(this, ScanService::class.java).apply {
            action = ScanService.ACTION_STOP
        }
        startService(intent)
        handler.removeCallbacks(refreshLoop)
    }

    private fun updateStatus(msg: String) {
        statusText.text = "Status: $msg"
    }

    private fun csvUri(): Uri? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(PREF_LAST_CSV, null) ?: return null
        return if (value.startsWith("content://") || value.startsWith("file:")) {
            Uri.parse(value)
        } else {
            Uri.fromFile(File(value))
        }
    }

    private fun kmlUri(): Uri? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(PREF_LAST_KML, null) ?: return null
        return if (value.startsWith("content://") || value.startsWith("file:")) {
            Uri.parse(value)
        } else {
            Uri.fromFile(File(value))
        }
    }

    private fun shareFile(uri: Uri?, mime: String) {
        if (uri == null) return
        val safeUri = if (uri.scheme == "file") {
            val file = File(uri.path ?: return)
            if (!file.exists()) return
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } else {
            uri
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, safeUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share file"))
    }

    private fun refreshList() {
        val records = CsvUtils.readCsvRecords(this, csvUri(), 5000)

        val showWifi = filterWifi.isChecked
        val showBt = filterBt.isChecked
        val showBle = filterBle.isChecked
        val showCell = filterCell.isChecked
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val settingsMin = prefs.getInt(PREF_RSSI_MIN, -150)
        val settingsMax = prefs.getInt(PREF_RSSI_MAX, 0)
        val inputMin = rssiInput.text.toString().toIntOrNull()
        val minRssi = if (inputMin != null) maxOf(settingsMin, inputMin) else settingsMin
        val maxRssi = settingsMax

        val whitelist = getWhitelist()
        val aggregate = LinkedHashMap<String, DeviceItem>()
        var latestLat: Double? = null
        var latestLon: Double? = null
        var latestTs = 0L
        for (r in records) {
            val ts = parseInstant(r.timestamp)
            if (r.latitude != null && r.longitude != null && ts >= latestTs) {
                latestTs = ts
                latestLat = r.latitude
                latestLon = r.longitude
            }
            if (!shouldInclude(r, showWifi, showBt, showBle, showCell, minRssi, maxRssi)) continue
            val key = "${r.type}|${r.id}"
            if (whitelist.contains(key)) continue
            val item = aggregate[key]
            if (item == null) {
                aggregate[key] = DeviceItem(
                    type = r.type,
                    name = r.name,
                    id = r.id,
                    rssi = r.rssi,
                    count = 1,
                    firstSeen = ts,
                    firstSeenText = r.timestamp,
                    lastSeen = ts,
                    lastSeenText = r.timestamp,
                    lastLat = r.latitude,
                    lastLon = r.longitude
                )
            } else {
                val newCount = item.count + 1
                val newer = ts > item.lastSeen
                aggregate[key] = item.copy(
                    rssi = if (newer) r.rssi else item.rssi,
                    count = newCount,
                    lastSeen = if (newer) ts else item.lastSeen,
                    lastSeenText = if (newer) r.timestamp else item.lastSeenText,
                    lastLat = if (newer) r.latitude else item.lastLat,
                    lastLon = if (newer) r.longitude else item.lastLon
                )
            }
        }

        // Update unique device counts based on aggregated list
        var wifiUnique = 0
        var btUnique = 0
        var bleUnique = 0
        var cellUnique = 0
        for (item in aggregate.values) {
            when (item.type) {
                "WIFI" -> wifiUnique += 1
                "BT" -> btUnique += 1
                "BLE" -> bleUnique += 1
                "CELL" -> cellUnique += 1
            }
        }
        countsText.text = "WiFi: $wifiUnique   BT: $btUnique   BLE: $bleUnique   CELL: $cellUnique"

        if (latestLat != null && latestLon != null) {
            val mgrs = MgrsConverter.toMgrs(latestLat, latestLon)
            locationText.text = "Location: $mgrs"
        }

        val list = aggregate.values.toMutableList()
        when (sortSpinner.selectedItemPosition) {
            0 -> list.sortByDescending { it.rssi }
            1 -> list.sortByDescending { it.lastSeen }
            2 -> list.sortByDescending { it.count }
        }
        adapter.submit(list)
    }

    private fun shouldInclude(
        record: CsvRecord,
        showWifi: Boolean,
        showBt: Boolean,
        showBle: Boolean,
        showCell: Boolean,
        minRssi: Int,
        maxRssi: Int
    ): Boolean {
        if (record.rssi < minRssi) return false
        if (record.rssi > maxRssi) return false
        return when (record.type) {
            "WIFI" -> showWifi
            "BT" -> showBt
            "BLE" -> showBle
            "CELL" -> showCell
            else -> true
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        return CsvUtils.parseCsvLine(line)
    }

    private fun writeKmlFromRecords(uri: Uri?, records: List<CsvRecord>) {
        if (uri == null) return
        val body = StringBuilder()
        body.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        body.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        body.append("  <Document>\n")
        body.append("    <name>PBR</name>\n")
        for (r in records) {
            if (r.latitude == null || r.longitude == null) continue
            val safeName = escapeXml("${r.type}: ${r.name} (${r.rssi} dBm)")
            val desc = escapeXml("${r.timestamp} | ID: ${r.id}")
            body.append("    <Placemark>\n")
            body.append("      <name>$safeName</name>\n")
            body.append("      <description>$desc</description>\n")
            body.append("      <Point><coordinates>${r.longitude},${r.latitude},0</coordinates></Point>\n")
            body.append("    </Placemark>\n")
        }
        body.append("  </Document>\n")
        body.append("</kml>\n")
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return)
            file.parentFile?.mkdirs()
            file.writeText(body.toString())
        } else {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(body.toString().toByteArray())
            }
        }
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun parseInstant(value: String): Long {
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun registerAppReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun getWhitelist(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(PREF_WHITELIST, emptySet()) ?: emptySet()
    }

    private fun addToWhitelist(key: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PREF_WHITELIST, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(key)
        prefs.edit().putStringSet(PREF_WHITELIST, current).apply()
    }

    private fun removeWhitelistedFromRunFiles(key: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val csvPath = prefs.getString(PREF_LAST_CSV, null)
        val kmlPath = prefs.getString(PREF_LAST_KML, null)
        val type = key.substringBefore("|")
        val id = key.substringAfter("|")

        val csvUri = if (csvPath != null) {
            if (csvPath.startsWith("content://") || csvPath.startsWith("file:")) Uri.parse(csvPath) else Uri.fromFile(File(csvPath))
        } else null
        val kmlUri = if (kmlPath != null) {
            if (kmlPath.startsWith("content://") || kmlPath.startsWith("file:")) Uri.parse(kmlPath) else Uri.fromFile(File(kmlPath))
        } else null

        if (csvUri != null) {
            val records = CsvUtils.readCsvRecords(this, csvUri, 100000)
            val filtered = records.filterNot { it.type == type && it.id == id }
            writeCsvFromRecords(csvUri, filtered)
        }

        if (kmlUri != null) {
            val records = if (csvUri != null) CsvUtils.readCsvRecords(this, csvUri, 100000) else emptyList()
            writeKmlFromRecords(kmlUri, records)
        }
    }

    private fun showDeviceDialog(item: DeviceItem) {
        val mgrs = if (item.lastLat != null && item.lastLon != null) {
            MgrsConverter.toMgrs(item.lastLat, item.lastLon)
        } else {
            "Unknown"
        }
        val message = buildString {
            appendLine("SSID/Name: ${item.name}")
            appendLine("ID: ${item.id}")
            appendLine("RSSI: ${item.rssi}")
            appendLine("First seen: ${item.firstSeenText}")
            appendLine("Last seen: ${item.lastSeenText}")
            appendLine("Last location: $mgrs")
            appendLine("Times seen: ${item.count}")
        }
        AlertDialog.Builder(this)
            .setTitle("${item.type} Details")
            .setMessage(message.trim())
            .setPositiveButton("Whitelist") { _, _ ->
                val key = "${item.type}|${item.id}"
                addToWhitelist(key)
                removeWhitelistedFromRunFiles(key)
                refreshList()
                alertText.text = "Whitelisted: ${item.name}"
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun ensurePinSetup() {
        val securePrefs = getSecurePrefs()
        if (securePrefs.getString(PREF_PIN, null) != null) return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4-6 digits"
        }
        AlertDialog.Builder(this)
            .setTitle("Set PIN")
            .setMessage("Create a 4-6 digit PIN for self-destruct.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length in 4..6 && pin.all { it.isDigit() }) {
                    securePrefs.edit().putString(PREF_PIN, pin).apply()
                } else {
                    alertText.text = "PIN must be 4-6 digits."
                    ensurePinSetup()
                }
            }
            .show()
    }

    private fun promptPinAndRun(action: () -> Unit) {
        val securePrefs = getSecurePrefs()
        val saved = securePrefs.getString(PREF_PIN, null) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                if (input.text.toString().trim() == saved) {
                    action()
                } else {
                    alertText.text = "Incorrect PIN."
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getSecurePrefs(): android.content.SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val securePrefs = EncryptedSharedPreferences.create(
            "wifi_bt_secure_prefs",
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // One-time migration if a plaintext PIN exists.
        val legacy = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (securePrefs.getString(PREF_PIN, null) == null) {
            val legacyPin = legacy.getString(PREF_PIN, null)
            if (!legacyPin.isNullOrBlank()) {
                securePrefs.edit().putString(PREF_PIN, legacyPin).apply()
                legacy.edit().remove(PREF_PIN).apply()
            }
        }
        return securePrefs
    }

    private fun clearData() {
        stopScanning()
        deleteDownloadsByPrefix(listOf("run_", "scan_log"))
        alertText.text = "Data cleared."
        adapter.submit(emptyList())
        countsText.text = "WiFi: 0   BT: 0   BLE: 0   CELL: 0"
        locationText.text = "Location: --"
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear Data")
            .setMessage("Are you sure you want to clear all run data?")
            .setPositiveButton("Yes") { _, _ ->
                clearData()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun selfDestruct() {
        clearData()
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save_run -> {
                saveRunAndStop()
                true
            }
            R.id.action_whitelist -> {
                startActivity(Intent(this, WhitelistActivity::class.java))
                true
            }
            R.id.action_distance_alerts -> {
                startActivity(Intent(this, DistanceAlertsActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_clear -> {
                confirmClearData()
                true
            }
            R.id.action_self_destruct -> {
                promptPinAndRun { selfDestruct() }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveRunAndStop() {
        if (isScanning) {
            stopScanning()
        }
        val csv = csvUri()
        val kml = kmlUri()
        if (csv == null && kml == null) {
            alertText.text = "No run data to save."
            return
        }

        val records = CsvUtils.readCsvRecords(this, csv, 200000)
        if (records.isEmpty()) {
            alertText.text = "No run data to save."
            return
        }

        val agg = buildDeviceAgg(records)
        val nowStamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd-uuuu-HH-mm-ss"))

        saveDistanceBin(records, agg, 2.0, 4.0, "co_2", nowStamp)
        saveDistanceBin(records, agg, 4.0, 6.0, "co_4", nowStamp)
        saveDistanceBin(records, agg, 6.0, 8.0, "co_6", nowStamp)
        saveDistanceBin(records, agg, 8.0, 10.0, "co_8", nowStamp)
        saveDistanceBin(records, agg, 10.0, null, "co_10plus", nowStamp)
        saveDwellFiles(nowStamp)

        alertText.text = "Run saved to Downloads."
    }

    private fun saveDwellFiles(stamp: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dwellCsv = prefs.getString(PREF_LAST_DWELL_CSV, null) ?: return
        val uri = if (dwellCsv.startsWith("content://") || dwellCsv.startsWith("file:")) {
            Uri.parse(dwellCsv)
        } else {
            Uri.fromFile(File(dwellCsv))
        }
        val records = CsvUtils.readCsvRecords(this, uri, 200000)
        if (records.isEmpty()) return

        val outCsv = createDownloadFile("run_${stamp}_dwell.csv", "text/csv")
        val outKml = createDownloadFile("run_${stamp}_dwell.kml", "application/vnd.google-earth.kml+xml")
        if (outCsv != null) writeCsvFromRecords(outCsv, records)
        if (outKml != null) writeKmlFromRecords(outKml, records)
    }

    private fun toggleDwellMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = !prefs.getBoolean(PREF_DWELL_MODE, false)
        prefs.edit().putBoolean(PREF_DWELL_MODE, enabled).apply()
        dwellButton.text = if (enabled) "Dwell Mode: On" else "Dwell Mode: Off"
        if (isScanning) {
            val intent = Intent(this, ScanService::class.java).apply {
                action = ScanService.ACTION_SET_DWELL
                putExtra(ScanService.EXTRA_DWELL, enabled)
            }
            startService(intent)
        }
    }

    private fun setScanMode(mode: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = when (mode) {
            SCAN_MODE_HIGH -> if (getScanMode() == SCAN_MODE_HIGH) SCAN_MODE_BALANCED else SCAN_MODE_HIGH
            SCAN_MODE_LOW -> if (getScanMode() == SCAN_MODE_LOW) SCAN_MODE_BALANCED else SCAN_MODE_LOW
            else -> SCAN_MODE_BALANCED
        }
        prefs.edit().putString(PREF_SCAN_MODE, next).apply()
        updateScanModeButtons()
        if (isScanning) {
            val intent = Intent(this, ScanService::class.java).apply {
                action = ScanService.ACTION_SET_MODE
                putExtra(ScanService.EXTRA_SCAN_MODE, next)
            }
            startService(intent)
        }
    }

    private fun updateScanModeButtons() {
        val mode = getScanMode()
        highIntensityButton.text = if (mode == SCAN_MODE_HIGH) "High Intensity: On" else "High Intensity: Off"
        lowPowerButton.text = if (mode == SCAN_MODE_LOW) "Low Power: On" else "Low Power: Off"
    }

    private fun getScanMode(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_SCAN_MODE, SCAN_MODE_BALANCED) ?: SCAN_MODE_BALANCED
    }

    private fun isDwellMode(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DWELL_MODE, false)
    }

    private fun saveDistanceBin(
        records: List<CsvRecord>,
        agg: Map<String, DeviceAgg>,
        min: Double,
        maxExclusive: Double?,
        suffix: String,
        stamp: String
    ) {
        val keys = agg.filterValues { device ->
            if (device.count < 2) return@filterValues false
            if (maxExclusive == null) {
                device.maxDistanceMiles >= min
            } else {
                device.maxDistanceMiles >= min && device.maxDistanceMiles < maxExclusive
            }
        }.keys
        if (keys.isEmpty()) return

        val filtered = records.filter { keys.contains("${it.type}|${it.id}") }
        if (filtered.isEmpty()) return

        val csvUri = createDownloadFile("run_${stamp}_${suffix}.csv", "text/csv")
        val kmlUri = createDownloadFile("run_${stamp}_${suffix}.kml", "application/vnd.google-earth.kml+xml")
        if (csvUri != null) writeCsvFromRecords(csvUri, filtered)
        if (kmlUri != null) writeKmlFromRecords(kmlUri, filtered)
    }

    private fun buildDeviceAgg(records: List<CsvRecord>): Map<String, DeviceAgg> {
        val map = LinkedHashMap<String, DeviceAgg>()
        for (r in records) {
            val key = "${r.type}|${r.id}"
            val device = map.getOrPut(key) { DeviceAgg(r.type, r.id) }
            device.count += 1
            val ts = parseInstant(r.timestamp)
            if (ts >= device.lastSeen) {
                device.lastSeen = ts
                device.name = r.name
                device.rssi = r.rssi
            }
            if (r.latitude != null && r.longitude != null) {
                device.addPoint(r.latitude, r.longitude)
            }
        }
        return map
    }

    private fun createDownloadFile(displayName: String, mimeType: String): Uri? {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
        }
        return contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun writeCsvFromRecords(uri: Uri, records: List<CsvRecord>) {
        val header = "timestamp,latitude,longitude,type,name,id,rssi\n"
        val body = StringBuilder()
        body.append(header)
        for (r in records) {
            body.append(r.timestamp)
            body.append(',')
            body.append(r.latitude ?: "")
            body.append(',')
            body.append(r.longitude ?: "")
            body.append(',')
            body.append(escapeCsv(r.type))
            body.append(',')
            body.append(escapeCsv(r.name))
            body.append(',')
            body.append(escapeCsv(r.id))
            body.append(',')
            body.append(r.rssi)
            body.append('\n')
        }
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return)
            file.parentFile?.mkdirs()
            file.writeText(body.toString())
        } else {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(body.toString().toByteArray())
            }
        }
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n")
        return if (needsQuotes) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    private fun deleteDownloadsByPrefix(prefixes: List<String>) {
        val resolver = contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Downloads._ID,
            android.provider.MediaStore.Downloads.DISPLAY_NAME
        )
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: ""
                if (prefixes.any { name.startsWith(it) }) {
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    resolver.delete(uri, null, null)
                }
            }
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        ) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private class SimpleItemSelectedListener(
        private val onSelected: () -> Unit
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: android.view.View?,
            position: Int,
            id: Long
        ) {
            onSelected()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}

private data class DeviceItem(
    val type: String,
    val name: String,
    val id: String,
    val rssi: Int,
    val count: Int,
    val firstSeen: Long,
    val firstSeenText: String,
    val lastSeen: Long,
    val lastSeenText: String,
    val lastLat: Double?,
    val lastLon: Double?
)

private class DeviceAdapter(
    private val onClick: (DeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceViewHolder>() {
    private val items = ArrayList<DeviceItem>()

    fun submit(newItems: List<DeviceItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DeviceViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size
}

private class DeviceViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.title)
    private val subtitle: TextView = view.findViewById(R.id.subtitle)
    private val meta: TextView = view.findViewById(R.id.meta)

    fun bind(item: DeviceItem, onClick: (DeviceItem) -> Unit) {
        title.text = "${item.type}: ${item.name}"
        subtitle.text = "ID: ${item.id}"
        meta.text = "RSSI: ${item.rssi}   Seen: ${item.count}   Last: ${item.lastSeenText}"
        itemView.setOnClickListener { onClick(item) }
    }
}

private const val PREFS_NAME = "wifi_bt_prefs"
private const val PREF_PIN = "self_destruct_pin"
private const val PREF_LAST_CSV = "last_run_csv"
private const val PREF_LAST_KML = "last_run_kml"
private const val PREF_WHITELIST = "whitelist_keys"
private const val PREF_DWELL_MODE = "dwell_mode"
private const val PREF_LAST_DWELL_CSV = "last_dwell_csv"
private const val PREF_RSSI_MIN = "rssi_min"
private const val PREF_RSSI_MAX = "rssi_max"
private const val PREF_SCAN_MODE = "scan_mode"
private const val SCAN_MODE_BALANCED = "balanced"
private const val SCAN_MODE_HIGH = "high"
private const val SCAN_MODE_LOW = "low"
