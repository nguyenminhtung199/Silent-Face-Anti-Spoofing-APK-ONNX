@file:Suppress("DEPRECATION")

package com.mv.livebodyexample

import android.Manifest
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.mv.engine.Live.Companion.tag
import com.mv.livebodyexample.databinding.ActivityMainBinding
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@ObsoleteCoroutinesApi
class MainActivity : AppCompatActivity(), SetThresholdDialogFragment.ThresholdDialogListener {

    private lateinit var binding: ActivityMainBinding

    private var enginePrepared: Boolean = false
    private lateinit var engineWrapper: EngineWrapper
    private var threshold: Float = defaultThreshold

    private var camera: Camera? = null
    private var cameraId: Int = Camera.CameraInfo.CAMERA_FACING_BACK
    private val previewWidth: Int = 1920
    private val previewHeight: Int = 1080
    private var parametersB: Camera.Parameters? = null
    /**
     *    1       2       3       4        5          6          7            8
     * <p>
     * 888888  888888      88  88      8888888888  88                  88  8888888888
     * 88          88      88  88      88  88      88  88          88  88      88  88
     * 8888      8888    8888  8888    88          8888888888  8888888888          88
     * 88          88      88  88
     * 88          88  888888  888888
     */
    private var frameOrientation: Int = 6

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var factorX: Float = 0F
    private var factorY: Float = 0F

    private val detectionContext = newSingleThreadContext("detection")
    private var working: Boolean = false

    private lateinit var scaleAnimator: ObjectAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasPermissions()) {
            init()
        } else {
            requestPermission()
        }
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (permission in permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }
        return true
    }


        @TargetApi(Build.VERSION_CODES.M)
    private fun requestPermission() = requestPermissions(permissions, permissionReqCode)

    private fun init() {
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.result = DetectionResult()

        calculateSize()

        binding.surface.holder.let {
            it.setFormat(ImageFormat.NV21)


            it.addCallback(object : SurfaceHolder.Callback, Camera.PreviewCallback {
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    if (holder?.surface == null) return

                    if (camera == null) return

                    try {
                        camera?.stopPreview()
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                    }

                    var parameters = camera?.parameters
                    parameters?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
//                    camera?.parameters = parameters
                    parameters?.setPreviewSize(previewWidth, previewHeight)
                    factorX = screenWidth / previewWidth.toFloat()
                    factorY = screenHeight / previewHeight.toFloat()

                    camera?.parameters = parameters
//                    if (cameraId == Camera.CameraInfo.CAMERA_FACING_BACK) parametersB = parameters


                    camera?.startPreview()
                    camera?.setPreviewCallback(this)

                    setCameraDisplayOrientation()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    camera?.setPreviewCallback(null)
                    camera?.release()
                    camera = null
                }

                override fun surfaceCreated(holder: SurfaceHolder) {
                    try {
                        camera = Camera.open(cameraId)
                    } catch (e: Exception) {
                        cameraId = Camera.CameraInfo.CAMERA_FACING_BACK
                        camera = Camera.open(cameraId)
                    }

                    try {
                        camera!!.setPreviewDisplay(binding.surface.holder)
                        setCameraDisplayOrientation()

                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
                    if (enginePrepared && data != null) {
                        if (!working) {
                            GlobalScope.launch(detectionContext) {
                                working = true
                                val result = engineWrapper.detect(
                                    data,
                                    previewWidth,
                                    previewHeight,
                                    frameOrientation
                                )
                                result.threshold = threshold

                                val rect = calculateBoxLocationOnScreen(
                                    result.left,
                                    result.top,
                                    result.right,
                                    result.bottom
                                )

//                                val rect2x = calculateBoxLocationOnScreen(result.left, result.top, result.right, result.bottom, 1.2f)
//                                val rect4x = calculateBoxLocationOnScreen(result.left, result.top, result.right, result.bottom, 4.0f)
//
//                                // You can set different colors for each box
//                                binding.result = result.updateLocation(rect2x)
//                                binding.rectView.postInvalidate()
//
//                                // Optionally draw the second box with another instance or modify the same rectView
//                                binding.result = result.updateLocation(rect4x)
//                                binding.rectView.postInvalidate()

                                binding.result = result.updateLocation(rect)

                                Log.d(
                                    tag,
                                    "threshold:${result.threshold}, confidence: ${result.confidence}"
                                )

                                binding.rectView.postInvalidate()
                                working = false
                            }
                        }
                    }
                }
            })
        }


        scaleAnimator = ObjectAnimator.ofFloat(binding.scan, View.SCALE_Y, 1F, -1F, 1F).apply {
            this.duration = 3000
            this.repeatCount = ValueAnimator.INFINITE
            this.repeatMode = ValueAnimator.REVERSE
            this.interpolator = LinearInterpolator()
            this.start()
        }

    }
    private fun calculateBoxLocationOnScreen(left: Int, top: Int, right: Int, bottom: Int, expandFactor: Float): Rect {
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2

        val newWidth = (right - left) * expandFactor
        val newHeight = (bottom - top) * expandFactor

        return Rect(
            (centerX - newWidth / 2 * factorX).toInt(),
            (centerY - newHeight / 2 * factorY).toInt(),
            (centerX + newWidth / 2 * factorX).toInt(),
            (centerY + newHeight / 2 * factorY).toInt()
        )
    }

    fun switchCamera(view: View) {
        if (camera == null) return

        camera?.setPreviewCallback(null)
        camera?.stopPreview()
        camera?.release()
        camera = null

        cameraId = if (cameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            Camera.CameraInfo.CAMERA_FACING_BACK
        } else {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        }
        // Cập nhật frameOrientation tùy thuộc vào cameraId
        frameOrientation = if (cameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            7
        } else {
            6
        }

        try {
            camera = Camera.open(cameraId)
            camera?.setPreviewDisplay(binding.surface.holder)

            setCameraDisplayOrientation()
            camera?.startPreview()
            camera?.setPreviewCallback(object : Camera.PreviewCallback {
                override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
                    if (enginePrepared && data != null) {
                        if (!working) {
                            GlobalScope.launch(detectionContext) {
                                working = true
                                val result = engineWrapper.detect(
                                    data,
                                    previewWidth,
                                    previewHeight,
                                    frameOrientation
                                )
                                result.threshold = threshold

                                val rect = calculateBoxLocationOnScreen(
                                    result.left,
                                    result.top,
                                    result.right,
                                    result.bottom
                                )

                                binding.result = result.updateLocation(rect)

                                Log.d(
                                    tag,
                                    "threshold:${result.threshold}, confidence: ${result.confidence}"
                                )

                                binding.rectView.postInvalidate()
                                working = false
                            }
                        }
                    }
                }
            })
            setCameraDisplayOrientation()
        } catch (e: Exception) {
            Log.e(tag, "Failed to switch camera: ${e.message}")
            Toast.makeText(this, "Failed to switch camera", Toast.LENGTH_SHORT).show()
        }
    }

