package com.example.wifibtlogger

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader

data class CsvRecord(
    val timestamp: String,
    val latitude: Double?,
    val longitude: Double?,
    val type: String,
    val name: String,
    val id: String,
    val rssi: Int
)

object CsvUtils {
    fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i += 1
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                out.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i += 1
        }
        out.add(sb.toString())
        return out
    }

    fun readCsvRecords(context: Context, uri: Uri?, limit: Int): List<CsvRecord> {
        if (uri == null) return emptyList()
        val rows = ArrayList<CsvRecord>()
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return emptyList())
            if (!file.exists()) return emptyList()
            BufferedReader(FileReader(file)).use { reader ->
                readLines(reader, rows)
            }
        } else {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input)).use { reader ->
                        readLines(reader, rows)
                    }
                }
            } catch (_: Exception) {
                return emptyList()
            }
        }
        return if (rows.size > limit) rows.takeLast(limit) else rows
    }

    private fun readLines(reader: BufferedReader, rows: MutableList<CsvRecord>) {
        var line = reader.readLine()
        var isHeader = true
        while (line != null) {
            if (!isHeader) {
                val cols = parseCsvLine(line)
                if (cols.size >= 7) {
                    val record = CsvRecord(
                        timestamp = cols[0],
                        latitude = cols[1].toDoubleOrNull(),
                        longitude = cols[2].toDoubleOrNull(),
                        type = cols[3],
                        name = cols[4],
                        id = cols[5],
                        rssi = cols[6].toIntOrNull() ?: 0
                    )
                    rows.add(record)
                }
            }
            isHeader = false
            line = reader.readLine()
        }
    }
}
