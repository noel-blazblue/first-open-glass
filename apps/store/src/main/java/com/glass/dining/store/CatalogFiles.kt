package com.glass.dining.store

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.glass.dining.shared.catalog.StoreCatalogIds
import com.glass.dining.shared.catalog.StoreCatalogJson
import com.glass.dining.shared.model.Store
import java.io.File

object CatalogFiles {
    fun privateFile(context: Context): File {
        return File(context.filesDir, StoreCatalogIds.FILE_NAME)
    }

    fun publicFile(): File {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(dir, StoreCatalogIds.FILE_NAME)
    }

    fun read(context: Context): List<Store> {
        val sources = listOf(privateFile(context), publicFile())
        for (file in sources) {
            if (!file.exists()) continue
            return try {
                StoreCatalogJson.decode(file.readText())
            } catch (_: Exception) {
                emptyList()
            }
        }
        return readMediaStore(context)
    }

    fun write(context: Context, stores: List<Store>) {
        val json = StoreCatalogJson.encode(stores)
        privateFile(context).writeText(json)
        try {
            val pub = publicFile()
            pub.parentFile?.mkdirs()
            pub.writeText(json)
        } catch (_: Exception) {
        }
        writeMediaStore(context, json)
        context.contentResolver.notifyChange(Uri.parse(StoreCatalogIds.CONTENT_URI), null)
    }

    private fun readMediaStore(context: Context): List<Store> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
        val args = arrayOf(StoreCatalogIds.FILE_NAME)
        context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            val id = cursor.getLong(0)
            val item = Uri.withAppendedPath(uri, id.toString())
            val text = context.contentResolver.openInputStream(item)?.bufferedReader()?.readText().orEmpty()
            return try {
                StoreCatalogJson.decode(text)
            } catch (_: Exception) {
                emptyList()
            }
        }
        return emptyList()
    }

    private fun writeMediaStore(context: Context, json: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(StoreCatalogIds.FILE_NAME),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()) else null
        }
        val target = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, StoreCatalogIds.FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            },
        ) ?: return
        try {
            resolver.openOutputStream(target, "wt")?.use { it.write(json.toByteArray()) }
        } catch (_: Exception) {
        }
    }
}
