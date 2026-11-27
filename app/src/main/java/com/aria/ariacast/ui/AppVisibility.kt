package com.aria.ariacast.ui

/**
 * Whether one of the app's screens is on show. A background service may only open a window while
 * this is true; otherwise it has to use a notification.
 */
object AppVisibility {
    @Volatile
    var visible = false
}
