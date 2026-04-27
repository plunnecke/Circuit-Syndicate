package com.circuitsyndicate.findingtheway

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble

/**
 * Camera intrinsics + distortion profile used for undistortion and
 * geometry-aware estimation. Values are referenced to a base resolution and
 * scaled to runtime frame size.
 */
data class CameraCalibrationProfile(
    val profileName: String,
    val referenceWidth: Int,
    val referenceHeight: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val distCoeffs: DoubleArray,
    val enableUndistortion: Boolean,
    val enableCalibratedDistanceModel: Boolean
) {
    fun scaledFor(width: Int, height: Int): CameraCalibrationProfile {
        if (referenceWidth <= 0 || referenceHeight <= 0) return this

        val sx = width.toDouble() / referenceWidth.toDouble()
        val sy = height.toDouble() / referenceHeight.toDouble()

        return copy(
            fx = fx * sx,
            fy = fy * sy,
            cx = cx * sx,
            cy = cy * sy
        )
    }

    fun focalLengthPx(): Float = ((fx + fy) * 0.5).toFloat()

    fun toCameraMatrix(): Mat {
        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        cameraMatrix.put(0, 0, fx)
        cameraMatrix.put(1, 1, fy)
        cameraMatrix.put(0, 2, cx)
        cameraMatrix.put(1, 2, cy)
        return cameraMatrix
    }

    fun toDistortionMat(): MatOfDouble {
        return MatOfDouble(*distCoeffs)
    }
}

object CameraCalibrationStore {
    private const val TAG = "CameraCalibration"
    private const val ASSET_FILE = "camera_calibration.json"

    fun load(context: Context): CameraCalibrationProfile {
        val fromAsset = loadFromAsset(context)
        return fromAsset ?: defaultProfile()
    }

    private fun loadFromAsset(context: Context): CameraCalibrationProfile? {
        return try {
            val jsonText = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            fromJson(JSONObject(jsonText)).also {
                Log.i(TAG, "Loaded calibration profile '${it.profileName}' from assets")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Calibration asset not loaded (${t.message}); using built-in defaults")
            null
        }
    }

    private fun fromJson(root: JSONObject): CameraCalibrationProfile {
        val coeffArray = root.getJSONArray("distCoeffs")
        val coeffs = DoubleArray(coeffArray.length()) { idx -> coeffArray.getDouble(idx) }

        return CameraCalibrationProfile(
            profileName = root.optString("profileName", "ov2640_vga_default"),
            referenceWidth = root.optInt("referenceWidth", 640),
            referenceHeight = root.optInt("referenceHeight", 480),
            fx = root.optDouble("fx", 434.0),
            fy = root.optDouble("fy", 434.0),
            cx = root.optDouble("cx", 320.0),
            cy = root.optDouble("cy", 240.0),
            distCoeffs = coeffs,
            enableUndistortion = root.optBoolean("enableUndistortion", true),
            enableCalibratedDistanceModel = root.optBoolean("enableCalibratedDistanceModel", true)
        )
    }

    private fun defaultProfile(): CameraCalibrationProfile {
        // Conservative OV2640-VGA starting point. Keep this editable via asset override.
        return CameraCalibrationProfile(
            profileName = "ov2640_vga_default",
            referenceWidth = 640,
            referenceHeight = 480,
            fx = 434.0,
            fy = 434.0,
            cx = 320.0,
            cy = 240.0,
            distCoeffs = doubleArrayOf(-0.19, 0.05, 0.0, 0.0, 0.0),
            enableUndistortion = true,
            enableCalibratedDistanceModel = true
        )
    }
}

