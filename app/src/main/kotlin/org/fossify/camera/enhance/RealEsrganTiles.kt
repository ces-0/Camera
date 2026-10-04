package org.fossify.camera.enhance

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object RealEsrganTiles {
    fun paint(
        output: Bitmap,
        pixels: IntArray,
        width: Int,
        height: Int,
        env: OrtEnvironment,
        ortSession: OrtSession,
        sessionLock: Any,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ) {
        if (width <= TILE_SIZE && height <= TILE_SIZE) {
            paintWhole(output, pixels, width, height, env, ortSession, sessionLock, isCancelled, onProgress)
            return
        }
        paintTiled(output, pixels, width, height, env, ortSession, sessionLock, isCancelled, onProgress)
    }

    private fun paintWhole(
        output: Bitmap,
        pixels: IntArray,
        width: Int,
        height: Int,
        env: OrtEnvironment,
        ortSession: OrtSession,
        sessionLock: Any,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ) {
        if (isCancelled()) {
            throw PhotoEnhanceException(PhotoEnhanceException.Reason.CANCELLED)
        }
        val input = FloatArray(RGB_CHANNELS * width * height)
        fillInput(pixels, width, height, 0, 0, width, height, input)
        val inferred = infer(env, ortSession, sessionLock, input, height, width)
        writeFull(output, inferred, width, height)
        onProgress(PROGRESS_SCALE)
    }

    private fun paintTiled(
        output: Bitmap,
        pixels: IntArray,
        width: Int,
        height: Int,
        env: OrtEnvironment,
        ortSession: OrtSession,
        sessionLock: Any,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ) {
        val tilesX = ceil(width / TILE_SIZE.toFloat()).toInt().coerceAtLeast(1)
        val tilesY = ceil(height / TILE_SIZE.toFloat()).toInt().coerceAtLeast(1)
        val window = TILE_SIZE + TILE_PAD * 2
        val input = FloatArray(RGB_CHANNELS * window * window)
        val total = tilesX * tilesY
        var done = 0
        for (tileY in 0 until tilesY) {
            for (tileX in 0 until tilesX) {
                if (isCancelled()) {
                    throw PhotoEnhanceException(PhotoEnhanceException.Reason.CANCELLED)
                }
                val originX = tileX * TILE_SIZE - TILE_PAD
                val originY = tileY * TILE_SIZE - TILE_PAD
                fillInput(pixels, width, height, originX, originY, window, window, input)
                val inferred = infer(env, ortSession, sessionLock, input, window, window)
                writeTile(output, inferred, window, tileX, tileY, width, height)
                done += 1
                onProgress(done * PROGRESS_SCALE / total)
            }
        }
    }

    private fun fillInput(
        pixels: IntArray,
        width: Int,
        height: Int,
        originX: Int,
        originY: Int,
        windowWidth: Int,
        windowHeight: Int,
        input: FloatArray,
    ) {
        var plane = 0
        for (channel in 0 until RGB_CHANNELS) {
            val shift = CHANNEL_SHIFTS[channel]
            for (y in 0 until windowHeight) {
                val sampleY = (originY + y).coerceIn(0, height - 1)
                val row = sampleY * width
                for (x in 0 until windowWidth) {
                    val sampleX = (originX + x).coerceIn(0, width - 1)
                    input[plane] = straightChannel(pixels[row + sampleX], shift)
                    plane += 1
                }
            }
        }
    }

    private fun straightChannel(pixel: Int, shift: Int): Float {
        val alpha = (pixel ushr ALPHA_SHIFT) and COLOR_LEVELS
        val channel = (pixel shr shift) and COLOR_LEVELS
        if (alpha == COLOR_LEVELS) {
            return channel / COLOR_LEVELS.toFloat()
        }
        val composited = channel * alpha + COLOR_LEVELS * (COLOR_LEVELS - alpha)
        return composited / (COLOR_LEVELS * COLOR_LEVELS).toFloat()
    }

    private fun infer(
        env: OrtEnvironment,
        ortSession: OrtSession,
        sessionLock: Any,
        input: FloatArray,
        height: Int,
        width: Int,
    ): FloatArray {
        val shape = longArrayOf(1, RGB_CHANNELS.toLong(), height.toLong(), width.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
        try {
            val results = synchronized(sessionLock) {
                ortSession.run(mapOf(INPUT_NAME to tensor))
            }
            results.use { result ->
                val output = result[0] as OnnxTensor
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                val scale = RealEsrganSizing.UPSCALE
                val expected = RGB_CHANNELS * height * scale * width * scale
                if (values.size != expected) {
                    throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED)
                }
                return values
            }
        } catch (e: OrtException) {
            throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED, e)
        } finally {
            tensor.close()
        }
    }

    private fun writeFull(output: Bitmap, values: FloatArray, width: Int, height: Int) {
        val outWidth = width * RealEsrganSizing.UPSCALE
        val outHeight = height * RealEsrganSizing.UPSCALE
        val plane = outWidth * outHeight
        val pixels = IntArray(plane)
        for (y in 0 until outHeight) {
            val row = y * outWidth
            for (x in 0 until outWidth) {
                val offset = row + x
                pixels[offset] = packPixel(
                    quantize(values[offset]),
                    quantize(values[plane + offset]),
                    quantize(values[plane * BLUE_PLANE + offset]),
                )
            }
        }
        output.setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
    }

    private fun writeTile(
        output: Bitmap,
        values: FloatArray,
        window: Int,
        tileX: Int,
        tileY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        val outWindow = window * RealEsrganSizing.UPSCALE
        val plane = outWindow * outWindow
        val crop = TILE_PAD * RealEsrganSizing.UPSCALE
        val imageX = tileX * TILE_SIZE
        val imageY = tileY * TILE_SIZE
        val copyW = minOf(TILE_SIZE, sourceWidth - imageX) * RealEsrganSizing.UPSCALE
        val copyH = minOf(TILE_SIZE, sourceHeight - imageY) * RealEsrganSizing.UPSCALE
        val pixels = IntArray(copyW * copyH)
        for (y in 0 until copyH) {
            val sourceRow = (crop + y) * outWindow + crop
            val destRow = y * copyW
            for (x in 0 until copyW) {
                val offset = sourceRow + x
                pixels[destRow + x] = packPixel(
                    quantize(values[offset]),
                    quantize(values[plane + offset]),
                    quantize(values[plane * BLUE_PLANE + offset]),
                )
            }
        }
        output.setPixels(
            pixels,
            0,
            copyW,
            imageX * RealEsrganSizing.UPSCALE,
            imageY * RealEsrganSizing.UPSCALE,
            copyW,
            copyH,
        )
    }

    private fun packPixel(red: Int, green: Int, blue: Int): Int {
        return (COLOR_LEVELS shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }

    private fun quantize(value: Float): Int {
        return (value * COLOR_LEVELS).roundToInt().coerceIn(0, COLOR_LEVELS)
    }

    private const val INPUT_NAME = "input"
    private const val TILE_SIZE = 192
    private const val TILE_PAD = 16
    private const val RGB_CHANNELS = 3
    private const val COLOR_LEVELS = 255
    private const val PROGRESS_SCALE = 100
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val ALPHA_SHIFT = 24
    private const val BLUE_PLANE = 2
    private val CHANNEL_SHIFTS = intArrayOf(RED_SHIFT, GREEN_SHIFT, 0)
}
