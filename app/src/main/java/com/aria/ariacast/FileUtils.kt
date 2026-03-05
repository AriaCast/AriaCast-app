package com.aria.ariacast

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object FileUtils {
    /**
     * Tries to resolve a Uri to a physical file path.
     * Note: This is a best-effort implementation for common Tree URIs.
     */
    fun getPath(context: Context, uri: Uri): String? {
        if (DocumentsContract.isTreeUri(uri)) {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val split = documentId.split(":")
            if (split.size >= 2) {
                val type = split[0]
                val relativePath = split[1]

                if ("primary".equals(type, ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory()}/$relativePath"
                } else {
                    // Attempt to find SD card path
                    val externalDirs = context.getExternalFilesDirs(null)
                    for (dir in externalDirs) {
                        val path = dir.absolutePath
                        if (path.contains(type)) {
                            return "${path.substring(0, path.indexOf("Android"))}$relativePath"
                        }
                    }
                }
            }
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }
}
