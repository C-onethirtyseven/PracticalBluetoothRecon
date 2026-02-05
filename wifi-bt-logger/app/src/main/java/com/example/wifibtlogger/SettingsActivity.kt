package com.example.wifibtlogger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import java.util.Calendar
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var slider: RangeSlider
    private lateinit var rssiValue: TextView
    private lateinit var startMonth: Spinner
    private lateinit var startDay: Spinner
    private lateinit var startYear: Spinner
    private lateinit var stopMonth: Spinner
    private lateinit var stopDay: Spinner
    private lateinit var stopYear: Spinner
    private lateinit var startTime: TimePicker
    private lateinit var stopTime: TimePicker
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Settings"

        slider = findViewById(R.id.rssiSlider)
        rssiValue = findViewById(R.id.rssiValue)
        startMonth = findViewById(R.id.startMonth)
        startDay = findViewById(R.id.startDay)
        startYear = findViewById(R.id.startYear)
        stopMonth = findViewById(R.id.stopMonth)
        stopDay = findViewById(R.id.stopDay)
        stopYear = findViewById(R.id.stopYear)
        startTime = findViewById(R.id.startTime)
        stopTime = findViewById(R.id.stopTime)
        saveButton = findViewById(R.id.saveSettings)

        startTime.setIs24HourView(true)
        stopTime.setIs24HourView(true)

        setupRssiSlider()
        setupDateSpinners()
        loadSavedValues()

        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun setupRssiSlider() {
        slider.valueFrom = -180f
        slider.valueTo = 0f
        slider.stepSize = 5f
        slider.addOnChangeListener { _, _, _ ->
            updateRssiLabel()
        }
    }

    private fun updateRssiLabel() {
        val values = slider.values.sorted()
        val min = values[0].toInt()
        val max = values[1].toInt()
        rssiValue.text = "Min: $min dBm   Max: $max dBm"
    }

    private fun setupDateSpinners() {
        val months = (1..12).map { it.toString() }
        val days = (1..31).map { it.toString() }
        val yearStart = Calendar.getInstance().get(Calendar.YEAR)
        val years = (yearStart..(yearStart + 5)).map { it.toString() }

        startMonth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, months).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        startDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, days).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        startYear.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, years).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        stopMonth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, months).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        stopDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, days).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        stopYear.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, years).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun loadSavedValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val min = prefs.getInt(PREF_RSSI_MIN, -150)
        val max = prefs.getInt(PREF_RSSI_MAX, 0)
        slider.values = listOf(min.toFloat(), max.toFloat())
        updateRssiLabel()

        val start = Calendar.getInstance()
        start.timeInMillis = prefs.getLong(PREF_START_AT, start.timeInMillis)
        val stop = Calendar.getInstance()
        stop.timeInMillis = prefs.getLong(PREF_STOP_AT, stop.timeInMillis)

        startMonth.setSelection(start.get(Calendar.MONTH))
        startDay.setSelection(start.get(Calendar.DAY_OF_MONTH) - 1)
        startYear.setSelection(start.get(Calendar.YEAR) - Calendar.getInstance().get(Calendar.YEAR))
        startTime.hour = start.get(Calendar.HOUR_OF_DAY)
        startTime.minute = start.get(Calendar.MINUTE)

        stopMonth.setSelection(stop.get(Calendar.MONTH))
        stopDay.setSelection(stop.get(Calendar.DAY_OF_MONTH) - 1)
        stopYear.setSelection(stop.get(Calendar.YEAR) - Calendar.getInstance().get(Calendar.YEAR))
        stopTime.hour = stop.get(Calendar.HOUR_OF_DAY)
        stopTime.minute = stop.get(Calendar.MINUTE)
    }

    private fun saveSettings() {
        val values = slider.values.sorted()
        val min = values[0].toInt()
        val max = values[1].toInt()

        val startCal = calendarFromInputs(startYear, startMonth, startDay, startTime)
        val stopCal = calendarFromInputs(stopYear, stopMonth, stopDay, stopTime)
        if (stopCal.timeInMillis <= startCal.timeInMillis) {
            stopCal.add(Calendar.DAY_OF_MONTH, 1)
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(PREF_RSSI_MIN, min)
            .putInt(PREF_RSSI_MAX, max)
            .putLong(PREF_START_AT, startCal.timeInMillis)
            .putLong(PREF_STOP_AT, stopCal.timeInMillis)
            .apply()

        scheduleAlarms(startCal.timeInMillis, stopCal.timeInMillis)
        finish()
    }

    private fun calendarFromInputs(yearSpinner: Spinner, monthSpinner: Spinner, daySpinner: Spinner, timePicker: TimePicker): Calendar {
        val year = yearSpinner.selectedItem.toString().toInt()
        val month = monthSpinner.selectedItem.toString().toInt() - 1
        val day = daySpinner.selectedItem.toString().toInt()
        val cal = Calendar.getInstance(Locale.getDefault())
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, timePicker.hour)
        cal.set(Calendar.MINUTE, timePicker.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    private fun scheduleAlarms(startAt: Long, stopAt: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startIntent = Intent(this, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_START
        }
        val stopIntent = Intent(this, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_STOP
        }

        val startPending = PendingIntent.getBroadcast(
            this,
            1001,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getBroadcast(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, startPending)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, stopPending)
    }

    companion object {
        private const val PREFS_NAME = "wifi_bt_prefs"
        private const val PREF_RSSI_MIN = "rssi_min"
        private const val PREF_RSSI_MAX = "rssi_max"
        private const val PREF_START_AT = "schedule_start_at"
        private const val PREF_STOP_AT = "schedule_stop_at"
    }
}
