package com.houseofswish.swishvision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.houseofswish.swishvision.core.Box
import com.houseofswish.swishvision.core.HoopFinder
import com.houseofswish.swishvision.core.Method
import com.houseofswish.swishvision.core.Result
import com.houseofswish.swishvision.core.Roi
import com.houseofswish.swishvision.core.Session
import com.houseofswish.swishvision.core.ShotTracker
import com.houseofswish.swishvision.core.ShotType
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
/**
 * The camera tracker. Opened from Swish Quest's Log Session screen; when the player
 * taps End -> Save, the session goes back to Swish Quest as JSON (EXTRA_RESULT).
 */
class TrackerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var soundBtn: TextView
    private lateinit var makesTv: TextView
    private lateinit var missesTv: TextView
    private lateinit var pctTv: TextView
    private lateinit var subline: TextView
    private lateinit var chips: Map<ShotType, Button>

    // --- analysis thread state (only touched on analysisExecutor) ---
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var detector: BallDetector? = null
    private val tracker = ShotTracker()
    private val hoopFinder = HoopFinder()
    private var ballFrames = 0 // frames with a ball seen, counted per second by the ticker
    private var frameBmp: Bitmap? = null
    private var tight: ByteBuffer? = null
    private var lastFrameAt = 0L
    private var fpsEma = 0f

    // --- shared ---
    @Volatile private var rim: Box? = null
    @Volatile private var resetTracker = false
    @Volatile private var detectorError: String? = null
    @Volatile private var backend = "…"
    @Volatile private var hot = false          // critical: check every other frame
    @Volatile private var autoRim = false      // rim came from the hoop finder (and follows the hoop)
    @Volatile private var autoFind = true      // look for the hoop while no rim is set
    @Volatile private var ballRate = 0
    private var frameNo = 0L

    // --- main thread state ---
    private var session = Session(System.currentTimeMillis())
    private var shotType = ShotType.FT
    private var soundOn = true
    private var tone: ToneGenerator? = null
    private var latestFps = 0f
    private var fpsSum = 0f
    private var fpsCount = 0
    private var cameraFps = "30"
    private var cameraProvider: ProcessCameraProvider? = null
    private var want60 = true
    private var fellBackTo30 = false
    private val ui = Handler(Looper.getMainLooper())

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            hint.text = getString(R.string.camera_needed)
            hint.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        hint = findViewById(R.id.hint)
        status = findViewById(R.id.status)
        soundBtn = findViewById(R.id.soundBtn)
        makesTv = findViewById(R.id.makes)
        missesTv = findViewById(R.id.misses)
        pctTv = findViewById(R.id.pct)
        subline = findViewById(R.id.subline)
        chips = mapOf(
            ShotType.LAYUP to findViewById<Button>(R.id.chipLayup),
            ShotType.MID to findViewById<Button>(R.id.chipMid),
            ShotType.FT to findViewById<Button>(R.id.chipFt),
            ShotType.THREE to findViewById<Button>(R.id.chipThree),
        )
        overlay.ringLineFrac = tracker.cfg.ringLineFrac
        intent.getStringExtra(EXTRA_SHOT_TYPE)?.let { key ->
            ShotType.values().firstOrNull { it.key == key }?.let { shotType = it }
        }
        // Back: don't lose a session by accident.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (session.shots.isEmpty()) finish() else endSession()
            }
        })

        overlay.onRimDrawn = { box ->
            autoRim = false // the player placed it: stop auto-following
            rim = box
            resetTracker = true
            if (hint.visibility == View.VISIBLE) {
                Toast.makeText(this, "Drag the box to move it, or a corner to resize", Toast.LENGTH_LONG).show()
            }
            hint.visibility = View.GONE
        }
        chips.forEach { (type, btn) -> btn.setOnClickListener { shotType = type; refresh() } }
        findViewById<Button>(R.id.addMake).setOnClickListener { addShot(Result.MAKE, Method.MANUAL) }
        findViewById<Button>(R.id.addMiss).setOnClickListener { addShot(Result.MISS, Method.MANUAL) }
        findViewById<Button>(R.id.undo).setOnClickListener { session.undo(); refresh() }
        findViewById<Button>(R.id.wrong).setOnClickListener {
            session.flipLast()?.let { overlay.flash(it.result) }
            refresh()
        }
        findViewById<Button>(R.id.redraw).setOnClickListener {
            autoFind = false // they want to draw it themselves
            autoRim = false
            rim = null
            resetTracker = true
            overlay.rim = null
            overlay.settingRim = true
            hint.text = getString(R.string.draw_hint)
            hint.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.end).setOnClickListener { endSession() }
        soundBtn.setOnClickListener {
            soundOn = !soundOn
            soundBtn.text = if (soundOn) "🔊" else "🔇"
        }
        status.setOnClickListener { overlay.showDebug = !overlay.showDebug }
        hint.setOnClickListener {
            if (!hasCamera()) askCamera.launch(Manifest.permission.CAMERA)
        }

        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

        // Build the detector on the analysis thread: the GPU delegate must run where it was created.
        analysisExecutor.execute {
            try {
                val d = BallDetector(this)
                detector = d
                backend = d.backend
            } catch (t: Throwable) {
                Log.e(TAG, "Detector failed", t)
                detectorError = t.message ?: t.javaClass.simpleName
            }
        }

        if (hasCamera()) startCamera() else askCamera.launch(Manifest.permission.CAMERA)
        refresh()
        ui.post(ticker)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        analysisExecutor.execute { detector?.close(); detector = null }
        analysisExecutor.shutdown()
        tone?.release()
        super.onDestroy()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------- Camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /** A fixed 60 fps range if the back camera offers one: shorter exposures = less motion blur on the ball. */
    private fun sixtyFpsRange(): Range<Int>? {
        try {
            val cm = ContextCompat.getSystemService(this, CameraManager::class.java) ?: return null
            val back = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
            val ranges = cm.getCameraCharacteristics(back)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
            return ranges.firstOrNull { it.lower == 60 && it.upper == 60 }
                ?: ranges.filter { it.upper == 60 }.maxByOrNull { it.lower }
        } catch (t: Throwable) {
            return null
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(selector)
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

        val range = if (want60) sixtyFpsRange() else null
        if (range != null) {
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }

        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

        try {
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            cameraFps = if (range != null) "60" else "30"
            camera.cameraInfo.cameraState.observe(this) { state ->
                if (state.error != null && want60 && !fellBackTo30) {
                    fellBackTo30 = true
                    want60 = false
                    bindCamera()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed (60fps=$want60)", e)
            if (want60) {
                want60 = false
                bindCamera()
            } else {
                hint.text = "Couldn't open the camera: ${e.message}"
                hint.visibility = View.VISIBLE
            }
        }
    }

    /** Copy the RGBA frame into a reused bitmap (no per-frame allocation). */
    private fun frameBitmap(proxy: ImageProxy): Bitmap {
        val w = proxy.width
        val h = proxy.height
        var bmp = frameBmp
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frameBmp = bmp
        }
        val plane = proxy.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        buf.rewind()
        if (rowStride == w * 4) {
            bmp.copyPixelsFromBuffer(buf)
        } else {
            var t = tight
            if (t == null || t.capacity() != w * h * 4) {
                t = ByteBuffer.allocateDirect(w * h * 4)
                tight = t
            }
            t.clear()
            for (row in 0 until h) {
                buf.limit(row * rowStride + w * 4)
                buf.position(row * rowStride)
                t.put(buf)
            }
            buf.limit(buf.capacity())
            t.rewind()
            bmp.copyPixelsFromBuffer(t)
        }
        return bmp
    }

    private fun analyze(proxy: ImageProxy) {
        val det = detector
        frameNo++
        // Phone running hot: check every other frame to cool down (tracker is tested down to 15/s).
        if (det == null || (hot && frameNo % 2L == 1L)) {
            proxy.close()
            return
        }
        val tMs = proxy.imageInfo.timestamp / 1_000_000L
        val rotation = proxy.imageInfo.rotationDegrees
        val raw: Bitmap = try {
            frameBitmap(proxy)
        } finally {
            proxy.close()
        }
        val frame: Bitmap = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
        } else {
            raw
        }

        if (resetTracker) {
            resetTracker = false
            tracker.reset()
            hoopFinder.reset()
        }
        val r = rim
        val w = frame.width
        val h = frame.height
        val roi = if (r != null) Roi.aroundRim(r, w, h, BallDetector.INPUT) else Roi.full(w, h, BallDetector.INPUT)
        val found = try {
            det.detect(frame, roi)
        } catch (t: Throwable) {
            Log.e(TAG, "detect failed", t)
            Found(emptyList(), emptyList())
        }
        val balls = found.balls
        if (balls.isNotEmpty()) ballFrames++
        // Find the hoop automatically, and keep the box on it if the phone gets nudged.
        val autoBox: Box? = when {
            r == null && autoFind -> hoopFinder.find(found.hoops)
            r != null && autoRim -> hoopFinder.follow(r, found.hoops)
            else -> null
        }
        val call = tracker.update(tMs, balls, r)
        if (frame !== frameBmp) frame.recycle()

        val now = SystemClock.elapsedRealtime()
        if (lastFrameAt > 0) {
            val inst = 1000f / max(1L, now - lastFrameAt)
            fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.9f + inst * 0.1f
        }
        lastFrameAt = now

        val snap = OverlayView.Snapshot(w, h, balls, tracker.lastBall, tracker.trail.toList(), tracker.phase, roi)
        val fps = fpsEma
        runOnUiThread {
            overlay.update(snap)
            latestFps = fps
            if (r != null && fps > 0f) { fpsSum += fps; fpsCount++ }
            if (call != null && r === rim) addShot(call, Method.AUTO)
            if (autoBox != null) applyAutoRim(autoBox, first = r == null)
        }
    }

    private fun applyAutoRim(box: Box, first: Boolean) {
        if (overlay.isEditing) return
        if (first && (rim != null || !autoFind)) return // the player got there first
        rim = box
        overlay.rim = box
        overlay.settingRim = false
        if (first) {
            autoRim = true
            resetTracker = true
            hint.visibility = View.GONE
            Toast.makeText(this, "Found the hoop. Drag the box to adjust it if needed.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Session ----------------

    private fun addShot(result: Result, method: Method) {
        val now = System.currentTimeMillis()
        if (session.shots.isEmpty() && session.phantoms == 0) session = Session(now) // clock starts at the first shot
        session.add(now, result, shotType, method)
        overlay.flash(result)
        if (soundOn) {
            tone?.startTone(
                if (result == Result.MAKE) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_NACK,
                180,
            )
        }
        refresh()
    }

    private fun refresh() {
        val s = session
        makesTv.text = s.makes.toString()
        missesTv.text = (s.attempts - s.makes).toString()
        pctTv.text = if (s.attempts == 0) "–" else "${s.pct}"
        val secs = if (s.shots.isEmpty()) 0L else (System.currentTimeMillis() - s.startedAt) / 1000
        val last10 = s.last10Pct?.let { "$it%" } ?: "–"
        subline.text = "Streak ${s.currentStreak} · Best ${s.bestStreak} · Last 10 $last10 · ${secs / 60}:${"%02d".format(secs % 60)}"
        val lines = s.byType()
        chips.forEach { (type, btn) ->
            val l = lines.getValue(type)
            btn.text = if (l.attempted == 0) type.label else "${type.label} ${l.made}/${l.attempted}"
            val on = type == shotType
            btn.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.btn_ghost)
            btn.setTextColor(ContextCompat.getColor(this, if (on) R.color.amber else R.color.net))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            val err = detectorError
            val hot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = ContextCompat.getSystemService(this@TrackerActivity, PowerManager::class.java)
                val st = pm?.currentThermalStatus ?: 0
                this@TrackerActivity.hot = st >= PowerManager.THERMAL_STATUS_CRITICAL
                st >= PowerManager.THERMAL_STATUS_SEVERE
            } else false
            analysisExecutor.execute { ballRate = ballFrames; ballFrames = 0 }
            status.text = when {
                err != null -> "Model error"
                detector == null -> "Loading…"
                else -> "$backend · ${latestFps.toInt()}/s · ball ${ballRate}/s" + if (hot) " · HOT" else ""
            }
            status.setTextColor(ContextCompat.getColor(this@TrackerActivity, if (hot || err != null) R.color.miss else R.color.muted))
            ui.postDelayed(this, 1000)
        }
    }

    private fun endSession() {
        val s = session
        val now = System.currentTimeMillis()
        if (s.shots.isEmpty()) {
            Toast.makeText(this, "No shots yet", Toast.LENGTH_SHORT).show()
            return
        }
        s.endedAt = now
        val avgFps = if (fpsCount > 0) fpsSum / fpsCount else 0f
        val json = s.toJson(now, avgFps, BallDetector.MODEL_NAME)
        val saved = runCatching {
            val dir = File(getExternalFilesDir(null), "sessions").apply { mkdirs() }
            File(dir, "session-${s.startedAt}.json").apply { writeText(json) }
        }.getOrNull()

        val types = s.byType().filterValues { it.attempted > 0 }
            .entries.joinToString("   ") { (t, l) -> "${t.label} ${l.made}/${l.attempted}" }
        val cam = s.cameraAccuracy?.let {
            "Camera: ${s.cameraRight} right, ${s.cameraWrong} wrong ($it%)\n" +
                "  ${s.cameraFlipped} flipped · ${s.cameraMissed} it didn't see · ${s.phantoms} it made up"
        } ?: "Camera: no auto calls"
        val msg = buildString {
            append("${s.makes} of ${s.attempts}  (${s.pct}%)\n")
            append("$types\n")
            append("Best streak ${s.bestStreak} · ${s.minutes(now)} min\n\n")
            append("$cam\n")
            append("Speed: ${avgFps.toInt()} checks/sec on $backend")
            if (saved == null) append("\n\n(Couldn't save the session file)")
        }
        AlertDialog.Builder(this)
            .setTitle("Session")
            .setMessage(msg)
            .setPositiveButton("Save to Swish Quest") { _, _ ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, json))
                finish()
            }
            .setNegativeButton("Keep going") { _, _ -> s.endedAt = null }
            .setNeutralButton("Discard") { _, _ -> confirmDiscard(s) }
            .show()
    }

    private fun confirmDiscard(s: Session) {
        AlertDialog.Builder(this)
            .setTitle("Discard this session?")
            .setMessage("${s.makes} of ${s.attempts} won't be saved to Swish Quest.")
            .setPositiveButton("Discard") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("Keep it") { _, _ -> s.endedAt = null }
            .show()
    }

    companion object {
        private const val TAG = "SwishVision"
        const val EXTRA_SHOT_TYPE = "shotType"
        const val EXTRA_RESULT = "sessionJson"
    }
}
