package org.fossify.camera.enhance

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.fossify.camera.R
import org.fossify.camera.activities.MainActivity
import org.fossify.camera.databinding.ActivityMainBinding
import org.fossify.camera.databinding.DialogEnhancePhotoBinding
import org.fossify.camera.databinding.DialogSaveEnhancedBinding
import org.fossify.camera.extensions.config
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getRealPathFromURI
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.BROADCAST_REFRESH_MEDIA
import org.fossify.commons.helpers.REFRESH_PATH
import org.fossify.commons.helpers.ensureBackgroundThread
import java.io.File
import java.util.Locale

/**
 * Review of the last still photo. The 4× action on that review runs [PhotoEnhanceRunner].
 * Closing the review cancels an enhance that is still running.
 */
class PhotoEnhanceUi(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val canOfferEnhance: () -> Boolean,
    private val currentPhoto: () -> Uri?,
) {
    private val runner = PhotoEnhanceRunner(activity, ::onEnhanceSaved)
    private var reviewUri: Uri? = null
    private var enhancedPath: String? = null
    private var latchedOriginal = false

    fun attach() {
        binding.enhanceLastPhoto.setOnClickListener {
            val uri = currentPhoto() ?: return@setOnClickListener
            showReview(uri)
        }
        binding.photoReview.setOnClickListener { hideReview() }
        binding.photoReviewEnhance.setOnClickListener { confirmAndEnhance() }
        binding.photoReviewCompare.setOnClickListener {
            if (enhancedPath == null) {
                return@setOnClickListener
            }
            latchedOriginal = !latchedOriginal
            binding.showOriginalLayer(latchedOriginal)
        }
        binding.photoReviewCompare.setOnTouchListener(::onCompareTouch)
    }

    fun onStillPhotoSaved(uri: Uri) {
        refreshActionButton()
        if (activity.config.showPreview) {
            showReview(uri)
        }
    }

    fun refreshActionButton() {
        val offer = canOfferEnhance() && currentPhoto() != null && binding.photoReview.isGone()
        val button = binding.enhanceLastPhoto
        if (!offer) {
            button.beGone()
            return
        }
        button.alpha = 1f
        button.isClickable = true
        button.beVisible()
    }

    fun isReviewVisible(): Boolean = binding.photoReview.isVisible()

    fun hideReview() {
        runner.cancel()
        reviewUri = null
        clearCompare()
        if (!binding.photoReview.isGone()) {
            Glide.with(activity).clear(binding.photoReviewImage)
            binding.photoReview.beGone()
        }
        refreshActionButton()
    }

    private fun showReview(uri: Uri) {
        if (activity.isDestroyed) {
            return
        }
        reviewUri = uri
        runner.cancelled = false
        clearCompare()
        binding.photoReview.beVisible()
        binding.photoReview.bringToFront()
        binding.enhanceLastPhoto.beGone()
        Glide.with(activity)
            .load(uri)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(binding.photoReviewImage)
    }

    private fun confirmAndEnhance() {
        val uri = reviewUri ?: return
        if (runner.isDialogShowing()) {
            return
        }
        val source = stillPhotoSource(activity, uri)
        val suggested = EnhancedPhotoPaths.suggest(activity, source)
        val dialogBinding = DialogSaveEnhancedBinding.inflate(activity.layoutInflater)
        dialogBinding.enhancedPhotoName.setText(suggested.name)
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.save) { _, _ ->
                val typed = dialogBinding.enhancedPhotoName.text?.toString().orEmpty()
                val name = sanitizedEnhancedName(typed, suggested.name)
                val folder = suggested.parentFile ?: EnhancedPhotoPaths.folderFor(activity, source)
                runner.start(source, File(folder, name).absolutePath)
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(dialogBinding.root, this, R.string.enhance_photo)
            }
    }

    private fun onEnhanceSaved(destPath: String) {
        val original = reviewUri ?: return
        enhancedPath = destPath
        latchedOriginal = false
        binding.showOriginalLayer(false)
        binding.photoReviewCompare.beVisible()
        val enhanced: Any = if (File(destPath).isFile) File(destPath) else destPath
        Glide.with(activity)
            .load(original)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(binding.photoReviewOriginal)
        Glide.with(activity)
            .load(enhanced)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(binding.photoReviewImage)
    }

    private fun onCompareTouch(view: View, event: MotionEvent): Boolean {
        if (enhancedPath == null) {
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                binding.showOriginalLayer(true)
                true
            }
            MotionEvent.ACTION_UP -> {
                view.isPressed = false
                val elapsed = event.eventTime - event.downTime
                val longPress = ViewConfiguration.getLongPressTimeout().toLong()
                if (elapsed < longPress) {
                    view.performClick()
                } else {
                    latchedOriginal = false
                    binding.showOriginalLayer(false)
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                binding.showOriginalLayer(latchedOriginal)
                true
            }
            else -> true
        }
    }

    private fun clearCompare() {
        enhancedPath = null
        latchedOriginal = false
        binding.showOriginalLayer(false)
        binding.photoReviewCompare.beGone()
        if (!activity.isDestroyed) {
            Glide.with(activity).clear(binding.photoReviewOriginal)
        }
    }
}

private fun ActivityMainBinding.showOriginalLayer(showOriginal: Boolean) {
    photoReviewCompare.isActivated = showOriginal
    photoReviewOriginal.visibility = if (showOriginal) View.VISIBLE else View.INVISIBLE
}

