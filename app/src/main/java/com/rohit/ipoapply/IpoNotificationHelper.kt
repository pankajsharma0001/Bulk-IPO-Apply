package com.rohit.bulkipoapply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

object IpoNotificationHelper {
    const val CHANNEL_ID = "ipo_notifications"
    private const val PREFS_NAME = "ipo"
    private const val KEY_SEEN_IPO_IDS = "seen_ipo_ids"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "New IPO Alerts"
            val descriptionText = "Notifications when a new IPO opens for application"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun checkAndNotifyNewIssues(context: Context, issues: JSONArray) {
        if (issues.length() == 0) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seenIds = prefs.getStringSet(KEY_SEEN_IPO_IDS, null)?.toMutableSet()

        val isFirstRun = (seenIds == null)
        val currentSeenIds = seenIds ?: mutableSetOf()
        val newIssuesToNotify = mutableListOf<JSONObject>()

        for (i in 0 until issues.length()) {
            val issue = issues.getJSONObject(i)
            val shareId = issue.optInt("companyShareId", -1)
            if (shareId == -1) continue

            val shareIdStr = shareId.toString()
            if (!currentSeenIds.contains(shareIdStr)) {
                newIssuesToNotify.add(issue)
                currentSeenIds.add(shareIdStr)
            }
        }

        // Save updated seen set
        prefs.edit().putStringSet(KEY_SEEN_IPO_IDS, currentSeenIds).apply()

        // Post notification for new issues
        for (issue in newIssuesToNotify) {
            showNotification(context, issue)
        }
    }

    private fun showNotification(context: Context, issue: JSONObject) {
        createNotificationChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shareId = issue.optInt("companyShareId", System.currentTimeMillis().toInt())
        val scrip = issue.optString("scrip", "IPO")
        val company = clean(issue.optString("companyName", "Company"))
        val shareType = issue.optString("shareTypeName", "IPO")

        val title = "New IPO Available: $scrip"
        val message = "$company ($shareType) is now open for application!"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("opened_from_notification", true)
            putExtra("company_share_id", shareId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(context, shareId, intent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }

        notificationManager.notify(shareId, builder.build())
    }

    fun showPushNotification(context: Context, title: String, message: String, shareId: Int = System.currentTimeMillis().toInt()) {
        createNotificationChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("opened_from_notification", true)
            putExtra("company_share_id", shareId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(context, shareId, intent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }

        notificationManager.notify(shareId, builder.build())
    }

    private fun clean(s: String) = s.trim().replace(Regex("\\s+"), " ")
}
