package org.fossify.camera.enhance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

data class PreparedPhoto(
    val bitmap: Bitmap,
    val exif: ExifInterface?,
)

/**
 * Loads a gallery photo as straight 8-bit ARGB_8888.
 * RGB_565 and RGBA_F16 are converted so the enhancer never keeps 16-bit color.
 */
object PhotoBitmapPrep {
    fun load(context: Context, path: String, preShrink: Boolean): PreparedPhoto {
        val exif = readExif(context, path)
        val decoded = decode(context, path, preShrink)
        val argb = decoded.ensureArgb8888()
        val target = RealEsrganSizing.inputSize(argb.width, argb.height, preShrink)
        val scaled = PhotoBitmapTransform.scaleTo(argb, target.first, target.second)
        return PreparedPhoto(scaled, exif)
    }

    private fun decode(context: Context, path: String, preShrink: Boolean): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val modern = decodeWithImageDecoder(context, path, preShrink)
            if (modern != null) {
                return modern
            }
        }
        return decodeWithBitmapFactory(context, path, preShrink)
            ?: throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, path: String, preShrink: Boolean): Bitmap? {
        return try {
            val source = imageSource(context, path) ?: return null
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                decoder.setUnpremultipliedRequired(true)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val target = RealEsrganSizing.inputSize(info.size.width, info.size.height, preShrink)
                if (target.first != info.size.width || target.second != info.size.height) {
                    decoder.setTargetSize(target.first, target.second)
                }
            }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "ImageDecoder could not open the photo", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun imageSource(context: Context, path: String): ImageDecoder.Source? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        return if (path.startsWith("content:")) {
            ImageDecoder.createSource(context.contentResolver, Uri.parse(path))
        } else {
            val file = File(path)
            if (!file.isFile) null else ImageDecoder.createSource(file)
        }
    }

    private fun decodeWithBitmapFactory(context: Context, path: String, preShrink: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, path).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val orientation = readExif(context, path)?.orientationOrNormal() ?: ExifInterface.ORIENTATION_NORMAL
        val oriented = PhotoBitmapTransform.orientedSize(bounds.outWidth, bounds.outHeight, orientation)
        val target = RealEsrganSizing.inputSize(oriented.first, oriented.second, preShrink)
        val rawTarget = PhotoBitmapTransform.unorientedSize(target.first, target.second, orientation)
        val options = BitmapFactory.Options().apply {
            inSampleSize = subsampleFactor(bounds.outWidth, bounds.outHeight, rawTarget.first, rawTarget.second)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inDither = false
            inScaled = false
            inPremultiplied = false
        }
        val decoded = openStream(context, path).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return PhotoBitmapTransform.applyOrientation(decoded.ensureArgb8888(), orientation)
    }

    private fun readExif(context: Context, path: String): ExifInterface? {
        return try {
            if (path.startsWith("content:")) {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { ExifInterface(it) }
            } else if (File(path).isFile) {
                ExifInterface(path)
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "Could not read photo details", e)
            null
        }
    }

    private fun openStream(context: Context, path: String): InputStream {
        return if (path.startsWith("content:")) {
            context.contentResolver.openInputStream(Uri.parse(path))
                ?: throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED)
        } else {
            FileInputStream(File(path))
        }
    }

    private fun ExifInterface.orientationOrNormal(): Int {
        return getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }

    private const val LOG_TAG = "PhotoEnhance"
}

internal fun subsampleFactor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    var factor = 1
    var next = factor * 2
    while (sourceWidth / next >= targetWidth && sourceHeight / next >= targetHeight && next > 0) {
        factor = next
        next = factor * 2
    }
    return factor
}

internal fun Bitmap.ensureArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) {
        return this
    }
    val converted = copy(Bitmap.Config.ARGB_8888, false) ?: return this
    if (converted !== this) {
        recycle()
    }
    return converted
}
