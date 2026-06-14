package com.yourcompany.tihwinnew

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets

// Data class to hold the essential info extracted from the ISO selection.
data class IsoInfo(val gameId: String?, val displayName: String?)

object IsoUtil {
    private const val TAG = "IsoUtil"

    /**
     * Public function to get all necessary info from a user-selected URI in one go.
     */
    fun getIsoInfo(context: Context, uri: Uri): IsoInfo {
        val gameId = extractGameId(context, uri)
        val displayName = getDisplayNameFromUri(context, uri)
        return IsoInfo(gameId, displayName)
    }

    /**
     * Calculates the OPL-specific CRC32 value for a given game title.
     * This implementation is a direct port of the one found in the original Tihwin desktop app,
     * which is required for compatibility with OPL.
     *
     * @param title The game title, which should be less than 32 characters.
     * @return The calculated 32-bit integer CRC value.
     */
    internal fun oplCrc32(title: String): Int {
        val trimmedTitle = title.trim()
        if (trimmedTitle.length > 31) {
            Log.w(TAG, "Title length exceeds 31 characters, which might cause issues.")
            // Potentially truncate or handle as an error, for now, we log a warning.
        }

        // 1. Pad the title to 32 bytes with nulls
        val decodedString = ByteArray(32)
        val titleBytes = trimmedTitle.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(titleBytes, 0, decodedString, 0, titleBytes.size.coerceAtMost(32))

        // 2. Generate the custom CRC table
        val crcTable = IntArray(256)
        for (i in 0..255) {
            var crc = i shl 24
            for (j in 0..7) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    (crc shl 1)
                } else {
                    (crc shl 1) xor 0x04C11DB7
                }
            }
            crcTable[255 - i] = crc
        }

        // 3. Calculate the CRC using the custom table
        var crc = 0
        // The loop must include the null terminator after the string
        for (i in 0..trimmedTitle.length) {
            val byte = decodedString[i].toInt() and 0xFF // Treat byte as unsigned
            val lookupIndex = byte xor (crc ushr 24)
            crc = crcTable[lookupIndex] xor (crc shl 8)
        }

        return crc
    }


    /**
     * Extracts the PS2 Game ID from the ISO header. Now private.
     */
    private fun extractGameId(context: Context, uri: Uri): String? {
        Log.d(TAG, "extractGameId() called with URI: $uri")
        val gameIdRegex = """([A-Z]{4}_[0-9]{3}\.[0-9]{2})""".toRegex()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(3 * 1024 * 1024) // 3MB
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val headerAsString = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
                    val match = gameIdRegex.find(headerAsString)
                    if (match != null) {
                        Log.d(TAG, "extractGameId(): Game ID found: ${match.value}")
                        return match.value
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "extractGameId(): IOException while reading ISO: ${e.message}", e)
        }
        return null
    }

    /**
     * Gets a clean display name from the file URI. Now private.
     */
    private fun getDisplayNameFromUri(context: Context, uri: Uri): String? {
        val fileName = try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex) else uri.lastPathSegment
                } else {
                    uri.lastPathSegment
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting display name from URI: $uri", e)
            uri.lastPathSegment // Fallback
        }

        return fileName?.let {
            val extensionIndex = it.lastIndexOf('.')
            val nameWithoutExtension = if (extensionIndex > 0) it.substring(0, extensionIndex) else it

            nameWithoutExtension
                .replace(Regex("""\s*\(.*?\)"""), "")
                .replace(Regex("""\s*\[.*?\]"""), "")
                .trim()
        }
    }
}
