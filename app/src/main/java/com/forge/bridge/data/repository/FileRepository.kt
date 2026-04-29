package com.forge.bridge.data.repository

import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val uploadDir = File(context.filesDir, "uploads").apply { mkdirs() }

    fun saveFile(fileName: String, bytes: ByteArray): File {
        val file = File(uploadDir, fileName)
        file.writeBytes(bytes)
        return file
    }

    fun getFile(fileName: String): File? {
        val file = File(uploadDir, fileName)
        return if (file.exists()) file else null
    }

    fun deleteFile(fileName: String) {
        File(uploadDir, fileName).delete()
    }

    fun getFileBase64(fileName: String): String? {
        val file = getFile(fileName) ?: return null
        return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    fun getMimeType(fileName: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(fileName)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
    }
}
