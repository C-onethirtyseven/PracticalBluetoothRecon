package com.example.wifibtlogger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ScanService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private lateinit var wifiManager: WifiManager
    private var btAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private lateinit var telephonyManager: TelephonyManager
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val records = CopyOnWriteArrayList<ScanRecord>()
    private val deviceSeen = ConcurrentHashMap<String, DeviceSeen>()

    private var wifiCount = 0
    private var btCount = 0
    private var bleCount = 0
    private var cellCount = 0
    private var lastWifiReadMs = 0L
    private var lastWifiScanMs = 0L
    private var lastNotifyMs = 0L
    private var lastLocationMs = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var dwellMode = false

    private val cellExecutor = Executors.newSingleThreadExecutor()

    private val locationLoop = object : Runnable {
        override fun run() {
            if (!isScanning) return
            refreshLocation()
            handler.postDelayed(this, 10000L)
        }
    }

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT
    private val runFormatter = DateTimeFormatter.ofPattern("MM-dd-uuuu-HH-mm-ss")

    private var currentRunStamp = ""
    private var currentCsvUri: Uri? = null
    private var currentKmlUri: Uri? = null
    private var lastMgrs: String = ""
    private var csvHeaderWritten = false
    private var csvPending = false
    private var kmlPending = false

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                handleWifiResults()
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
            if (device != null) {
                val name = device.name ?: "Unknown"
                if (logRecord("BT", name, device.address, rssi)) {
                    btCount += 1
                }
                sendUpdate(lastLat, lastLon)
                notifyStatus()
            }
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            val rssi = result.rssi
            if (logRecord("BLE", name, device.address, rssi)) {
                bleCount += 1
            }
            sendUpdate(lastLat, lastLon)
            notifyStatus()
        }
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            if (!isScanning) return
            startWifiScan()
            startBluetoothDiscovery()
            readWifiSnapshot()
            scanCellInfo()
            handler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        btAdapter = BluetoothAdapter.getDefaultAdapter()
        bleScanner = btAdapter?.bluetoothLeScanner
        telephonyManager = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        }
        registerAppReceiver(wifiScanReceiver, filter)

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        registerAppReceiver(btReceiver, btFilter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
        unregisterReceiver(btReceiver)
        stopScanning()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cellExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                dwellMode = intent.getBooleanExtra(EXTRA_DWELL, dwellMode)
                startScanning()
            }
            ACTION_STOP -> stopSelf()
            ACTION_SET_DWELL -> {
                dwellMode = intent.getBooleanExtra(EXTRA_DWELL, false)
                if (dwellMode) {
                    resetDwellState()
                }
            }
        }
        return START_STICKY
    }

    private fun startScanning() {
        if (isScanning) return
        isScanning = true
        resetRunState()
        if (dwellMode) {
            resetDwellState()
        }
        startForeground(NOTIFICATION_ID, buildNotification("Scanning"))

        startWifiScan()
        startBluetoothDiscovery()
        startBleScan()
        readWifiSnapshot()
        scanCellInfo()
        refreshLocation()
        handler.postDelayed(locationLoop, 10000L)
        handler.postDelayed(scanLoop, 15000L)
        notifyStatus()
    }

    private fun stopScanning() {
        isScanning = false
        handler.removeCallbacks(scanLoop)
        handler.removeCallbacks(locationLoop)
        stopBleScan()
        btAdapter?.cancelDiscovery()
    }

    private fun resetRunState() {
        wifiCount = 0
        btCount = 0
        bleCount = 0
        cellCount = 0
        records.clear()
        deviceSeen.clear()
        csvHeaderWritten = false
        csvPending = false
        kmlPending = false

        currentRunStamp = runFormatter.format(LocalDateTime.now())
        currentCsvUri = createDownloadFile("run_${currentRunStamp}.csv", "text/csv").also {
            csvPending = it != null
        }
        currentKmlUri = createDownloadFile(
            "run_${currentRunStamp}.kml",
            "application/vnd.google-earth.kml+xml"
        ).also {
            kmlPending = it != null
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_LAST_CSV, currentCsvUri?.toString())
            .putString(PREF_LAST_KML, currentKmlUri?.toString())
            .apply()
    }

    private fun resetDwellState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stamp = runFormatter.format(LocalDateTime.now())
        val dwellCsv = createDownloadFile("dwell_${stamp}.csv", "text/csv")
        prefs.edit().putString(PREF_LAST_DWELL_CSV, dwellCsv?.toString()).apply()
        if (dwellCsv != null) {
            val header = "timestamp,latitude,longitude,type,name,id,rssi\n"
            contentResolver.openOutputStream(dwellCsv, "w")?.use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write(header)
                }
            }
        }
    }

    private fun registerAppReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun createDownloadFile(displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) return uri

        // Fallback to app external files if MediaStore insert fails.
        val dir = getExternalFilesDir(null) ?: filesDir
        val fallback = File(dir, displayName)
        return Uri.fromFile(fallback)
    }

    private fun markDownloadComplete(uri: Uri?) {
        if (uri == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiScan() {
        if (!wifiManager.isWifiEnabled) {
            Log.i(TAG, "WiFi disabled")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastWifiScanMs < 30000L) {
            return
        }
        lastWifiScanMs = now
        try {
            val started = wifiManager.startScan()
            Log.i(TAG, "WiFi startScan started=$started")
        } catch (_: SecurityException) {
            Log.i(TAG, "WiFi startScan blocked by permissions")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleWifiResults() {
        val results = wifiManager.scanResults
        handleWifiResults(results)
    }

    private fun handleWifiResults(results: List<android.net.wifi.ScanResult>) {
        if (results.isEmpty()) {
            Log.i(TAG, "WiFi results empty")
            return
        }
        for (result in results) {
            val ssid = if (result.SSID.isNullOrBlank()) "<hidden>" else result.SSID
            val bssid = result.BSSID ?: ""
            val rssi = result.level
            if (logRecord("WIFI", ssid, bssid, rssi)) {
                wifiCount += 1
            }
        }
        sendUpdate(lastLat, lastLon)
        notifyStatus()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        if (btAdapter == null) return
        if (btAdapter?.isDiscovering == true) {
            btAdapter?.cancelDiscovery()
        }
        val enabled = btAdapter?.isEnabled == true
        val started = if (enabled) btAdapter?.startDiscovery() ?: false else false
        Log.i(TAG, "BT discovery started=$started enabled=$enabled")
        sendUpdate(lastLat, lastLon)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner?.startScan(null, settings, bleCallback)
        Log.i(TAG, "BLE scan started")
        sendUpdate(lastLat, lastLon)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        bleScanner?.stopScan(bleCallback)
    }

    private fun logRecord(type: String, name: String, mac: String, rssi: Int): Boolean {
        if (isWhitelisted(type, mac)) return false
        val lat = lastLat
        val lon = lastLon
        val key = "$type|$mac"
        val existing = deviceSeen[key]
        if (!dwellMode && existing != null) {
            if (lat == null || lon == null) {
                return false
            }
            if (existing.lat.isNaN() || existing.lon.isNaN()) {
                deviceSeen[key] = DeviceSeen(lat, lon, existing.count)
                return false
            }
            val distanceMeters = haversineMiles(existing.lat, existing.lon, lat, lon) * 1609.344
            if (distanceMeters < 100.0) {
                return false
            }
        }

        val timestamp = isoFormatter.format(Instant.now())
        val record = ScanRecord(timestamp, lat, lon, type, name, mac, rssi)
        records.add(record)
        appendCsv(record)
        appendDwellCsv(record)
        writeKml()
        if (existing != null && lat != null && lon != null &&
            !existing.lat.isNaN() && !existing.lon.isNaN()
        ) {
            checkDistanceAlert(type, name, mac, existing.lat, existing.lon, lat, lon)
        }
        val newCount = (existing?.count ?: 0) + 1
        val storedLat = lat ?: Double.NaN
        val storedLon = lon ?: Double.NaN
        deviceSeen[key] = DeviceSeen(storedLat, storedLon, newCount)
        sendUpdate(lat, lon)
        return true
    }

    private fun sendUpdate(lat: Double?, lon: Double?) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_WIFI, wifiCount)
            putExtra(EXTRA_BT, btCount)
            putExtra(EXTRA_BLE, bleCount)
            putExtra(EXTRA_CELL, cellCount)
            putExtra(EXTRA_LAT, lat ?: Double.NaN)
            putExtra(EXTRA_LON, lon ?: Double.NaN)
            putExtra(EXTRA_MGRS, lastMgrs)
        }
        sendBroadcast(intent)
    }

    private fun sendAlert(message: String) {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_ALERT, message)
        }
        sendBroadcast(intent)
    }

    private fun notifyStatus() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < 5000L) return
        lastNotifyMs = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("Scanning"))
    }

    private fun buildNotification(status: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi BT Logger",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScanService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi BT Logger")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun csvUri(): Uri? {
        return currentCsvUri
    }

    private fun kmlUri(): Uri? {
        return currentKmlUri
    }

    private fun appendCsv(record: ScanRecord) {
        var uri = csvUri() ?: return
        val header = "timestamp,latitude,longitude,type,name,id,rssi\n"
        if (!csvHeaderWritten) {
            try {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    OutputStreamWriter(output).use { writer ->
                        writer.write(header)
                    }
                }
                csvHeaderWritten = true
                if (csvPending) {
                    markDownloadComplete(uri)
                    csvPending = false
                }
            } catch (_: Exception) {
                uri = recreateCsvUri()
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    OutputStreamWriter(output).use { writer ->
                        writer.write(header)
                    }
                }
                csvHeaderWritten = true
                if (csvPending) {
                    markDownloadComplete(uri)
                    csvPending = false
                }
            }
        }
        val line = buildString {
            append(record.timestamp)
            append(',')
            append(record.latitude ?: "")
            append(',')
            append(record.longitude ?: "")
            append(',')
            append(escapeCsv(record.type))
            append(',')
            append(escapeCsv(record.name))
            append(',')
            append(escapeCsv(record.mac))
            append(',')
            append(record.rssi)
            append('\n')
        }
        try {
            contentResolver.openOutputStream(uri, "wa")?.use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write(line)
                }
            }
        } catch (_: Exception) {
            uri = recreateCsvUri()
            contentResolver.openOutputStream(uri, "wa")?.use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write(line)
                }
            }
        }
    }

    private fun appendDwellCsv(record: ScanRecord) {
        if (!dwellMode) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dwellValue = prefs.getString(PREF_LAST_DWELL_CSV, null) ?: return
        val uri = if (dwellValue.startsWith("content://")) Uri.parse(dwellValue) else Uri.fromFile(File(dwellValue))
        val header = "timestamp,latitude,longitude,type,name,id,rssi\n"
        try {
            contentResolver.openOutputStream(uri, "wa")?.use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write(lineForRecord(record))
                }
            }
        } catch (_: Exception) {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write(header)
                    writer.write(lineForRecord(record))
                }
            }
        }
    }

    private fun lineForRecord(record: ScanRecord): String {
        return buildString {
            append(record.timestamp)
            append(',')
            append(record.latitude ?: "")
            append(',')
            append(record.longitude ?: "")
            append(',')
            append(escapeCsv(record.type))
            append(',')
            append(escapeCsv(record.name))
            append(',')
            append(escapeCsv(record.mac))
            append(',')
            append(record.rssi)
            append('\n')
        }
    }


    private fun writeKml() {
        var uri = kmlUri() ?: return
        val body = StringBuilder()
        body.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        body.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        body.append("  <Document>\n")
        body.append("    <name>WiFi BT Logger</name>\n")

        for (r in records) {
            if (r.latitude == null || r.longitude == null) continue
            val safeName = escapeXml("${r.type}: ${r.name} (${r.rssi} dBm)")
            val desc = escapeXml("${r.timestamp} | ID: ${r.mac}")
            body.append("    <Placemark>\n")
            body.append("      <name>$safeName</name>\n")
            body.append("      <description>$desc</description>\n")
            body.append("      <Point><coordinates>${r.longitude},${r.latitude},0</coordinates></Point>\n")
            body.append("    </Placemark>\n")
        }

        body.append("  </Document>\n")
        body.append("</kml>\n")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(body.toString().toByteArray())
            }
            if (kmlPending) {
                markDownloadComplete(uri)
                kmlPending = false
            }
        } catch (_: Exception) {
            uri = recreateKmlUri()
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(body.toString().toByteArray())
            }
            if (kmlPending) {
                markDownloadComplete(uri)
                kmlPending = false
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

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    @SuppressLint("MissingPermission")
    private fun scanCellInfo() {
        if (!hasPermission(android.Manifest.permission.READ_PHONE_STATE) &&
            !hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            Log.i(TAG, "Cell scan blocked by permissions")
            return
        }
        try {
            telephonyManager.requestCellInfoUpdate(cellExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: List<CellInfo>) {
                    Log.i(TAG, "Cell infos count=${cellInfo.size}")
                    for (info in cellInfo) {
                        val record = buildCellRecord(info) ?: continue
                        if (logRecord("CELL", record.name, record.cellId, record.rssi)) {
                            cellCount += 1
                        }
                    }
                    sendUpdate(lastLat, lastLon)
                    notifyStatus()
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    Log.i(TAG, "Cell info error=$errorCode detail=${detail?.message}")
                }
            })
        } catch (_: SecurityException) {
            Log.i(TAG, "Cell scan blocked by permissions")
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshLocation() {
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            Log.i(TAG, "Location update blocked by permissions")
            return
        }
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    lastLat = loc.latitude
                    lastLon = loc.longitude
                    lastLocationMs = System.currentTimeMillis()
                    lastMgrs = MgrsConverter.toMgrs(loc.latitude, loc.longitude)
                    sendUpdate(lastLat, lastLon)
                }
            }
    }

    private fun checkDistanceAlert(
        type: String,
        name: String,
        id: String,
        prevLat: Double,
        prevLon: Double,
        lat: Double,
        lon: Double
    ) {
        val distanceMiles = haversineMiles(prevLat, prevLon, lat, lon)
        if (distanceMiles >= 10.0) {
            val msg = String.format(
                "GPS moved %.1f mi and device seen again: %s %s",
                distanceMiles,
                type,
                name
            )
            sendAlert(msg)
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

    private fun buildCellRecord(info: CellInfo): CellRecord? {
        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val name = "LTE mccmnc=${id.mccString}${id.mncString} tac=${id.tac} pci=${id.pci} cid=${id.ci}"
                CellRecord(name, "lte:${id.ci}:${id.pci}:${id.tac}", info.cellSignalStrength.dbm)
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as? CellIdentityNr ?: return null
                val name = "NR mccmnc=${id.mccString}${id.mncString} tac=${id.tac} pci=${id.pci} nci=${id.nci}"
                CellRecord(name, "nr:${id.nci}:${id.pci}:${id.tac}", info.cellSignalStrength.dbm)
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val name = "WCDMA mccmnc=${id.mccString}${id.mncString} lac=${id.lac} cid=${id.cid} psc=${id.psc}"
                CellRecord(name, "wcdma:${id.cid}:${id.lac}:${id.psc}", info.cellSignalStrength.dbm)
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val name = "GSM mccmnc=${id.mccString}${id.mncString} lac=${id.lac} cid=${id.cid} arfcn=${id.arfcn}"
                CellRecord(name, "gsm:${id.cid}:${id.lac}:${id.arfcn}", info.cellSignalStrength.dbm)
            }
            is CellInfoCdma -> {
                val id = info.cellIdentity
                val name = "CDMA sid=${id.systemId} nid=${id.networkId} bid=${id.basestationId}"
                CellRecord(name, "cdma:${id.basestationId}:${id.networkId}:${id.systemId}", info.cellSignalStrength.dbm)
            }
            else -> null
        }
    }

    private fun readWifiSnapshot() {
        val now = System.currentTimeMillis()
        if (now - lastWifiReadMs < 5000L) return
        lastWifiReadMs = now
        try {
            val results = wifiManager.scanResults
            Log.i(TAG, "WiFi snapshot count=${results.size}")
            handleWifiResults(results)
        } catch (_: SecurityException) {
            Log.i(TAG, "WiFi snapshot blocked by permissions")
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isWhitelisted(type: String, id: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(PREF_WHITELIST, emptySet()) ?: emptySet()
        return set.contains("$type|$id")
    }

    private fun recreateCsvUri(): Uri {
        currentCsvUri = createDownloadFile("run_${currentRunStamp}.csv", "text/csv").also {
            csvPending = it != null
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LAST_CSV, currentCsvUri?.toString()).apply()
        return currentCsvUri ?: Uri.EMPTY
    }

    private fun recreateKmlUri(): Uri {
        currentKmlUri = createDownloadFile(
            "run_${currentRunStamp}.kml",
            "application/vnd.google-earth.kml+xml"
        ).also {
            kmlPending = it != null
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LAST_KML, currentKmlUri?.toString()).apply()
        return currentKmlUri ?: Uri.EMPTY
    }

    companion object {
        const val ACTION_START = "com.example.wifibtlogger.START"
        const val ACTION_STOP = "com.example.wifibtlogger.STOP"
        const val ACTION_SET_DWELL = "com.example.wifibtlogger.DWELL"
        const val ACTION_STATUS = "com.example.wifibtlogger.STATUS"

        const val EXTRA_WIFI = "wifi"
        const val EXTRA_BT = "bt"
        const val EXTRA_BLE = "ble"
        const val EXTRA_CELL = "cell"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_ALERT = "alert"
        const val EXTRA_MGRS = "mgrs"
        const val EXTRA_DWELL = "dwell"

        private const val CHANNEL_ID = "wifi_bt_logger"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "WifiBtLogger"

        private const val PREFS_NAME = "wifi_bt_prefs"
        private const val PREF_LAST_CSV = "last_run_csv"
        private const val PREF_LAST_KML = "last_run_kml"
        private const val PREF_WHITELIST = "whitelist_keys"
        private const val PREF_LAST_DWELL_CSV = "last_dwell_csv"
    }
}

private data class ScanRecord(
    val timestamp: String,
    val latitude: Double?,
    val longitude: Double?,
    val type: String,
    val name: String,
    val mac: String,
    val rssi: Int
)

private data class CellRecord(
    val name: String,
    val cellId: String,
    val rssi: Int
)

private data class DeviceSeen(
    val lat: Double,
    val lon: Double,
    val count: Int
)
