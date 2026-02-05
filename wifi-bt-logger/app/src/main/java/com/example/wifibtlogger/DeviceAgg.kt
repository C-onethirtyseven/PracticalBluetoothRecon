package com.example.wifibtlogger

class DeviceAgg(
    val type: String,
    val id: String
) {
    var name: String = "Unknown"
    var rssi: Int = 0
    var count: Int = 0
    var lastSeen: Long = 0L
    var maxDistanceMiles: Double = 0.0
    private val points = ArrayList<Pair<Double, Double>>()

    fun addPoint(lat: Double, lon: Double) {
        for (p in points) {
            val dist = haversineMiles(p.first, p.second, lat, lon)
            if (dist > maxDistanceMiles) {
                maxDistanceMiles = dist
            }
        }
        points.add(Pair(lat, lon))
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
}
