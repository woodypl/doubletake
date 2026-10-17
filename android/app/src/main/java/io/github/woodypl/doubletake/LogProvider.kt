package io.github.woodypl.doubletake

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the one shared log snapshot read-only to the app the user shares
 * it with (a minimal stand-in for AndroidX's FileProvider).
 */
class LogProvider : ContentProvider() {
    companion object {
        private const val FILE_NAME = "doubletake-android.log"

        private fun authority(context: Context) = "${context.packageName}.logs"

        private fun file(context: Context) = File(File(context.cacheDir, "shared").apply { mkdirs() }, FILE_NAME)

        /** Writes text to the shared snapshot and returns its content URI. */
        fun writeSnapshot(context: Context, text: String): Uri {
            file(context).writeText(text)
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(FILE_NAME).build()
        }
    }

    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        if (uri.lastPathSegment != FILE_NAME) throw FileNotFoundException(uri.toString())
        return file(ctx)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "text/plain"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> FILE_NAME
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
