package com.aria.ariacast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val html_url: String,
    val body: String? = null
)

class UpdateManager(private val context: Context) {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    suspend fun checkForUpdates(manual: Boolean = false) {
        try {
            val latestRelease: GitHubRelease = client.get("https://api.github.com/repos/AriaCast/AriaCast-app/releases/latest").body()
            val currentVersion = context.getString(R.string.app_version)
            
            if (isNewerVersion(latestRelease.tag_name, currentVersion)) {
                showUpdateDialog(latestRelease)
            } else if (manual) {
                showNoUpdateDialog()
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Failed to check for updates", e)
            if (manual) {
                showErrorDialog()
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        if (latest == current) return false
        
        val latestClean = latest.removePrefix("v")
        val currentClean = current.removePrefix("v")
        
        val latestParts = latestClean.split("[.-]".toRegex())
        val currentParts = currentClean.split("[.-]".toRegex())
        
        val length = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until length) {
            val l = latestParts.getOrNull(i)
            val c = currentParts.getOrNull(i)
            
            if (l == c) continue
            if (l == null) return false 
            if (c == null) return true  
            
            val lNum = l.filter { it.isDigit() }.toIntOrNull()
            val cNum = c.filter { it.isDigit() }.toIntOrNull()
            
            if (lNum != null && cNum != null) {
                if (lNum > cNum) return true
                if (lNum < cNum) return false
                if (l.length > c.length && l.startsWith(c)) return true
                if (c.length > l.length && c.startsWith(l)) return false
            }
            
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_available_title))
            .setMessage(context.getString(R.string.update_available_message, release.tag_name, release.body ?: ""))
            .setPositiveButton(context.getString(R.string.download)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.html_url))
                context.startActivity(intent)
            }
            .setNegativeButton(context.getString(R.string.later), null)
            .show()
    }

    private fun showNoUpdateDialog() {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.up_to_date_title))
            .setMessage(context.getString(R.string.up_to_date_message))
            .setPositiveButton(context.getString(R.string.ok), null)
            .show()
    }

    private fun showErrorDialog() {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.update_failed_title))
            .setMessage(context.getString(R.string.update_failed_message))
            .setPositiveButton(context.getString(R.string.ok), null)
            .show()
    }
}
