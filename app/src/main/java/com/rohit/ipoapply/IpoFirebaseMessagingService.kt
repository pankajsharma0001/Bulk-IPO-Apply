package com.rohit.bulkipoapply

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class IpoFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_NAME)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "New IPO Announcement"

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: "A new IPO is available for application."

        val shareIdStr = remoteMessage.data["companyShareId"]
        val shareId = shareIdStr?.toIntOrNull() ?: System.currentTimeMillis().toInt()

        IpoNotificationHelper.showPushNotification(this, title, body, shareId)
    }

    companion object {
        const val TOPIC_NAME = "new_ipos"
    }
}
