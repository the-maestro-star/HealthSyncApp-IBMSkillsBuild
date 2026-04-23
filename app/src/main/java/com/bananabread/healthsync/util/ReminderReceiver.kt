package com.bananabread.healthsync.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medName = intent.getStringExtra("medication_name") ?: "Medication"
        val dosage = intent.getStringExtra("dosage") ?: ""
        val time = intent.getStringExtra("time") ?: ""
        
        Log.d("ReminderReceiver", "Alarm received for: $medName")

        val notificationHelper = NotificationHelper(context)
        notificationHelper.showNotification(
            "Medication Reminder",
            "It's time to take your $medName ($dosage)${if (time.isNotBlank()) " at $time" else ""}."
        )

        // Sound the alarm
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, alarmUri)
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
        } catch (e: Exception) {
            Log.e("ReminderReceiver", "Error playing ringtone", e)
        }
    }
}
