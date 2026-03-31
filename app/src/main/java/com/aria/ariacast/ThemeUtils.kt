package com.aria.ariacast

import androidx.appcompat.app.AppCompatDelegate

object ThemeUtils {
    const val MODE_NIGHT_NO = AppCompatDelegate.MODE_NIGHT_NO
    const val MODE_NIGHT_YES = AppCompatDelegate.MODE_NIGHT_YES
    const val MODE_NIGHT_FOLLOW_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    fun applyTheme(themeMode: Int) {
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }

    fun getThemeForAccent(accentColorResId: Int): Int {
        return when (accentColorResId) {
            R.color.accent_blue -> R.style.Theme_AriaCast_Blue
            R.color.accent_purple -> R.style.Theme_AriaCast_Purple
            R.color.accent_green -> R.style.Theme_AriaCast_Green
            R.color.accent_orange -> R.style.Theme_AriaCast_Orange
            R.color.accent_pink -> R.style.Theme_AriaCast_Pink
            else -> R.style.Theme_AriaCast
        }
    }
}
