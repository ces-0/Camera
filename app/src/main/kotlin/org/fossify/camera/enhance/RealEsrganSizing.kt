package org.fossify.camera.enhance

import kotlin.math.sqrt

/**
 * Chooses the bitmap size fed into the 4x Real-ESRGAN model.
 *
 * When pre-shrink is on, a photo over about 1 megapixel is scaled down to
 * that budget first. The network then upscales the smaller image by 4.
 */
object RealEsrganSizing {
    const val PRE_SHRINK_MAX_PIXELS = 1_000_000
    const val UPSCALE = 4

    fun inputSize(width: Int, height: Int, preShrink: Boolean): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return 1 to 1
        }
        if (!preShrink || width.toLong() * height <= PRE_SHRINK_MAX_PIXELS) {
            return width to height
        }
        val scale = sqrt(PRE_SHRINK_MAX_PIXELS.toDouble() / (width.toDouble() * height.toDouble()))
        var targetWidth = (width * scale).toInt().coerceAtLeast(1)
        var targetHeight = (height * scale).toInt().coerceAtLeast(1)
        while (targetWidth.toLong() * targetHeight > PRE_SHRINK_MAX_PIXELS) {
            if (targetWidth >= targetHeight && targetWidth > 1) {
                targetWidth -= 1
            } else if (targetHeight > 1) {
                targetHeight -= 1
            } else {
                break
            }
        }
        return targetWidth to targetHeight
    }
}
