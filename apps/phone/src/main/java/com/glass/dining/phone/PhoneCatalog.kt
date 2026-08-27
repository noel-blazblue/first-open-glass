package com.glass.dining.phone

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.glass.dining.shared.catalog.StoreCatalogIds
import com.glass.dining.shared.catalog.StoreCatalogJson
import com.glass.dining.shared.model.Store
import java.io.File

object PhoneCatalog {
    private const val TAG = "GlassDiningPhone"

    fun load(context: Context): List<Store> {
        val fromProvider = readProvider(context)
        if (!fromProvider.isNullOrEmpty()) {
            Log.i(TAG, "catalog provider n=${fromProvider.size}")
            return fromProvider
        }
        val fromMedia = readMediaStore(context)
        if (fromMedia.isNotEmpty()) {
            Log.i(TAG, "catalog mediastore n=${fromMedia.size}")
            return fromMedia
        }
        val fromFile = readFiles(context)
        Log.i(TAG, "catalog file n=${fromFile.size}")
        return fromFile
    }

    fun uri(): Uri = Uri.parse(StoreCatalogIds.CONTENT_URI)

    private fun readProvider(context: Context): List<Store>? {
        return try {
            context.contentResolver.query(uri(), arrayOf(StoreCatalogIds.COLUMN_JSON), null, null, null)
                ?.use { cursor -> parseCursor(cursor) }
        } catch (error: Exception) {
            Log.w(TAG, "catalog provider failed: ${error.message}")
            null
        }
    }

    private fun parseCursor(cursor: Cursor): List<Store>? {
        if (!cursor.moveToFirst()) return emptyList()
        val index = cursor.getColumnIndex(StoreCatalogIds.COLUMN_JSON)
        if (index < 0) return emptyList()
        val json = cursor.getString(index).orEmpty()
        return try {
            StoreCatalogJson.decode(json)
        } catch (error: Exception) {
            Log.w(TAG, "catalog provider json failed: ${error.message}")
            emptyList()
        }
    }

    private fun readMediaStore(context: Context): List<Store> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        return try {
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(StoreCatalogIds.FILE_NAME),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return emptyList()
                val item = Uri.withAppendedPath(uri, cursor.getLong(0).toString())
                val text = context.contentResolver.openInputStream(item)
                    ?.bufferedReader()?.readText().orEmpty()
                StoreCatalogJson.decode(text)
            } ?: emptyList()
        } catch (error: Exception) {
            Log.w(TAG, "catalog mediastore failed: ${error.message}")
            emptyList()
        }
    }

    private fun readFiles(context: Context): List<Store> {
        val files = listOfNotNull(
            File(context.filesDir, StoreCatalogIds.FILE_NAME),
            context.getExternalFilesDir(null)?.let { File(it, StoreCatalogIds.FILE_NAME) },
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                StoreCatalogIds.FILE_NAME,
            ),
        )
        for (file in files) {
            if (!file.exists()) continue
            try {
                val stores = StoreCatalogJson.decode(file.readText())
                if (stores.isNotEmpty()) return stores
            } catch (error: Exception) {
                Log.w(TAG, "catalog ${file.name} failed: ${error.message}")
            }
        }
        return emptyList()
    }
}
