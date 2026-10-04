package org.fossify.camera.enhance

class PhotoEnhanceException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(cause) {
    enum class Reason {
        FAILED,
        TOO_LARGE,
        CANCELLED,
    }
}
