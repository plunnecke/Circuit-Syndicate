package com.circuitsyndicate.findingtheway

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc

object CvImageUtils {
    private const val TAG = "CvImageUtils"
    private const val DEFAULT_CLAHE_CLIP_LIMIT = 2.0
    private const val DEFAULT_CLAHE_TILE_GRID_SIZE = 8

    fun bitmapToRgbaMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        return when (mat.type()) {
            CvType.CV_8UC4 -> mat
            CvType.CV_8UC3 -> {
                val rgba = Mat()
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_RGB2RGBA)
                mat.release()
                rgba
            }
            else -> {
                val rgba = Mat()
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA)
                mat.release()
                rgba
            }
        }
    }

    fun matToArgbBitmap(inputMat: Mat): Bitmap {
        val rgba = when (inputMat.type()) {
            CvType.CV_8UC4 -> inputMat
            CvType.CV_8UC3 -> {
                val converted = Mat()
                Imgproc.cvtColor(inputMat, converted, Imgproc.COLOR_RGB2RGBA)
                converted
            }
            else -> {
                val converted = Mat()
                Imgproc.cvtColor(inputMat, converted, Imgproc.COLOR_GRAY2RGBA)
                converted
            }
        }

        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)

        if (rgba !== inputMat) {
            rgba.release()
        }

        return bitmap
    }

    fun applyClaheToBitmap(
        input: Bitmap,
        clipLimit: Double = DEFAULT_CLAHE_CLIP_LIMIT,
        tileGridSize: Int = DEFAULT_CLAHE_TILE_GRID_SIZE
    ): Bitmap {
        var rgba: Mat? = null
        var bgr: Mat? = null
        var lab: Mat? = null
        var enhancedBgr: Mat? = null
        var enhancedRgba: Mat? = null
        var clahe: CLAHE? = null
        val channels = ArrayList<Mat>(3)

        return try {
            rgba = bitmapToRgbaMat(input)
            bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            lab = Mat()
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)

            if (channels.size < 3) {
                throw IllegalStateException("Unexpected LAB channel count: ${channels.size}")
            }

            val grid = tileGridSize.coerceAtLeast(1).toDouble()
            clahe = Imgproc.createCLAHE(
                clipLimit.coerceAtLeast(0.1),
                Size(grid, grid)
            )

            val enhancedL = Mat()
            clahe.apply(channels[0], enhancedL)
            channels[0].release()
            channels[0] = enhancedL

            Core.merge(channels, lab)

            enhancedBgr = Mat()
            Imgproc.cvtColor(lab, enhancedBgr, Imgproc.COLOR_Lab2BGR)

            enhancedRgba = Mat()
            Imgproc.cvtColor(enhancedBgr, enhancedRgba, Imgproc.COLOR_BGR2RGBA)

            matToArgbBitmap(enhancedRgba)
        } catch (t: Throwable) {
            Log.w(TAG, "CLAHE enhancement failed: ${t.message}")
            input.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            channels.forEach { it.release() }
            clahe?.collectGarbage()
            rgba?.release()
            bgr?.release()
            lab?.release()
            enhancedBgr?.release()
            enhancedRgba?.release()
        }
    }
}

