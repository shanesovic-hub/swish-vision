package com.houseofswish.swishvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.houseofswish.swishvision.core.Detection
import com.houseofswish.swishvision.core.Roi
import com.houseofswish.swishvision.core.YoloDecoder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runs YOLO11n (COCO) on the phone's GPU and returns basketballs ("sports ball")
 * in camera-frame pixels. Input is NCHW float [1,3,640,640], RGB 0..1.
 * Output is [1,84,8400] with normalised boxes.
 */
class BallDetector(context: Context) : AutoCloseable {
    companion object {
        const val MODEL_FILE = "yolo11n_640.tflite"
        const val MODEL_NAME = "yolo11n-coco-640"
        const val INPUT = 640
        const val ANCHORS = 8400
        const val CLASSES = 80
        private const val INV255 = 1f / 255f
        private const val TAG = "BallDetector"
    }

    private val interpreter: Interpreter
    private var gpu: GpuDelegate? = null
    val backend: String

    private val plane = INPUT * INPUT
    private val chw = FloatArray(3 * plane)
    private val inputBytes: ByteBuffer =
        ByteBuffer.allocateDirect(4 * 3 * plane).order(ByteOrder.nativeOrder())
    private val input: FloatBuffer = inputBytes.asFloatBuffer()
    private val outBytes: ByteBuffer =
        ByteBuffer.allocateDirect(4 * (4 + CLASSES) * ANCHORS).order(ByteOrder.nativeOrder())
    private val out = FloatArray((4 + CLASSES) * ANCHORS)
    private val pixels = IntArray(plane)

    var confThreshold = 0.30f

    init {
        val model = loadModel(context)
        val onGpu = tryGpu(model)
        if (onGpu != null) {
            interpreter = onGpu
            backend = "GPU"
        } else {
            interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))
            backend = "CPU"
        }
    }

    private fun tryGpu(model: MappedByteBuffer): Interpreter? {
        return try {
            val compat = CompatibilityList()
            if (!compat.isDelegateSupportedOnThisDevice) return null
            val opts = compat.bestOptionsForThisDevice
            opts.setPrecisionLossAllowed(true)
            val delegate = GpuDelegate(opts)
            gpu = delegate
            Interpreter(model, Interpreter.Options().addDelegate(delegate))
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU", t)
            gpu?.close()
            gpu = null
            null
        }
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    // Reused every frame: the crop is drawn (scaled, filtered) into this square.
    private val inputBmp: Bitmap = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(inputBmp)
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val padColor = Color.rgb(114, 114, 114)

    /** Detect balls inside [roi] of [frame]. Results are in frame pixels. */
    fun detect(frame: Bitmap, roi: Roi): List<Detection> {
        val s = roi.scale
        canvas.drawColor(padColor)
        src.set(roi.x, roi.y, roi.x + roi.w, roi.y + roi.h)
        dst.set(0f, 0f, roi.w * s, roi.h * s)
        canvas.drawBitmap(frame, src, dst, filter)
        inputBmp.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)

        // Planar RGB, 0..1
        for (i in 0 until plane) {
            val p = pixels[i]
            chw[i] = ((p shr 16) and 0xFF) * INV255
            chw[plane + i] = ((p shr 8) and 0xFF) * INV255
            chw[2 * plane + i] = (p and 0xFF) * INV255
        }
        input.rewind()
        input.put(chw)
        inputBytes.rewind()
        outBytes.rewind()
        interpreter.run(inputBytes, outBytes)
        outBytes.rewind()
        outBytes.asFloatBuffer().get(out)

        return YoloDecoder.decode(
            out, ANCHORS, CLASSES, YoloDecoder.COCO_SPORTS_BALL,
            confThreshold, INPUT, normalizedBoxes = true,
        ).map { roi.toFrame(it) }
    }

    override fun close() {
        interpreter.close()
        gpu?.close()
    }
}
