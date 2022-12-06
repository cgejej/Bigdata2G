package com.aiden.tflite.realtime_image_classifier

import android.util.Size

interface ConnectionCallback {
    fun onPreviewSizeChosen(size: Size, cameraRotation: Int)
}