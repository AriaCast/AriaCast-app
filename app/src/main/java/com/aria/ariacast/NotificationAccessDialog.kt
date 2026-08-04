package com.aria.ariacast

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Activity.showNotificationAccessExplanationDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.notification_access_required_title)
        .setMessage(R.string.notification_access_required_message)
        .setPositiveButton(R.string.open_notification_settings) { _, _ ->
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
