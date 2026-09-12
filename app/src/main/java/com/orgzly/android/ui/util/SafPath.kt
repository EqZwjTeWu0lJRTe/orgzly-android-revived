package com.orgzly.android.ui.util

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * Helps display SAF tree URIs (content://…/tree/…) as readable local file paths.
 * Only content URIs backed by the device's own storage volumes can be decoded;
 * anything else falls back to the original URI.
 */
object SafPath {

    /** Decodes a repository URI to a local [File] when possible (SAF tree or file://). */
    fun toFile(context: Context, uriString: String): File? {
        if (uriString.isBlank()) return null
        val uri = Uri.parse(uriString)

        if (uri.scheme == "file") {
            return uri.path?.let { File(it) }
        }

        if (uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents") {
            val documentId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                return null
            }

            val separator = documentId.indexOf(':')
            if (separator < 0) return null

            val volume = documentId.substring(0, separator)
            val relative = Uri.decode(documentId.substring(separator + 1))

            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                    ?: return null

            for (storage in storageManager.storageVolumes) {
                val dir = storage.directory ?: continue
                val key = if (storage.isPrimary) "primary" else storage.uuid
                if (key != null && key == volume) {
                    return File(dir, relative)
                }
            }
        }

        return null
    }

    /** Human-readable path for the list row, or the raw URI when it cannot be decoded. */
    fun displayText(context: Context, uriString: String?): String {
        if (uriString.isNullOrBlank()) return uriString.orEmpty()
        return toFile(context, uriString)?.absolutePath ?: uriString
    }
}
