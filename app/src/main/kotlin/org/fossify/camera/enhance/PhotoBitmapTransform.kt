package org.fossify.camera.enhance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface

internal object PhotoBitmapTransform {
    fun orientedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
        return if (swapsAxes(orientation)) height to width else width to height
    }

    fun unorientedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
        return if (swapsAxes(orientation)) height to width else width to height
    }

    fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = orientationMatrix(orientation) ?: return source
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) {
            source.recycle()
        }
        return rotated.ensureArgb8888()
    }

    fun scaleTo(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val alreadySized = source.width == targetWidth && source.height == targetHeight
        if (alreadySized && source.config == Bitmap.Config.ARGB_8888) {
            return source
        }
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isDither = false
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(0, 0, targetWidth, targetHeight),
            paint,
        )
        if (source !== output) {
            source.recycle()
        }
        return output
    }

    private fun swapsAxes(orientation: Int): Boolean {
        return orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(ROTATE_RIGHT)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(ROTATE_HALF)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(ROTATE_LEFT)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(ROTATE_RIGHT)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(ROTATE_LEFT)
                matrix.preScale(-1f, 1f)
            }
            else -> return null
        }
        return matrix
    }

    private const val ROTATE_RIGHT = 90f
    private const val ROTATE_HALF = 180f
    private const val ROTATE_LEFT = 270f
}
