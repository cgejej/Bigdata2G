package com.aiden.tflite.realtime_image_classifier

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.model.Model
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteOrder

class Classifier(private var context: Context, private val modelName: String) {
    private lateinit var model: Model
    private lateinit var inputImage: TensorImage
    private lateinit var outputBuffer: TensorBuffer
    private var modelInputChannel = 0
    private var modelInputWidth = 0
    private var modelInputHeight = 0
    private val labels = mutableListOf<String>()
    private var isInitialized = false

    fun init() {
        model = createGPUModel(context, modelName)
        initModelShape()
        labels.addAll(FileUtil.loadLabels(context, LABEL_FILE))
        isInitialized = true
    }

    private fun createMultiThreadModel(context: Context, modelName: String, threadSize: Int): Model {
        val optionBuilder = Model.Options.Builder().apply {
            setNumThreads(threadSize)
        }
        return Model.createModel(context, modelName, optionBuilder.build())
    }

    private fun createMultiThreadInterpreter(context: Context, modelName: String, threadSize: Int): Interpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(threadSize)
        }
        val model = FileUtil.loadMappedFile(context, modelName).apply {
            order(ByteOrder.nativeOrder())
        }
        return Interpreter(model, options)
    }

    private fun createGPUModel(context: Context, modelName: String): Model {
        val compatList = CompatibilityList()
        val optionsBuilder = Model.Options.Builder().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
                setDevice(Model.Device.GPU)
            }
        }
        return Model.createModel(context, modelName, optionsBuilder.build())
    }

    private fun createGPUInterpreter(context: Context, modelName: String): Interpreter {
        val compatList = CompatibilityList()
        val options = Interpreter.Options().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                val gpuDelegate = GpuDelegate(delegateOptions)
                addDelegate(gpuDelegate)
            }
        }
        val model = FileUtil.loadMappedFile(context, modelName).apply {
            order(ByteOrder.nativeOrder())
        }
        return Interpreter(model, options)
    }

    private fun createNNAPIModel(context: Context, modelName: String): Model {
        val optionsBuilder = Model.Options.Builder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setDevice(Model.Device.NNAPI)
            }
        }
        return Model.createModel(context, modelName, optionsBuilder.build())
    }

    private fun createNNAPIInterpreter(context: Context, modelName: String): Interpreter {
        val options = Interpreter.Options().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val nnApiDelegate = NnApiDelegate()
                addDelegate(nnApiDelegate)
            }
        }
        val model = FileUtil.loadMappedFile(context, modelName).apply {
            order(ByteOrder.nativeOrder())
        }
        return Interpreter(model, options)
    }

    private fun initModelShape() {
        val inputTensor = model.getInputTensor(0)
        val inputShape = inputTensor.shape()
        modelInputChannel = inputShape[0]
        modelInputWidth = inputShape[1]
        modelInputHeight = inputShape[2]

        inputImage = TensorImage(inputTensor.dataType())

        val outputTensor = model.getOutputTensor(0)
        outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), outputTensor.dataType())
    }

    fun classify(image: Bitmap, sensorOrientation: Int): Pair<String, Float> {
        inputImage = loadImage(image, sensorOrientation)
        val inputs = arrayOf<Any>(inputImage.buffer)
        val outputs = mutableMapOf<Int, Any>()
        outputs[0] = outputBuffer.buffer.rewind()
        model.run(inputs, outputs)
        val output = TensorLabel(labels, outputBuffer).mapWithFloatValue
        return argmax(output)
    }

    fun classify(image: Bitmap) = classify(image, 0)

    private fun loadImage(bitmap: Bitmap, sensorOrientation: Int): TensorImage {
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            inputImage.load(convertBitmapToARGB8888(bitmap))
        } else {
            inputImage.load(bitmap)
        }

        val cropSize = Math.min(bitmap.width, bitmap.height)
        val numRotation = sensorOrientation / 90

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeWithCropOrPadOp(cropSize, cropSize))
            .add(ResizeOp(modelInputWidth, modelInputHeight, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
            .add(Rot90Op(numRotation))
//            .add(NormalizeOp(0.0f, 255.0f))
            .build()

        return imageProcessor.process(inputImage)
    }

    private fun convertBitmapToARGB8888(bitmap: Bitmap) = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    private fun argmax(map: Map<String, Float>) =
        map.entries.maxByOrNull { it.value }?.let {
            it.key to it.value
        } ?: "" to 0f

    fun finish() {
        if (::model.isInitialized) model.close()
        if (isInitialized) isInitialized = false
    }

    fun isInitialized() = isInitialized

    fun getModelInputSize(): Size = if (isInitialized.not()) Size(0, 0) else Size(modelInputWidth, modelInputHeight)

    companion object {
        const val IMAGENET_CLASSIFY_MODEL = "demo1.tflite"
        const val LABEL_FILE = "demo1_labels.txt"
    }
}