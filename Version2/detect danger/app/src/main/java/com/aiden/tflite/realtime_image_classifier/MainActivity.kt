package com.aiden.tflite.realtime_image_classifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaRouter
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
import kotlin.concurrent.thread
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.ERROR
import android.speech.tts.UtteranceProgressListener
import kotlin.collections.ArrayList


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
    private var isSafe: Boolean = true
    private val detectQueue = arrayListOf<Int>()
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initClassifier()
        checkPermission()

        initQueue()
        initTextToSpeech()

    }

    private fun initQueue(){
        for(i in 0 until 20)
            detectQueue.add(0)
    }

    private fun initTextToSpeech(){
        tts = TextToSpeech(this){
            if(it==TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "Language not supported", Toast.LENGTH_SHORT).show()
                    return@TextToSpeech
                }
                Toast.makeText(this, "TTS setting successed", Toast.LENGTH_SHORT).show()
            }else{
                Toast.makeText(this,"TTS init failed", Toast.LENGTH_SHORT).show()
            }
        }
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
        tts?.stop()
        tts?.shutdown()
        tts = null
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
    private var ttsTime:Long = 0

    private fun startTTS(output:Pair<String, Float>,time:Long){
        detectQueue.removeAt(0)
        detectQueue.add(output.first.toInt())

        if(detectQueue.contains(0)||detectQueue.contains(9)||detectQueue.contains(8))
            isSafe = true
        else if(time>1500) {
            when (getMostFrequentValue(detectQueue)) {
                2 -> {
                    isSafe = false
                    tts?.speak("전방에 나무가 있습니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
                3 -> {
                    isSafe = false
                    tts?.speak("전방에 사람이 있습니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
                4 -> {
                    isSafe = false
                    tts?.speak("전방에 기둥이 있습니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
                5 -> {
                    isSafe = false
                    tts?.speak("전방에 차량이 있습니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
                6 -> {
                    isSafe = false
                    tts?.speak("전방에 벽이 있습니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
                7 -> {
                    isSafe = false
                    tts?.speak("전방에 문이 있습니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
                91 -> {
                    isSafe = true
                    tts?.speak("전방에 횡단보도 입니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
                else -> {
                    isSafe = false
                    tts?.speak("전방에 장애물이 있습니다", TextToSpeech.QUEUE_ADD, null, null)
                    ttsTime = 0
                }
            }
        }
    }

    private fun getMostFrequentValue(array: ArrayList<Int>): Int? {
        // Create a mutable map to store the frequency of each element in the array
        val frequencyMap = mutableMapOf<Int, Int>()

        // Iterate through the array and add each element to the map, incrementing its count by 1 each time it appears
        for (element in array) {
            if (element in frequencyMap) {
                frequencyMap[element] = frequencyMap[element]!! + 1
            } else {
                frequencyMap[element] = 1
            }
        }
        // Find the maximum frequency by using the maxBy function on the map and selecting the value of the frequency
        val maxFrequency = frequencyMap.values.maxByOrNull { it }

//        // Calculate the ratio of the maximum frequency to the total number of elements in the array
//        val ratio = maxFrequency!!.toDouble() / array.size.toDouble()
//
//        // Check the ratio
//        if (ratio > 0.25) {
//            // Iterate through the map and find the key that has the maximum frequency
//            for ((key, value) in frequencyMap) {
//                if (value == maxFrequency) {
//                    return key
//                }
//            }
//        }

        for((key,value) in frequencyMap){
            if(value == maxFrequency){
                return key
            }
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
                ttsTime += elapsedTime
                startTTS(output,ttsTime)

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