package com.autoalbum

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class Photo(val uri: Uri, val path: String?)

object Sorter {

    val albums = listOf("Documents", "Family", "Personal", "Food", "Nature", "Pets")

    private fun eligible(rel: String): Boolean {
        val inScope = rel.startsWith("DCIM/") || rel.startsWith("Pictures/") || rel.startsWith("Download/")
        if (!inScope) return false
        if (rel.contains("Screenshot", ignoreCase = true)) return false
        if (albums.any { rel.startsWith("Pictures/$it/") }) return false // already sorted
        return true
    }

    /** Returns (highest DATE_ADDED seen, eligible photos added after sinceSec). */
    fun query(context: Context, sinceSec: Long): Pair<Long, List<Photo>> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )
        val list = mutableListOf<Photo>()
        var maxAdded = sinceSec
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DATE_ADDED} > ?",
            arrayOf(sinceSec.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val relCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                maxAdded = maxOf(maxAdded, c.getLong(addedCol))
                val rel = c.getString(relCol) ?: continue
                if (!eligible(rel)) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                )
                list.add(Photo(uri, c.getString(dataCol)))
            }
        }
        return maxAdded to list
    }

    /** Classifies and moves one photo. Returns the album name, or null if left in place / failed. */
    suspend fun process(context: Context, photo: Photo): String? {
        val category = Classifier.classify(context, photo.uri) ?: return null
        return if (move(context, photo, category)) category else null
    }

    private suspend fun move(context: Context, photo: Photo, category: String): Boolean =
        withContext(Dispatchers.IO) {
            // Preferred: change the folder in MediaStore
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$category")
                }
                if (context.contentResolver.update(photo.uri, values, null, null) > 0) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // fall through to file move
            }
            // Fallback: plain file move (needs All files access)
            try {
                val src = File(photo.path ?: return@withContext false)
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    category
                )
                dir.mkdirs()
                var dest = File(dir, src.name)
                var n = 1
                while (dest.exists()) {
                    dest = File(dir, "${src.nameWithoutExtension}_$n.${src.extension}")
                    n++
                }
                if (src.renameTo(dest)) {
                    MediaScannerConnection.scanFile(
                        context, arrayOf(src.absolutePath, dest.absolutePath), null, null
                    )
                    true
                } else false
            } catch (e: Exception) {
                false
            }
        }
}
