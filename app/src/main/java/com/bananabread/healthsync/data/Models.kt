package com.bananabread.healthsync.data

data class Medication(
    val id: String,
    val name: String,
    val dosage: String,
    val scheduleTimes: List<String>,
    val durationDays: Int? = null,
    val startDate: String = "",
    val endDate: String = "",
    val isTaken: Boolean = false
) {
    val time: String
        get() = scheduleTimes.firstOrNull().orEmpty()
}

data class Appointment(
    val id: String,
    val title: String,
    val doctorName: String,
    val time: String,
    val date: String
)

data class HealthSummary(
    val diagnosis: String = "",
    val planOverview: String = "",
    val upcomingAppointments: Int = 0,
    val todaysMedications: Int = 0
)
