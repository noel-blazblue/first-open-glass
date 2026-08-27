package com.glass.dining.store

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.glass.dining.shared.catalog.StoreCatalogIds
import com.glass.dining.shared.catalog.StoreCatalogJson

class StoreCatalogProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(StoreCatalogIds.COLUMN_JSON))
        val json = StoreCatalogJson.encode(CatalogFiles.read(ctx))
        val cursor = MatrixCursor(arrayOf(StoreCatalogIds.COLUMN_JSON))
        cursor.addRow(arrayOf(json))
        cursor.setNotificationUri(ctx.contentResolver, Uri.parse(StoreCatalogIds.CONTENT_URI))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.glass.stores"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
