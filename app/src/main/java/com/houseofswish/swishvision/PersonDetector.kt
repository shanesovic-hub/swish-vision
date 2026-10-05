package com.houseofswish.swishvision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.houseofswish.swishvision.core.Detection
import com.houseofswish.swishvision.core.Roi
import com.houseofswish.swishvision.core.YoloDecoder
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Finds people in the whole camera frame (YOLO11n COCO, 320 x 320, 8-bit weights, CPU).
 * Used a few times a second to learn where the shooter stands. Returns boxes in frame pixels.
 */
class PersonDetector(context: Context) : AutoCloseable {
    companion object {
        const val MODEL_FILE = "person_320.tflite"
        const val INPUT = 320
        private const val ANCHORS = 2100
        private const val CLASSES = 80
        private const val PERSON = 0
        private const val INV255 = 1f / 255f
    }

    private val interpreter: Interpreter
    private val plane = INPUT * INPUT
    private val chw = FloatArray(3 * plane)
    private val inputBytes: ByteBuffer = ByteBuffer.allocateDirect(4 * 3 * plane).order(ByteOrder.nativeOrder())
    private val outBytes: ByteBuffer = ByteBuffer.allocateDirect(4 * (4 + CLASSES) * ANCHORS).order(ByteOrder.nativeOrder())
    private val out = FloatArray((4 + CLASSES) * ANCHORS)
    private val pixels = IntArray(plane)
    private val bmp: Bitmap = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    init {
        val model = context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2).setUseXNNPACK(true))
    }

    /**
     * Step 1, on the camera thread (fast, ~2 ms): shrink the frame into this detector's own input buffer.
     * Returns how the frame was scaled, for [run]. Don't call again until [run] has finished.
     */
    fun prepare(frame: Bitmap): Roi {
        val roi = Roi.full(frame.width, frame.height, INPUT)
        val s = roi.scale
        canvas.drawColor(Color.rgb(114, 114, 114))
        src.set(0, 0, frame.width, frame.height)
        dst.set(0f, 0f, frame.width * s, frame.height * s)
        canvas.drawBitmap(frame, src, dst, filter)
        bmp.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        for (i in 0 until plane) {
            val p = pixels[i]
            chw[i] = ((p shr 16) and 0xFF) * INV255
            chw[plane + i] = ((p shr 8) and 0xFF) * INV255
            chw[2 * plane + i] = (p and 0xFF) * INV255
        }
        return roi
    }

    /** Step 2, on its own thread (~30-60 ms): find the people. Boxes in camera-frame pixels, best first. */
    fun run(roi: Roi, minScore: Float = 0.4f): List<Detection> {
        inputBytes.rewind(); inputBytes.asFloatBuffer().put(chw)
        inputBytes.rewind(); outBytes.rewind()
        interpreter.run(inputBytes, outBytes)
        outBytes.rewind(); outBytes.asFloatBuffer().get(out)
        return YoloDecoder.decode(out, ANCHORS, CLASSES, PERSON, minScore, INPUT, normalizedBoxes = true, maxResults = 6)
            .map { roi.toFrame(it) }
    }

    fun detect(frame: Bitmap, minScore: Float = 0.4f): List<Detection> = run(prepare(frame), minScore)

    override fun close() = interpreter.close()
}
