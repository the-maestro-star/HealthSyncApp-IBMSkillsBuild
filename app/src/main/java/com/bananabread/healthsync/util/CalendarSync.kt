package com.bananabread.healthsync.util

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.bananabread.healthsync.data.Appointment
import com.bananabread.healthsync.data.Medication
import java.text.SimpleDateFormat
import java.util.*

class CalendarSync(private val context: Context) {

    private fun getCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE
        )
        
        try {
            var fallbackCalendarId: Long? = null
            var nonVisibleFallbackCalendarId: Long? = null

            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val accessCol = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                val accountTypeCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val visibleCol = cursor.getColumnIndex(CalendarContract.Calendars.VISIBLE)

                while (cursor.moveToNext()) {
                    val access = cursor.getInt(accessCol)
                    val accountType = cursor.getString(accountTypeCol).orEmpty()
                    val isVisible = cursor.getInt(visibleCol) == 1

                    // Level 500+ means we can add events.
                    if (access >= 500 && isVisible) {
                        val calendarId = cursor.getLong(idCol)
                        if (accountType == GOOGLE_ACCOUNT_TYPE) {
                            return calendarId
                        }

                        if (fallbackCalendarId == null) {
                            fallbackCalendarId = calendarId
                        }
                    } else if (access >= 500 && nonVisibleFallbackCalendarId == null) {
                        nonVisibleFallbackCalendarId = cursor.getLong(idCol)
                    }
                }

                return fallbackCalendarId ?: nonVisibleFallbackCalendarId
            }
        } catch (e: Exception) {
            Log.e("CalendarSync", "Query error", e)
        }
        return null
    }

    fun addAppointmentToCalendar(appointment: Appointment): Boolean {
        return try {
            val calendarId = getCalendarId() ?: return false
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val date = sdf.parse("${appointment.date} ${appointment.time}") ?: return false
            
            val beginTime = Calendar.getInstance().apply { time = date }
            val endTime = Calendar.getInstance().apply { 
                time = date
                add(Calendar.HOUR, 1) 
            }

            if (eventExists(calendarId, "Appointment: ${appointment.title}", beginTime.timeInMillis)) {
                return true
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, beginTime.timeInMillis)
                put(CalendarContract.Events.DTEND, endTime.timeInMillis)
                put(CalendarContract.Events.TITLE, "Appointment: ${appointment.title}")
                put(CalendarContract.Events.DESCRIPTION, "Doctor: ${appointment.doctorName}")
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun addMedicationToCalendar(medication: Medication): Boolean {
        val calendarId = getCalendarId() ?: return false
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val originalStartDate = medication.startDate.takeIf { it.isNotBlank() }
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val startDate = resolveEffectiveStartDate(originalStartDate)
        val untilUtc = buildUntilUtc(medication, startDate)

        var insertedAny = false
        medication.scheduleTimes.forEach { time ->
            try {
                val date = sdf.parse("$startDate $time") ?: return@forEach

                val beginTime = Calendar.getInstance().apply { this.time = date }
                val endTime = Calendar.getInstance().apply {
                    this.time = date
                    add(Calendar.MINUTE, 15)
                }

                val title = "Take: ${medication.name}"
                val values = ContentValues().apply {
                    put(CalendarContract.Events.DTSTART, beginTime.timeInMillis)
                    put(CalendarContract.Events.DTEND, endTime.timeInMillis)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, "Dosage: ${medication.dosage}")
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(CalendarContract.Events.HAS_ALARM, 1)
                    put(CalendarContract.Events.RRULE, buildDailyRRule(untilUtc, medication.durationDays))
                }

                val existingEventId = findMedicationEventId(
                    calendarId = calendarId,
                    title = title,
                    hourOfDay = beginTime.get(Calendar.HOUR_OF_DAY),
                    minute = beginTime.get(Calendar.MINUTE)
                )
                if (existingEventId != null) {
                    val eventUri = CalendarContract.Events.CONTENT_URI.buildUpon()
                        .appendPath(existingEventId.toString())
                        .build()
                    context.contentResolver.update(eventUri, values, null, null)
                    replaceReminder(existingEventId)
                    insertedAny = true
                } else {
                    val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    if (inserted != null) {
                        insertReminder(inserted.lastPathSegment?.toLongOrNull())
                        insertedAny = true
                    }
                }
            } catch (e: Exception) {
                Log.e("CalendarSync", "Medication insert error", e)
            }
        }

        return insertedAny
    }

    private fun resolveEffectiveStartDate(originalStartDate: String): String {
        val parsedStart = parseDateAtStartOfDay(originalStartDate)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val effective = when {
            parsedStart == null -> today
            parsedStart.before(today) -> today
            else -> parsedStart
        }

        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(effective.time)
    }

    private fun eventExists(calendarId: Long, title: String, startTimeMillis: Long): Boolean {
        return findEventId(calendarId, title, startTimeMillis) != null
    }

    private fun findEventId(calendarId: Long, title: String, startTimeMillis: Long): Long? {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = buildString {
            append("${CalendarContract.Events.CALENDAR_ID} = ? AND ")
            append("${CalendarContract.Events.TITLE} = ? AND ")
            append("${CalendarContract.Events.DTSTART} = ?")
        }
        val selectionArgs = arrayOf(calendarId.toString(), title, startTimeMillis.toString())

        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events._ID))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("CalendarSync", "Event lookup error", e)
            null
        }
    }

    private fun findMedicationEventId(
        calendarId: Long,
        title: String,
        hourOfDay: Int,
        minute: Int
    ): Long? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.DTSTART
        )
        val selection = buildString {
            append("${CalendarContract.Events.CALENDAR_ID} = ? AND ")
            append("${CalendarContract.Events.TITLE} = ?")
        }
        val selectionArgs = arrayOf(calendarId.toString(), title)

        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val startIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext()) {
                    val eventStart = Calendar.getInstance().apply {
                        timeInMillis = cursor.getLong(startIndex)
                    }
                    if (eventStart.get(Calendar.HOUR_OF_DAY) == hourOfDay &&
                        eventStart.get(Calendar.MINUTE) == minute
                    ) {
                        return cursor.getLong(idIndex)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e("CalendarSync", "Medication event lookup error", e)
            null
        }
    }

    private fun insertReminder(eventId: Long?) {
        if (eventId == null) return

        try {
            val reminderValues = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, 0)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
        } catch (e: Exception) {
            Log.e("CalendarSync", "Reminder insert error", e)
        }
    }

    private fun replaceReminder(eventId: Long) {
        try {
            context.contentResolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString())
            )
        } catch (e: Exception) {
            Log.e("CalendarSync", "Reminder cleanup error", e)
        }
        insertReminder(eventId)
    }

    private fun buildDailyRRule(untilUtc: String?, durationDays: Int?): String {
        untilUtc?.let { return "FREQ=DAILY;UNTIL=$it" }
        durationDays?.takeIf { it > 0 }?.let { return "FREQ=DAILY;COUNT=$it" }
        return "FREQ=DAILY;COUNT=1"
    }

    private fun buildUntilUtc(medication: Medication, startDate: String): String? {
        val durationDays = resolveMedicationDurationDays(medication) ?: return null
        val start = parseLocalDate(startDate) ?: return null
        start.add(Calendar.DAY_OF_YEAR, durationDays - 1)
        return formatUntilUtc(start)
    }

    private fun resolveMedicationDurationDays(medication: Medication): Int? {
        medication.durationDays?.takeIf { it > 0 }?.let { return it }

        val parsedStart = medication.startDate.takeIf { it.isNotBlank() }
        val parsedEnd = medication.endDate.takeIf { it.isNotBlank() }
        if (parsedStart != null && parsedEnd != null) {
            inclusiveDaysBetween(parsedStart, parsedEnd)?.let { return it }
        }

        return inferDurationDaysFromDosage(medication.dosage)
    }

    private fun parseLocalDate(date: String): Calendar? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
            }
            val parsed = format.parse(date) ?: return null
            Calendar.getInstance().apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDateAtStartOfDay(date: String): Calendar? {
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
        val start = parseDateAtStartOfDay(startDate) ?: return null
        val end = parseDateAtStartOfDay(endDate) ?: return null
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

    private fun formatUntilUtc(calendar: Calendar): String {
        val utcFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return utcFormat.format(calendar.time)
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
