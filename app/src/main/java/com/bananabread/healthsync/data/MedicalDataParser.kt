package com.bananabread.healthsync.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class ParsedMedicalData(
    val summary: HealthSummary,
    val medications: List<Medication>,
    val appointments: List<Appointment>
)

object MedicalDataParser {
    fun parse(jsonString: String): ParsedMedicalData {
        val jsonObject = JSONObject(extractJsonPayload(jsonString))

        val medications = jsonObject.optJSONArray("medications")
            ?.let { medicationsJson ->
                buildList {
                    for (index in 0 until medicationsJson.length()) {
                        val medicationJson = medicationsJson.optJSONObject(index) ?: continue
                        val name = medicationJson.optString("name").trim()
                        if (name.isEmpty()) continue

                        add(
                            Medication(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                dosage = medicationJson.optString("dosage").trim(),
                                scheduleTimes = extractScheduleTimes(medicationJson),
                                durationDays = extractDurationDays(medicationJson),
                                startDate = normalizeDate(medicationJson.optString("startDate")),
                                endDate = normalizeDate(medicationJson.optString("endDate"))
                            )
                        )
                    }
                }
            }
            ?: emptyList()

        val appointments = jsonObject.optJSONArray("appointments")
            ?.let { appointmentsJson ->
                buildList {
                    for (index in 0 until appointmentsJson.length()) {
                        val appointmentJson = appointmentsJson.optJSONObject(index) ?: continue
                        val title = appointmentJson.optString("title").trim()
                        if (title.isEmpty()) continue

                        add(
                            Appointment(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                doctorName = appointmentJson.optString("doctorName").trim(),
                                time = normalizeTime(appointmentJson.optString("time")),
                                date = appointmentJson.optString("date").trim()
                            )
                        )
                    }
                }
            }
            ?: emptyList()

        val diagnosis = jsonObject.optString("diagnosis", "Recovery in progress").trim()
        val planOverview = jsonObject.optString(
            "planOverview",
            "Follow the scheduled medications and appointments."
        ).trim()

        return ParsedMedicalData(
            summary = HealthSummary(
                diagnosis = diagnosis.ifEmpty { "Recovery in progress" },
                planOverview = planOverview.ifEmpty {
                    "Follow the scheduled medications and appointments."
                },
                upcomingAppointments = appointments.size,
                todaysMedications = medications.sumOf { it.scheduleTimes.size.coerceAtLeast(1) }
            ),
            medications = medications,
            appointments = appointments
        )
    }

    private fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains("```")) {
            return trimmed
        }

        val fenced = trimmed.substringAfter("```").substringAfter('\n', trimmed)
        return fenced.substringBefore("```").trim()
    }

    private fun normalizeTime(time: String): String {
        val candidate = time.trim()
        return if (TIME_PATTERN.matches(candidate)) candidate else ""
    }

    private fun extractScheduleTimes(medicationJson: JSONObject): List<String> {
        val explicitTimes = medicationJson.optJSONArray("times")
            ?.let { timesJson ->
                buildList {
                    for (index in 0 until timesJson.length()) {
                        val normalized = normalizeTime(timesJson.optString(index))
                        if (normalized.isNotEmpty() && normalized !in this) {
                            add(normalized)
                        }
                    }
                }
            }
            .orEmpty()

        if (explicitTimes.isNotEmpty()) {
            return explicitTimes
        }

        val singleTime = normalizeTime(medicationJson.optString("time"))
        return if (singleTime.isEmpty()) emptyList() else listOf(singleTime)
    }

    private fun extractDurationDays(medicationJson: JSONObject): Int? {
        val explicitDuration = medicationJson.optInt("durationDays", -1)
        if (explicitDuration > 0) {
            return explicitDuration
        }

        val endDate = normalizeDate(medicationJson.optString("endDate"))
        val startDate = normalizeDate(medicationJson.optString("startDate"))
        if (startDate.isNotEmpty() && endDate.isNotEmpty()) {
            daysBetweenInclusive(startDate, endDate)?.let { return it }
        }

        return inferDurationDaysFromText(
            listOf(
                medicationJson.optString("dosage"),
                medicationJson.optString("instructions"),
                medicationJson.optString("notes")
            ).joinToString(" ")
        )
    }

    private fun inferDurationDaysFromText(text: String): Int? {
        val candidate = text.lowercase(Locale.getDefault())
        val match = DURATION_PATTERN.find(candidate) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2]) {
            "day", "days" -> amount
            "week", "weeks" -> amount * 7
            "month", "months" -> amount * 30
            else -> null
        }?.takeIf { it > 0 }
    }

    private fun normalizeDate(date: String): String {
        val candidate = date.trim()
        return if (DATE_PATTERN.matches(candidate)) candidate else ""
    }

    private fun daysBetweenInclusive(startDate: String, endDate: String): Int? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
            }
            val start = format.parse(startDate) ?: return null
            val end = format.parse(endDate) ?: return null
            val startCalendar = Calendar.getInstance().apply { time = start }
            val endCalendar = Calendar.getInstance().apply { time = end }
            if (endCalendar.before(startCalendar)) return null

            var days = 1
            while (startCalendar.get(Calendar.YEAR) != endCalendar.get(Calendar.YEAR) ||
                startCalendar.get(Calendar.DAY_OF_YEAR) != endCalendar.get(Calendar.DAY_OF_YEAR)
            ) {
                startCalendar.add(Calendar.DAY_OF_YEAR, 1)
                days++
            }
            days
        } catch (_: Exception) {
            null
        }
    }

    private val TIME_PATTERN = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")
    private val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val DURATION_PATTERN = Regex("""(?:for|x)\s+(\d+)\s+(day|days|week|weeks|month|months)\b""")
}
