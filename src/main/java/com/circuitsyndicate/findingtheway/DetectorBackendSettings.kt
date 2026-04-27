package com.circuitsyndicate.findingtheway

import android.content.Context

object DetectorBackendSettings {

    private const val PREF_NAME = "detector_settings"
    private const val KEY_BACKEND = "detector_backend"
    private const val ONNX_MODEL_FILE = "yolov8m-oiv7.onnx"
    private val ONNX_MODEL_FALLBACK_FILES = listOf(
        "yolov8s-oiv7.onnx",
        "yolov8n-oiv7.onnx"
    )

    private val DEFAULT_BACKEND = ObjectDetector.Backend.ONNX

    fun getBackend(context: Context): ObjectDetector.Backend {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, DEFAULT_BACKEND.name)
            ?: DEFAULT_BACKEND.name

        return runCatching { ObjectDetector.Backend.valueOf(raw) }
            .getOrElse { DEFAULT_BACKEND }
    }

    fun setBackend(context: Context, backend: ObjectDetector.Backend) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND, backend.name)
            .apply()
    }

    fun isOnnxModelAvailable(context: Context): Boolean {
        val candidates = listOf(ONNX_MODEL_FILE) + ONNX_MODEL_FALLBACK_FILES
        return candidates.any { modelAsset ->
            runCatching {
                context.assets.open(modelAsset).use { }
                true
            }.getOrDefault(false)
        }
    }

    fun sanitizeBackendForAvailability(requested: ObjectDetector.Backend): ObjectDetector.Backend {
        // ONNX is the only supported detector backend in the migrated app path.
        return if (requested == ObjectDetector.Backend.ONNX) {
            ObjectDetector.Backend.ONNX
        } else {
            ObjectDetector.Backend.ONNX
        }
    }

    fun sanitizeBackendForAvailability(
        context: Context,
        requested: ObjectDetector.Backend
    ): ObjectDetector.Backend {
        return sanitizeBackendForAvailability(requested)
    }
}
