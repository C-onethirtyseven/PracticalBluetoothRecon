package com.example.wifibtlogger

import kotlin.math.*

object MgrsConverter {
    private val LAT_BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val EASTING_SETS = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private val NORTHING_SET = "ABCDEFGHJKLMNPQRSTUV"

    fun toMgrs(lat: Double, lon: Double): String {
        val zone = ((lon + 180.0) / 6.0).toInt() + 1
        val bandIndex = (((lat + 80.0) / 8.0).toInt()).coerceIn(0, LAT_BANDS.length - 1)
        val band = LAT_BANDS[bandIndex]

        val utm = latLonToUtm(lat, lon, zone)
        val easting = utm.first
        val northing = utm.second

        val set = zone % 6
        val setIndex = if (set == 0) 5 else set - 1
        val eastingLetters = EASTING_SETS[setIndex % 3]
        val e100k = ((easting / 100000.0).toInt() - 1).coerceIn(0, 7)
        val eLetter = eastingLetters[e100k]

        val n100k = (northing / 100000.0).toInt() % 20
        val nOffset = if (setIndex % 2 == 0) 0 else 5
        val nLetter = NORTHING_SET[(n100k + nOffset) % 20]

        val eRemainder = (easting % 100000.0).toInt().coerceAtLeast(0)
        val nRemainder = (northing % 100000.0).toInt().coerceAtLeast(0)

        val eStr = eRemainder.toString().padStart(5, '0')
        val nStr = nRemainder.toString().padStart(5, '0')

        return String.format("%d%c %c%c %s %s", zone, band, eLetter, nLetter, eStr, nStr)
    }

    private fun latLonToUtm(lat: Double, lon: Double, zone: Int): Pair<Double, Double> {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val k0 = 0.9996

        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val cm = Math.toRadians(-183.0 + zone * 6.0)

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val tanLat = tan(latRad)

        val n = a / sqrt(1 - e2 * sinLat * sinLat)
        val t = tanLat * tanLat
        val c = ep2 * cosLat * cosLat
        val aTerm = cosLat * (lonRad - cm)

        val m = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * latRad
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * latRad)
            + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * latRad)
            - (35 * e2 * e2 * e2 / 3072) * sin(6 * latRad))

        var easting = k0 * n * (aTerm + (1 - t + c) * aTerm.pow(3.0) / 6.0 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aTerm.pow(5.0) / 120.0) + 500000.0

        var northing = k0 * (m + n * tanLat * (aTerm * aTerm / 2.0 +
            (5 - t + 9 * c + 4 * c * c) * aTerm.pow(4.0) / 24.0 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aTerm.pow(6.0) / 720.0))

        if (lat < 0) {
            northing += 10000000.0
        }

        if (easting < 0) easting = 0.0
        if (northing < 0) northing = 0.0

        return Pair(easting, northing)
    }
}