class PhotoEnhanceRunner(
    private val activity: MainActivity,
    private val onSaved: (String) -> Unit,
) {
    var cancelled = false
    private var enhanceDialog: AlertDialog? = null

    fun isDialogShowing(): Boolean = enhanceDialog?.isShowing == true

    fun cancel() {
        cancelled = true
        dismissEnhanceDialog()
    }

    fun start(sourcePath: String, destPath: String) {
        cancelled = false
        val dialogBinding = DialogEnhancePhotoBinding.inflate(activity.layoutInflater)
        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel) { _, _ ->
                cancelled = true
            }
            .apply {
                activity.setupDialogStuff(dialogBinding.root, this, R.string.enhance_photo) { dialog ->
                    enhanceDialog = dialog
                    dialog.setCanceledOnTouchOutside(false)
                    ensureBackgroundThread {
                        runEnhance(sourcePath, destPath, dialogBinding)
                    }
                }
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runEnhance(sourcePath: String, destPath: String, dialogBinding: DialogEnhancePhotoBinding) {
        var enhanced: Bitmap? = null
        try {
            val prepared = PhotoBitmapPrep.load(activity, sourcePath, activity.config.shrinkBeforeEnhance)
            enhanced = upscale(prepared.bitmap, dialogBinding)
            if (cancelled || activity.isDestroyed) {
                recycleQuietly(enhanced)
                activity.runOnUiThread { dismissEnhanceDialog() }
                return
            }
            val saved = activity.saveEnhancedPhoto(prepared.exif, destPath, enhanced)
            recycleQuietly(enhanced)
            activity.runOnUiThread { finishSave(saved, destPath) }
        } catch (e: PhotoEnhanceException) {
            recycleQuietly(enhanced)
            activity.runOnUiThread { showEnhanceFailure(e) }
        } catch (_: OutOfMemoryError) {
            recycleQuietly(enhanced)
            activity.runOnUiThread {
                dismissEnhanceDialog()
                activity.toast(org.fossify.commons.R.string.out_of_memory_error)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Enhancing the photo failed", e)
            recycleQuietly(enhanced)
            activity.runOnUiThread {
                dismissEnhanceDialog()
                activity.showErrorToast(e)
            }
        }
    }

    private fun upscale(source: Bitmap, dialogBinding: DialogEnhancePhotoBinding): Bitmap {
        try {
            return RealEsrganEnhancer.enhance(
                context = activity,
                source = source,
                isCancelled = { cancelled || activity.isDestroyed },
                onProgress = { percent -> publishProgress(dialogBinding, percent) },
            )
        } finally {
            recycleQuietly(source)
        }
    }

    private fun publishProgress(dialogBinding: DialogEnhancePhotoBinding, percent: Int) {
        activity.runOnUiThread {
            if (enhanceDialog?.isShowing == true) {
                dialogBinding.enhancePhotoProgress.isIndeterminate = false
                dialogBinding.enhancePhotoProgress.progress = percent
            }
        }
    }

    private fun showEnhanceFailure(error: PhotoEnhanceException) {
        dismissEnhanceDialog()
        if (error.reason == PhotoEnhanceException.Reason.CANCELLED) {
            return
        }
        val message = if (error.reason == PhotoEnhanceException.Reason.TOO_LARGE) {
            R.string.enhance_photo_too_large
        } else {
            R.string.enhance_photo_failed
        }
        activity.toast(message)
    }

    private fun finishSave(saved: Boolean, destPath: String) {
        dismissEnhanceDialog()
        if (!saved) {
            if (!cancelled) {
                activity.toast(R.string.enhance_photo_failed)
            }
            return
        }
        activity.rescanPaths(arrayListOf(destPath)) {
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    activity.toast(R.string.enhance_photo_saved)
                    onSaved(destPath)
                    Intent(BROADCAST_REFRESH_MEDIA).apply {
                        putExtra(REFRESH_PATH, destPath)
                        `package` = "org.fossify.gallery"
                        activity.sendBroadcast(this)
                    }
                }
            }
        }
    }

    private fun dismissEnhanceDialog() {
        enhanceDialog?.dismiss()
        enhanceDialog = null
    }

    private fun recycleQuietly(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

internal fun stillPhotoSource(activity: MainActivity, uri: Uri): String {
    if (uri.scheme == FILE_SCHEME) {
        return uri.path ?: uri.toString()
    }
    val real = runCatching { activity.getRealPathFromURI(uri) }.getOrNull()
    if (!real.isNullOrEmpty() && File(real).isFile) {
        return real
    }
    return uri.toString()
}

internal fun sanitizedEnhancedName(raw: String, fallback: String): String {
    val cleaned = raw.trim().replace("/", "").replace("\\", "")
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
        return fallback
    }
    val lower = cleaned.lowercase(Locale.US)
    val hasKnownExtension = lower.endsWith(JPG_SUFFIX) || lower.endsWith(JPEG_SUFFIX) || lower.endsWith(PNG_SUFFIX)
    return if (hasKnownExtension) cleaned else cleaned + JPG_SUFFIX
}

private const val FILE_SCHEME = "file"
private const val JPG_SUFFIX = ".jpg"
private const val JPEG_SUFFIX = ".jpeg"
private const val PNG_SUFFIX = ".png"
private const val LOG_TAG = "PhotoEnhance"
