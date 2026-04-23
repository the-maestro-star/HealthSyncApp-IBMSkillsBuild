package com.bananabread.healthsync.data

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GeminiService(private val apiKey: String) {
    private val modelId = "gemini-3-flash-preview"

    suspend fun extractMedicalDataFromImages(bitmaps: List<Bitmap>): String? {
        val model = GenerativeModel(modelName = modelId, apiKey = apiKey)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val timezone = TimeZone.getDefault().id
        val prompt = content {
            text("""
                Extract the following from these medical discharge instructions:
                1. Diagnosis: A clear summary of the diagnosis. Use "You" instead of "The patient".
                2. Plan Overview: A high-level overview of the recovery plan. Use "You" instead of "The patient".
                3. Medications: A list including 'name', 'dosage', 'times' (array of HH:mm 24-hour times in the user's local timezone), plus 'durationDays' when stated, and 'startDate'/'endDate' in YYYY-MM-DD when stated or inferable.
                4. Appointments: A list including 'title', 'doctorName', 'date' (YYYY-MM-DD), and 'time' (HH:mm 24-hour format).
                
                Format the output as a JSON object with keys: 'diagnosis', 'planOverview', 'medications', and 'appointments'.
                
                IMPORTANT: 
                - The current local date/time is $now in timezone $timezone.
                - Address the patient directly as "You".
                - Return ONLY valid JSON. 
                - Do not include markdown code blocks.
                - If details are missing, use empty strings or lists.
                - If the year is missing, assume 2026.
                - Convert relative instructions such as "take in 6 hours", "start tonight", or "next dose at bedtime" into exact local HH:mm medication times when possible.
                - If a medication is taken multiple times per day, include every exact dose time in the 'times' array.
                - If a medication has a limited course such as "for 7 days" or "until April 30", include that as 'durationDays' and/or 'endDate' so reminders do not repeat forever.
            """.trimIndent())
            bitmaps.forEach { bitmap ->
                image(resizeBitmap(bitmap, 1024))
            }
        }

        return try {
            model.generateContent(prompt).text
        } catch (e: Exception) {
            Log.e("GeminiService", "Scan failed: ${e.message}")
            throw e
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / Math.max(width, height)
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    suspend fun extractMedicalData(rawText: String): String? {
        val model = GenerativeModel(modelName = modelId, apiKey = apiKey)
        val prompt = """
            You are a helpful healthcare assistant for an app called HealthSync AI.
            Based on this medical context: $rawText
            Provide a concise and helpful answer to the user's question. 
            If you don't know the answer, advise them to contact their doctor.
        """.trimIndent()
        
        return try {
            model.generateContent(prompt).text
        } catch (e: Exception) {
            throw e
        }
    }
}