//    fun setting(@Suppress("UNUSED_PARAMETER") view: View) =
//        SetThresholdDialogFragment().show(supportFragmentManager, "dialog")

//    private fun switchCamera() {
//        releaseCamera()
//
//        // Chuyển đổi cameraId
//        cameraId = if (cameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
//            Camera.CameraInfo.CAMERA_FACING_BACK
//        } else {
//            Camera.CameraInfo.CAMERA_FACING_FRONT
//        }
//
//        openCamera()
//    }

    private fun calculateSize() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
//        screenWidth = dm.widthPixels
//        screenHeight = dm.heightPixels
        screenWidth = 1920
        screenHeight = 1080
    }

    private fun calculateBoxLocationOnScreen(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect(
            (left * factorX).toInt(),
            (top * factorY).toInt(),
            (right * factorX).toInt(),
            (bottom * factorY).toInt()
        )

    private fun setCameraDisplayOrientation() {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        val rotation = windowManager.defaultDisplay.rotation
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }
        var result: Int
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360
            result = (360 - result) % 360 // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360
        }
        camera!!.setDisplayOrientation(result)
        Log.d("RotationDebug", "Screen rotation: $degrees")
    }

    fun setting(@Suppress("UNUSED_PARAMETER") view: View) =
        SetThresholdDialogFragment().show(supportFragmentManager, "dialog")

    fun capturePhoto(@Suppress("UNUSED_PARAMETER") view: View) {
        if (camera == null) {
            Toast.makeText(this, "Camera is not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        camera?.takePicture(null, null, Camera.PictureCallback { data, _ ->
            try {
                // Lấy hướng xoay của thiết bị
                val rotation = windowManager.defaultDisplay.rotation
                val rotationInDegrees = if (cameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    when (rotation) {
                        Surface.ROTATION_0 -> 90
                        Surface.ROTATION_90 -> 180
                        Surface.ROTATION_180 -> 270
                        Surface.ROTATION_270 -> 0
                        else -> 0
                    }
                } else {
                    when (rotation) {
                        Surface.ROTATION_0 -> 270
                        Surface.ROTATION_90 -> 0
                        Surface.ROTATION_180 -> 90
                        Surface.ROTATION_270 -> 180
                        else -> 0
                    }
                }

                // Decode byte array thành bitmap
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)

                // Tạo matrix và xoay bitmap
                val matrix = Matrix()
                matrix.postRotate(rotationInDegrees.toFloat())
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                // Tạo tên file với timestamp
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "IMG_$timeStamp.jpg"

                // Lưu ảnh đã xoay vào bộ nhớ
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }

                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { outputStream ->
                            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        }
                    }
                } else {
                    val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val image = File(imagesDir, fileName)
                    FileOutputStream(image).use { outputStream ->
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }
                }

                Toast.makeText(this, "Photo saved successfully", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Log.e(tag, "Error saving photo: ${e.message}")
                Toast.makeText(this, "Failed to save photo", Toast.LENGTH_SHORT).show()
            }

            // Khởi động lại preview sau khi chụp ảnh
            camera?.startPreview()
        })
    }
    override fun onDialogPositiveClick(t: Float) {
        threshold = t
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionReqCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                init()
            } else {
                Toast.makeText(this, "Please grant camera permissions.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        engineWrapper = EngineWrapper(assets)
        enginePrepared = engineWrapper.init()

        if (!enginePrepared) {
            Toast.makeText(this, "Engine init failed.", Toast.LENGTH_LONG).show()
        }

        super.onResume()
    }

    override fun onDestroy() {
        engineWrapper.destroy()
        scaleAnimator.cancel()
        super.onDestroy()
    }

    companion object {
        const val tag = "MainActivity"
        const val defaultThreshold = 0.915F

        val permissions: Array<String> = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        const val permissionReqCode = 1
    }

}
