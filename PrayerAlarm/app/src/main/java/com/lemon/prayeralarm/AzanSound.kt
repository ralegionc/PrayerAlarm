package com.lemon.prayeralarm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Decides which recording plays for a given prayer.
 *
 * Users supply their own audio: one file for Fajr (whose adhan has an extra line) and one for
 * the remaining four prayers. Resolution order for Fajr is its own file, then the shared file,
 * then null — and null means the caller should fall back to the device's alarm ringtone, so a
 * user who adds nothing still gets a working alarm.
 */
object AzanSound {

    /** The recording to play for [prayer], or null to fall back to the alarm ringtone. */
    fun uriFor(context: Context, prayer: Prayer): Uri? {
        val prefs = PrefsRepository(context)
        val stored = when (prayer) {
            Prayer.FAJR -> prefs.fajrAzanUri.ifBlank { prefs.defaultAzanUri }
            else -> prefs.defaultAzanUri
        }
        if (stored.isBlank()) return null
        return try {
            Uri.parse(stored)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * File name to show in Settings, or null if the document is gone or unreadable — which is
     * how a revoked permission or a deleted file surfaces to the user.
     */
    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    } catch (e: Exception) {
        null
    }

    /** True when the URI can actually be opened, so Settings never shows a dead selection. */
    fun isReadable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }
}
