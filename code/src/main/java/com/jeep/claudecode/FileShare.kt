package com.jeep.claudecode

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The userland's files lent to other apps (files page: 打开方式, 分享): content://<app>.share/<path>, read only, and only
 * to an app this one granted the uri to (the provider is not exported). Only files under the userland or phone storage.
 */
class FileShare : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val path = uri.path ?: throw FileNotFoundException("no path")
        val f = File(path).canonicalFile
        val c = context ?: throw FileNotFoundException("no context")
        val roots = listOf(File(c.filesDir.parentFile, ".").canonicalFile, Environment.getExternalStorageDirectory().canonicalFile, File("/storage"))
        if (roots.none { f.path.startsWith(it.path + "/") }) throw FileNotFoundException("not shared")
        if (!f.isFile) throw FileNotFoundException(f.path)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val f = try {
            file(uri)
        } catch (e: IOException) {
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        }
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String = FileKit.mime(uri.lastPathSegment ?: "") ?: "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    companion object {
        /** The uri another app is given for a file of this one's (the host's path, not the userland's). */
        fun uri(c: Context, f: File): Uri = Uri.Builder().scheme("content").authority(c.packageName + ".share").path(f.path).build()
    }
}
