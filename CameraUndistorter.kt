package com.circuitsyndicate.findingtheway

import android.graphics.Bitmap
import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat

object CameraUndistorter {
    private const val TAG = "CameraUndistorter"

    fun undistortBitmap(input: Bitmap, profile: CameraCalibrationProfile): Bitmap {
        val scaled = profile.scaledFor(input.width, input.height)

        var src: Mat? = null
        var dst: Mat? = null
        var cameraMatrix: Mat? = null
        var dist: Mat? = null

        try {
            src = CvImageUtils.bitmapToRgbaMat(input)
            dst = Mat()
            cameraMatrix = scaled.toCameraMatrix()
            dist = scaled.toDistortionMat()

            Calib3d.undistort(src, dst, cameraMatrix, dist)
            return CvImageUtils.matToArgbBitmap(dst)
        } catch (t: Throwable) {
            Log.w(TAG, "Undistortion failed: ${t.message}")
            return input.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            src?.release()
            dst?.release()
            cameraMatrix?.release()
            dist?.release()
        }
    }
}

