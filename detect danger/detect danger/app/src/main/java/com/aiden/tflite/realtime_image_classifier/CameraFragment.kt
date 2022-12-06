package com.aiden.tflite.realtime_image_classifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.text.PrecomputedTextCompat.Params
import androidx.fragment.app.Fragment
import com.aiden.tflite.realtime_image_classifier.databinding.FragmentCameraBinding
import java.lang.reflect.Parameter
import java.security.Policy.Parameters
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sign


class CameraFragment(
    private val connectionCallback: ConnectionCallback,
    private val imageAvailableListener: ImageReader.OnImageAvailableListener,
    private val inputSize: Size,
    private val cameraId: String
) : Fragment() {
    private lateinit var binding: FragmentCameraBinding
    private lateinit var autoFitTextureView: AutoFitTextureView
    private var sensorOrientation = 0
    private lateinit var previewSize: Size
    private val cameraOpenCloseLock = Semaphore(1)
    private var cameraDevice: CameraDevice? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var previewReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val stateCallback by lazy {
        object : CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) {
                cameraOpenCloseLock.release()
                cameraDevice = p0
                createCameraPreviewSession()
            }

            override fun onDisconnected(p0: CameraDevice) {
                cameraOpenCloseLock.release()
                p0.close()
                cameraDevice = null
            }

            override fun onError(p0: CameraDevice, p1: Int) {
                cameraOpenCloseLock.release()
                p0.close()
                cameraDevice = null
                activity?.run {
                    finish()
                }
            }

        }
    }

    private val sessionStateCallback by lazy {
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(p0: CameraCaptureSession) {
                if (cameraDevice == null) return
                captureSession = p0
                try {
                    captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
                } catch (e: CameraAccessException) {
                    Toast.makeText(requireContext(), "sessionStateCallback CameraAccessException", Toast.LENGTH_SHORT)
                        .show()
                }
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                Toast.makeText(requireContext(), "CameraCaptureSession Failed", Toast.LENGTH_SHORT).show()
            }

        }
    }

    private val surfaceTextureListener by lazy {
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                openCamera(p1, p2)
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
                configureTransform(p1, p2)
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture) = true

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
            }

        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        autoFitTextureView = binding.textureCamera
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (!autoFitTextureView.isAvailable) {
            autoFitTextureView.surfaceTextureListener = surfaceTextureListener
        } else {
            openCamera(autoFitTextureView.width, autoFitTextureView.height)
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageListener")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Toast.makeText(requireContext(), "stopBackgroundThread InterruptedException", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera(width: Int, height: Int) {
        val manager = activity?.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            Toast.makeText(requireContext(), "Can not open camera", Toast.LENGTH_SHORT).show()
            return
        }

        setupCameraOutputs(manager)
        configureTransform(width, height)
        try {
            if (cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS).not()) {
                Toast.makeText(requireContext(), "Time out waiting to lock camera opening", Toast.LENGTH_SHORT).show()
                activity?.finish()
            } else {
                if (ActivityCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(requireContext(), "openCamera require camera permission", Toast.LENGTH_SHORT).show()
                    return
                }
                manager.openCamera(cameraId, stateCallback, backgroundHandler)
            }
        } catch (e: InterruptedException) {
            Toast.makeText(requireContext(), "openCamera InterruptedException", Toast.LENGTH_SHORT).show()

        } catch (e: CameraAccessException) {
            Toast.makeText(requireContext(), "openCamera InterruptedException", Toast.LENGTH_SHORT).show()

        }

    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            previewReader?.close()
            previewReader = null
        } catch (e: InterruptedException) {
            Toast.makeText(requireContext(), "closeCamera InterruptedException", Toast.LENGTH_SHORT).show()
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun setupCameraOutputs(manager: CameraManager) {
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            previewSize = chooseOptimalSize(
                map?.getOutputSizes(SurfaceTexture::class.java)!!,
                inputSize.width,
                inputSize.height
            )

            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                autoFitTextureView.setAspectRation(previewSize.width, previewSize.height)
            } else {
                autoFitTextureView.setAspectRation(previewSize.height, previewSize.width)
            }
        } catch (e: CameraAccessException) {
            Toast.makeText(requireContext(), "setupCameraOutputs CameraAccessException!!", Toast.LENGTH_SHORT)
                .show()
        }

        connectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation)
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        val minSize = width.coerceAtMost(height)
        val desiredSize = Size(width, height)

        val bigEnough = arrayListOf<Size>()
        val tooSmall = arrayListOf<Size>()
        choices.forEach { option ->
            if (option == desiredSize) {
                return desiredSize
            }

            if (option.height >= minSize && option.width >= minSize) {
                bigEnough.add(option)
            } else {
                tooSmall.add(option)
            }
        }

        return if (bigEnough.size > 0) {
            Collections.min(bigEnough, CompareSizeByArea())
        } else {
            Collections.max(tooSmall, CompareSizeByArea())
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (::autoFitTextureView.isInitialized.not() || ::previewSize.isInitialized.not() || activity == null) return
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().display!!.rotation
        } else {
            requireActivity().resources.configuration.orientation
        }
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale =
                max((viewHeight / previewSize.height).toFloat(), (viewHeight / previewSize.height).toFloat())
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        autoFitTextureView.setTransform(matrix)
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = autoFitTextureView.surfaceTexture
            texture!!.setDefaultBufferSize(previewSize.width, previewSize.height)

            val surface = Surface(texture)

            previewReader =
                ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
                }


            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(previewReader!!.surface)

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_STATE_PASSIVE_FOCUSED)

            }
            cameraDevice?.createCaptureSession(listOf(surface, previewReader!!.surface), sessionStateCallback, null)
                ?: run {
                    Toast.makeText(
                        requireContext(),
                        "createCameraPreviewSession cameraDevice null!!",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }
        } catch (e: CameraAccessException) {
            Toast.makeText(
                requireContext(),
                "createCameraPreviewSession CameraAccessException!!",
                Toast.LENGTH_SHORT
            )
                .show()
        }
    }

    inner class CompareSizeByArea : Comparator<Size> {
        override fun compare(p0: Size?, p1: Size?): Int {
            return sign((p0?.width!! * p0.height).toDouble() - (p1!!.width * p1.height).toDouble()).toInt()
        }
    }

    companion object {
        fun newInstance(
            callback: ConnectionCallback,
            imageAvailableListener: ImageReader.OnImageAvailableListener,
            inputSize: Size,
            cameraId: String
        ) = CameraFragment(callback, imageAvailableListener, inputSize, cameraId)
    }
}