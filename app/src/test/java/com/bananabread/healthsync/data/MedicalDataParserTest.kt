package com.bananabread.healthsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicalDataParserTest {
    @Test
    fun `parses valid payload and normalizes invalid time fields`() {
        val parsed = MedicalDataParser.parse(
            """
            {
              "diagnosis": "You are recovering well.",
              "planOverview": "Rest and follow up.",
              "medications": [
                {"name": "Ibuprofen", "dosage": "200mg", "times": ["09:30", "21:30"]},
                {"name": "Vitamin D", "dosage": "1000 IU", "time": "morning"}
              ],
              "appointments": [
                {"title": "Primary Care", "doctorName": "Dr. Kim", "date": "2026-04-15", "time": "14:00"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("You are recovering well.", parsed.summary.diagnosis)
        assertEquals(2, parsed.medications.size)
        assertEquals(listOf("09:30", "21:30"), parsed.medications[0].scheduleTimes)
        assertEquals(emptyList<String>(), parsed.medications[1].scheduleTimes)
        assertEquals(1, parsed.appointments.size)
    }

    @Test
    fun `ignores incomplete entries and accepts fenced json`() {
        val parsed = MedicalDataParser.parse(
            """
            ```json
            {
              "diagnosis": "",
              "planOverview": "",
              "medications": [
                {"name": "", "dosage": "5mg", "time": "08:00"},
                {"name": "Amoxicillin", "dosage": "", "time": "08:00"}
              ],
              "appointments": [
                {"title": "", "doctorName": "Dr. A", "date": "2026-04-20", "time": "09:00"}
              ]
            }
            ```
            """.trimIndent()
        )

        assertEquals("Recovery in progress", parsed.summary.diagnosis)
        assertEquals("Follow the scheduled medications and appointments.", parsed.summary.planOverview)
        assertEquals(1, parsed.medications.size)
        assertEquals("Amoxicillin", parsed.medications.first().name)
        assertTrue(parsed.appointments.isEmpty())
    }

    @Test
    fun `parses medication duration from explicit fields and dosage text`() {
        val parsed = MedicalDataParser.parse(
            """
            {
              "diagnosis": "Infection",
              "planOverview": "Finish the medications.",
              "medications": [
                {
                  "name": "Amoxicillin",
                  "dosage": "500mg",
                  "times": ["09:00"],
                  "durationDays": 7,
                  "startDate": "2026-04-23"
                },
                {
                  "name": "Prednisone",
                  "dosage": "10mg for 5 days",
                  "times": ["08:00"]
                }
              ],
              "appointments": []
            }
            """.trimIndent()
        )

        assertEquals(7, parsed.medications[0].durationDays)
        assertEquals("2026-04-23", parsed.medications[0].startDate)
        assertEquals(5, parsed.medications[1].durationDays)
    }
}
