package com.aiden.tflite.realtime_image_classifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.*
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aiden.tflite.realtime_image_classifier.databinding.ActivityMainBinding
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater, null, false) }
    private lateinit var classifier: Classifier
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                setFragment()
            } else {
                Toast.makeText(
                    this,
                    "permission denied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    private var previewWidth = 0
    private var previewHeight = 0
    private var sensorOrientation = 0
    private var rgbFrameBitmap: Bitmap? = null
    private var isProcessingFrame = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initClassifier()
        checkPermission()
    }

    override fun onResume() {
        super.onResume()

        handlerThread = HandlerThread("InferenceThread")
        handlerThread?.start()
        handler = Handler(handlerThread!!.looper)
    }

    override fun onPause() {
        handlerThread?.quitSafely()
        try {
            handlerThread?.join()
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            Toast.makeText(this, "activity onPause InterruptedException", Toast.LENGTH_SHORT).show()
        }
        super.onPause()
    }

    override fun onDestroy() {
        classifier.finish()
        super.onDestroy()
    }

    private fun initClassifier() {
        classifier = Classifier(this, Classifier.IMAGENET_CLASSIFY_MODEL)
        try {
            classifier.init()
        } catch (exception: IOException) {
            Toast.makeText(this, "Can not init Classifier!!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                setFragment()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(
                    this,
                    "This app need camera permission to classify realtime camera image",
                    Toast.LENGTH_SHORT
                ).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setFragment() {
        val inputSize = classifier.getModelInputSize()
        val cameraId = chooseCamera()
        if (inputSize.width > 0 && inputSize.height > 0 && cameraId != null) {
            val fragment = CameraFragment.newInstance(object : ConnectionCallback {
                override fun onPreviewSizeChosen(size: Size, cameraRotation: Int) {
                    previewWidth = size.width
                    previewHeight = size.height
                    sensorOrientation = cameraRotation - getScreenOrientation()
                }
            }, {
                processImage(it)
            },
                inputSize,
                cameraId
            )
            supportFragmentManager.beginTransaction().replace(R.id.frame_camera, fragment).commit()
        } else {
            Toast.makeText(this, "Can not find camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseCamera(): String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.cameraIdList.forEach { cameraId ->
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            Toast.makeText(this, "CameraAccessException", Toast.LENGTH_SHORT).show()
        }
        return null
    }

    private fun getScreenOrientation(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.display
        } else {
            windowManager.defaultDisplay
        } ?: return 0
        return when (display.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    private fun processImage(reader: ImageReader) {
        if (previewWidth == 0 || previewHeight == 0) return
        if (rgbFrameBitmap == null) {
            rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        }
        if (isProcessingFrame) return
        isProcessingFrame = true
        val image = reader.acquireLatestImage()
        if (image == null) {
            isProcessingFrame = false
            return
        }

        YuvToRgbConverter.yuvToRgb(this, image, rgbFrameBitmap!!)

        handler?.post {
            if (::classifier.isInitialized && classifier.isInitialized()) {
                val startTime = SystemClock.uptimeMillis()
                val output = classifier.classify(rgbFrameBitmap!!, sensorOrientation)
                val elapsedTime = SystemClock.uptimeMillis() - startTime
                runOnUiThread {
                    binding.textResult.text =
                        String.format(
                            Locale.ENGLISH,
                            "class : %s\nprob : %.2f%%\ntime : %dms",
                            output.first,
                            output.second * 100,
                            elapsedTime
                        )
                }
            }
            image.close()
            isProcessingFrame = false
        }
    }

}