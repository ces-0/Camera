package org.fossify.camera.enhance

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.IOException

/**
 * On-device 4x super-resolution using the official Real-ESRGAN
 * `realesr-general-x4v3` weights (BSD-3-Clause). Inference runs locally with
 * ONNX Runtime (MIT). See assets/models/NOTICE.
 *
 * Tiles are a fixed size so the runtime compiles one input shape. Border
 * context is clamped from the source. Output pixels are written as 8-bit
 * ARGB_8888, never RGB_565.
 */
object RealEsrganEnhancer {
    private val sessionLock = Any()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun enhance(
        context: Context,
        source: Bitmap,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): Bitmap {
        if (source.config != Bitmap.Config.ARGB_8888) {
            throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED)
        }
        if (isCancelled()) {
            throw PhotoEnhanceException(PhotoEnhanceException.Reason.CANCELLED)
        }
        val width = source.width
        val height = source.height
        assertOutputFits(width, height)
        val runtime = openSession(context.applicationContext)
        return runTiles(source, runtime.first, runtime.second, isCancelled, onProgress)
    }

    private fun openSession(context: Context): Pair<OrtEnvironment, OrtSession> {
        synchronized(sessionLock) {
            val existingEnv = environment
            val existingSession = session
            if (existingEnv != null && existingSession != null) {
                return existingEnv to existingSession
            }
            try {
                val createdEnv = OrtEnvironment.getEnvironment()
                val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
                val createdSession = try {
                    createdEnv.createSession(model, sessionOptions())
                } catch (e: OrtException) {
                    Log.i(LOG_TAG, "Falling back to the default CPU runtime", e)
                    createdEnv.createSession(model, cpuSessionOptions())
                }
                environment = createdEnv
                session = createdSession
                return createdEnv to createdSession
            } catch (e: OrtException) {
                throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED, e)
            } catch (e: IOException) {
                throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED, e)
            }
        }
    }

    private fun sessionOptions(): OrtSession.SessionOptions {
        val xnnpack = cpuSessionOptions()
        return try {
            xnnpack.addXnnpack(mapOf("intra_op_num_threads" to INTRA_OP_THREADS.toString()))
            xnnpack
        } catch (e: OrtException) {
            Log.i(LOG_TAG, "XNNPACK is unavailable, using the default CPU runtime", e)
            xnnpack.close()
            cpuSessionOptions()
        }
    }

    private fun cpuSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(INTRA_OP_THREADS)
        }
    }

    private fun assertOutputFits(width: Int, height: Int) {
        val outWidth = width.toLong() * RealEsrganSizing.UPSCALE
        val outHeight = height.toLong() * RealEsrganSizing.UPSCALE
        if (outWidth > Int.MAX_VALUE || outHeight > Int.MAX_VALUE) {
            throw PhotoEnhanceException(PhotoEnhanceException.Reason.TOO_LARGE)
        }
        val bytes = outWidth * outHeight * BYTES_PER_PIXEL
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val available = runtime.maxMemory() - used
        if (bytes <= 0L || bytes + OUTPUT_RESERVE_BYTES > available) {
            throw PhotoEnhanceException(PhotoEnhanceException.Reason.TOO_LARGE)
        }
    }

    private fun runTiles(
        source: Bitmap,
        env: OrtEnvironment,
        ortSession: OrtSession,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = Bitmap.createBitmap(
            width * RealEsrganSizing.UPSCALE,
            height * RealEsrganSizing.UPSCALE,
            Bitmap.Config.ARGB_8888,
        )
        if (output.config != Bitmap.Config.ARGB_8888) {
            output.recycle()
            throw PhotoEnhanceException(PhotoEnhanceException.Reason.FAILED)
        }
        var completed = false
        try {
            RealEsrganTiles.paint(output, pixels, width, height, env, ortSession, sessionLock, isCancelled, onProgress)
            completed = true
            return output
        } finally {
            if (!completed && !output.isRecycled) {
                output.recycle()
            }
        }
    }

    private const val LOG_TAG = "PhotoEnhance"
    private const val MODEL_ASSET = "models/realesr-general-x4v3.onnx"
    private const val INTRA_OP_THREADS = 4
    private const val BYTES_PER_PIXEL = 4L
    private const val OUTPUT_RESERVE_BYTES = 16L * 1024L * 1024L
}
