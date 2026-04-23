package com.bananabread.healthsync.ui.viewmodel

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bananabread.healthsync.BuildConfig
import com.bananabread.healthsync.data.Appointment
import com.bananabread.healthsync.data.GeminiService
import com.bananabread.healthsync.data.HealthSummary
import com.bananabread.healthsync.data.MedicalDataParser
import com.bananabread.healthsync.data.Medication
import com.bananabread.healthsync.util.NotificationHelper
import com.bananabread.healthsync.util.ReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Screen {
    Dashboard, Chatbot, Schedule, Medications, Settings
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val geminiService = GeminiService(BuildConfig.GEMINI_API_KEY)
    private val notificationHelper = NotificationHelper(application)
    private val prefs = application.getSharedPreferences("health_sync_prefs", Context.MODE_PRIVATE)

    private val _currentScreen = MutableStateFlow(Screen.Dashboard)
    val currentScreen = _currentScreen.asStateFlow()

    private val _medications = MutableStateFlow<List<Medication>>(emptyList())
    val medications = _medications.asStateFlow()

    private val _appointments = MutableStateFlow<List<Appointment>>(emptyList())
    val appointments = _appointments.asStateFlow()

    private val _healthSummary = MutableStateFlow(HealthSummary())
    val healthSummary = _healthSummary.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage("Hello! I am your HealthSync Assistant. How can I help you today?", false)
    ))
    val chatMessages = _chatMessages.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _capturedImages = MutableStateFlow<List<Bitmap>>(emptyList())
    val capturedImages = _capturedImages.asStateFlow()

    init {
        loadData()
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun addCapturedImage(bitmap: Bitmap) {
        _capturedImages.value = _capturedImages.value + bitmap
    }

    fun removeImage(index: Int) {
        val current = _capturedImages.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _capturedImages.value = current
        }
    }

    fun clearImages() {
        _capturedImages.value = emptyList()
    }

    fun updateMedicationTaken(medicationId: String, isTaken: Boolean) {
        _medications.value = _medications.value.map { medication ->
            if (medication.id == medicationId) {
                medication.copy(isTaken = isTaken)
            } else {
                medication
            }
        }
        saveData()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        
        _chatMessages.value = _chatMessages.value + ChatMessage(text, true)
        
        viewModelScope.launch {
            try {
                val context = "Diagnosis: ${_healthSummary.value.diagnosis}. " +
                        "Plan: ${_healthSummary.value.planOverview}. " +
                        "Meds: ${medications.value.joinToString { it.name }}. " +
                        "User: $text"
                
                val response = geminiService.extractMedicalData(context)
                if (response != null) {
                    _chatMessages.value = _chatMessages.value + ChatMessage(response, false)
                }
            } catch (e: Exception) {
                _chatMessages.value = _chatMessages.value + ChatMessage("I'm sorry, I couldn't process that. Please try again.", false)
            }
        }
    }

    fun scanCapturedImages() {
        if (_capturedImages.value.isEmpty()) return
        
        viewModelScope.launch {
            _isScanning.value = true
            _errorMessage.value = null
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "null") {
                    _errorMessage.value = "ERROR: Your API Key is empty."
                    return@launch
                }

                val result = geminiService.extractMedicalDataFromImages(_capturedImages.value)
                if (result != null) {
                    parseAndSetData(result)
                    clearImages()
                    notificationHelper.showNotification("Recovery Plan Ready", "AI has successfully processed your instructions.")
                } else {
                    _errorMessage.value = "AI returned no data."
                }
            } catch (e: Exception) {
                _errorMessage.value = "Scan Error: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    private fun parseAndSetData(jsonString: String) {
        try {
            val parsedData = MedicalDataParser.parse(jsonString)
            cancelMedicationAlarms(_medications.value)
            _medications.value = parsedData.medications
            _appointments.value = parsedData.appointments
            _healthSummary.value = parsedData.summary

            scheduleMedicationAlarms(parsedData.medications)
            saveData()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to parse data. Try clearer photos."
        }
    }

    private fun saveData() {
        val editor = prefs.edit()
        
        val medsArray = JSONArray()
        _medications.value.forEach { med ->
            val obj = JSONObject()
            obj.put("id", med.id)
            obj.put("name", med.name)
            obj.put("dosage", med.dosage)
            obj.put("time", med.time)
            obj.put("times", JSONArray(med.scheduleTimes))
            med.durationDays?.let { obj.put("durationDays", it) }
            obj.put("startDate", med.startDate)
            obj.put("endDate", med.endDate)
            obj.put("isTaken", med.isTaken)
            medsArray.put(obj)
        }
        editor.putString("medications", medsArray.toString())

        val apptsArray = JSONArray()
        _appointments.value.forEach { appt ->
            val obj = JSONObject()
            obj.put("id", appt.id)
            obj.put("title", appt.title)
            obj.put("doctorName", appt.doctorName)
            obj.put("time", appt.time)
            obj.put("date", appt.date)
            apptsArray.put(obj)
        }
        editor.putString("appointments", apptsArray.toString())

        editor.putString("diagnosis", _healthSummary.value.diagnosis)
        editor.putString("planOverview", _healthSummary.value.planOverview)
        
        editor.apply()
    }

    private fun loadData() {
        val medsStr = prefs.getString("medications", null)
        if (medsStr != null) {
            val arr = JSONArray(medsStr)
            val list = mutableListOf<Medication>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Medication(
                    obj.getString("id"),
                    obj.getString("name"),
                    obj.getString("dosage"),
                    extractScheduleTimes(obj),
                    obj.optInt("durationDays").takeIf { it > 0 },
                    obj.optString("startDate"),
                    obj.optString("endDate"),
                    obj.getBoolean("isTaken")
                ))
            }
            _medications.value = list
        }

        val apptsStr = prefs.getString("appointments", null)
        if (apptsStr != null) {
            val arr = JSONArray(apptsStr)
            val list = mutableListOf<Appointment>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Appointment(
                    obj.getString("id"),
                    obj.getString("title"),
                    obj.getString("doctorName"),
                    obj.getString("time"),
                    obj.getString("date")
                ))
            }
            _appointments.value = list
        }

        val diagnosis = prefs.getString("diagnosis", "") ?: ""
        val plan = prefs.getString("planOverview", "") ?: ""
        _healthSummary.value = HealthSummary(diagnosis, plan, _appointments.value.size, _medications.value.size)
        scheduleMedicationAlarms(_medications.value)
    }

    private fun scheduleMedicationAlarms(medications: List<Medication>) {
        medications.forEach { medication ->
            scheduleMedicationAlarmsForMedication(medication)
        }
    }

    private fun scheduleMedicationAlarmsForMedication(medication: Medication) {
        val context = getApplication<Application>().applicationContext
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        medication.scheduleTimes.forEach { time ->
            buildScheduledDoseTimes(medication, time).forEach { scheduledAt ->
                try {
                    val pendingIntent = createMedicationPendingIntent(context, medication, time, scheduledAt)
                    scheduleExactAlarm(alarmManager, scheduledAt, pendingIntent)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Alarm error for ${medication.name} at $time", e)
                }
            }
        }
    }

    private fun cancelMedicationAlarms(medications: List<Medication>) {
        val context = getApplication<Application>().applicationContext
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        medications.forEach { medication ->
            medication.scheduleTimes.forEach { time ->
                buildScheduledDoseTimes(medication, time).forEach { scheduledAt ->
                    alarmManager.cancel(createMedicationPendingIntent(context, medication, time, scheduledAt))
                }
            }
        }
    }

    private fun buildScheduledDoseTimes(medication: Medication, time: String): List<Long> {
        val timeParts = time.split(":")
        if (timeParts.size != 2) return emptyList()

        return try {
            val hour = timeParts[0].toInt()
            val minute = timeParts[1].toInt()
            val durationDays = resolveMedicationDurationDays(medication)
            val startCalendar = resolveMedicationStartCalendar(medication) ?: Calendar.getInstance()
            val now = Calendar.getInstance()

            buildList {
                repeat(durationDays) { offset ->
                    val scheduled = (startCalendar.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, offset)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (!scheduled.before(now)) {
                        add(scheduled.timeInMillis)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to build dose times for ${medication.name}", e)
            emptyList()
        }
    }

    private fun resolveMedicationDurationDays(medication: Medication): Int {
        medication.durationDays?.takeIf { it > 0 }?.let { return it }

        val parsedStart = medication.startDate.takeIf { it.isNotBlank() }
        val parsedEnd = medication.endDate.takeIf { it.isNotBlank() }
        if (parsedStart != null && parsedEnd != null) {
            inclusiveDaysBetween(parsedStart, parsedEnd)?.let { return it }
        }

        inferDurationDaysFromDosage(medication.dosage)?.let { return it }

        return 1
    }

    private fun scheduleExactAlarm(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (securityException: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        }
    }

    private fun resolveMedicationStartCalendar(medication: Medication): Calendar? {
        val parsedStart = medication.startDate.takeIf { it.isNotBlank() }?.let(::parseDate)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return when {
            parsedStart == null -> today
            parsedStart.before(today) -> today
            else -> parsedStart
        }
    }

    private fun parseDate(date: String): Calendar? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
            }
            val parsed = format.parse(date) ?: return null
            Calendar.getInstance().apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun inclusiveDaysBetween(startDate: String, endDate: String): Int? {
        val start = parseDate(startDate) ?: return null
        val end = parseDate(endDate) ?: return null
        if (end.before(start)) return null

        var days = 1
        val cursor = start.clone() as Calendar
        while (cursor.get(Calendar.YEAR) != end.get(Calendar.YEAR) ||
            cursor.get(Calendar.DAY_OF_YEAR) != end.get(Calendar.DAY_OF_YEAR)
        ) {
            cursor.add(Calendar.DAY_OF_YEAR, 1)
            days++
        }
        return days
    }

    private fun inferDurationDaysFromDosage(dosage: String): Int? {
        val match = Regex("""(?:for|x)\s+(\d+)\s+(day|days|week|weeks|month|months)\b""")
            .find(dosage.lowercase(Locale.getDefault()))
            ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2]) {
            "day", "days" -> amount
            "week", "weeks" -> amount * 7
            "month", "months" -> amount * 30
            else -> null
        }?.takeIf { it > 0 }
    }

    private fun createMedicationPendingIntent(
        context: Context,
        medication: Medication,
        time: String,
        scheduledAt: Long
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("medication_name", medication.name)
            putExtra("dosage", medication.dosage)
            putExtra("time", time)
            putExtra("scheduled_at", scheduledAt)
        }

        return PendingIntent.getBroadcast(
            context,
            "${medication.id}:$time:$scheduledAt".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun extractScheduleTimes(obj: JSONObject): List<String> {
        val timesArray = obj.optJSONArray("times")
        if (timesArray != null) {
            return buildList {
                for (i in 0 until timesArray.length()) {
                    val time = timesArray.optString(i)
                    if (time.isNotBlank()) add(time)
                }
            }
        }

        val legacyTime = obj.optString("time")
        return if (legacyTime.isBlank()) emptyList() else listOf(legacyTime)
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
