package org.fossify.camera.enhance

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.fossify.camera.extensions.config
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getFileOutputStreamSync
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.helpers.isQPlus
import java.io.File
import java.io.IOException
import java.util.Locale

object EnhancedPhotoPaths {
    fun suggest(context: Context, sourcePath: String): File {
        val folder = folderFor(context, sourcePath)
        val sourceName = displayName(context, sourcePath)
        val base = sourceName.substringBeforeLast('.').ifEmpty { DEFAULT_NAME }
        val extension = if (sourceName.lowercase(Locale.US).endsWith(".$PNG_EXTENSION")) {
            PNG_EXTENSION
        } else {
            JPEG_EXTENSION
        }
        var index = 0
        while (index < MAX_NAME_TRIES) {
            val suffix = if (index == 0) "" else "_$index"
            val candidate = File(folder, "${base}_enhanced$suffix.$extension")
            if (!candidate.exists()) {
                return candidate
            }
            index += 1
        }
        return File(folder, "${base}_enhanced_${System.currentTimeMillis()}.$extension")
    }

    fun folderFor(context: Context, sourcePath: String): File {
        if (!sourcePath.startsWith("content:") && !sourcePath.startsWith("file:")) {
            val parent = File(sourcePath).parentFile
            if (parent != null && (parent.isDirectory || parent.mkdirs())) {
                return parent
            }
        }
        val folder = File(context.config.savePhotosFolder)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    private fun displayName(context: Context, sourcePath: String): String {
        if (!sourcePath.startsWith("content:")) {
            return File(sourcePath).name.ifEmpty { "$DEFAULT_NAME.$JPEG_EXTENSION" }
        }
        val cursor = context.contentResolver.query(
            Uri.parse(sourcePath),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val name = it.getString(0)
                if (!name.isNullOrBlank()) {
                    return name
                }
            }
        }
        return "$DEFAULT_NAME.$JPEG_EXTENSION"
    }

    private const val DEFAULT_NAME = "photo"
    private const val PNG_EXTENSION = "png"
    private const val JPEG_EXTENSION = "jpg"
    private const val MAX_NAME_TRIES = 1000
}

fun BaseSimpleActivity.saveEnhancedPhoto(
    sourceExif: ExifInterface?,
    destPath: String,
    bitmap: Bitmap,
): Boolean {
    if (bitmap.config != Bitmap.Config.ARGB_8888) {
        return false
    }
    val parent = File(destPath).parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }
    if (writeToPath(destPath, bitmap)) {
        EnhancedExif.copyNonDimensionAttributes(sourceExif, destPath)
        return true
    }
    return saveViaMediaStore(destPath, bitmap, sourceExif)
}

private fun BaseSimpleActivity.writeToPath(destPath: String, bitmap: Bitmap): Boolean {
    val stream = getFileOutputStreamSync(destPath, destPath.getMimeType()) ?: return false
    return try {
        stream.use { output ->
            val format = compressionFormat(destPath)
            val quality = if (format == Bitmap.CompressFormat.PNG) PNG_QUALITY else JPEG_QUALITY
            bitmap.compress(format, quality, output)
        }
    } catch (e: IOException) {
        Log.w(LOG_TAG, "Could not write enhanced photo", e)
        false
    }
}

private fun BaseSimpleActivity.saveViaMediaStore(
    destPath: String,
    bitmap: Bitmap,
    sourceExif: ExifInterface?,
): Boolean {
    val file = File(destPath)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, destPath.getMimeType())
        if (isQPlus()) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(file))
            put(MediaStore.MediaColumns.IS_PENDING, IS_PENDING)
        }
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val uri = contentResolver.insert(collection, values) ?: return false
    val saved = try {
        contentResolver.openOutputStream(uri)?.use { output ->
            val format = compressionFormat(destPath)
            val quality = if (format == Bitmap.CompressFormat.PNG) PNG_QUALITY else JPEG_QUALITY
            bitmap.compress(format, quality, output)
        } ?: false
    } catch (e: IOException) {
        Log.w(LOG_TAG, "Could not write enhanced photo", e)
        false
    }
    if (!saved) {
        contentResolver.delete(uri, null, null)
        return false
    }
    if (isQPlus()) {
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, NOT_PENDING) }
        contentResolver.update(uri, done, null, null)
    }
    copyExifToUri(sourceExif, uri)
    return true
}

private fun BaseSimpleActivity.copyExifToUri(sourceExif: ExifInterface?, uri: Uri) {
    if (sourceExif == null) {
        return
    }
    try {
        contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
            val destination = ExifInterface(descriptor.fileDescriptor)
            EnhancedExif.copyAttributes(sourceExif, destination)
            destination.saveAttributes()
        }
    } catch (e: IOException) {
        Log.w(LOG_TAG, "Could not copy photo details", e)
    }
}

private fun relativePathFor(file: File): String {
    val parent = file.parentFile?.absolutePath ?: return Environment.DIRECTORY_DCIM
    val roots = listOf(
        Environment.DIRECTORY_DCIM to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        Environment.DIRECTORY_PICTURES to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    )
    roots.forEach { (name, root) ->
        val rootPath = root.absolutePath
        if (parent == rootPath) {
            return name
        }
        if (parent.startsWith(rootPath + File.separator)) {
            return name + parent.removePrefix(rootPath)
        }
    }
    return Environment.DIRECTORY_DCIM
}

private fun compressionFormat(path: String): Bitmap.CompressFormat {
    return if (path.lowercase(Locale.US).endsWith(".$PNG_EXTENSION")) {
        Bitmap.CompressFormat.PNG
    } else {
        Bitmap.CompressFormat.JPEG
    }
}

private const val JPEG_QUALITY = 95
private const val PNG_QUALITY = 100
private const val PNG_EXTENSION = "png"
private const val IS_PENDING = 1
private const val NOT_PENDING = 0
private const val LOG_TAG = "PhotoEnhance"
