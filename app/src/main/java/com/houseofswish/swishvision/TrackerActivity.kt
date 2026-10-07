package com.houseofswish.swishvision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.SoundPool
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
import com.houseofswish.swishvision.core.Detection
import com.houseofswish.swishvision.core.HoopFinder
import com.houseofswish.swishvision.core.Method
import com.houseofswish.swishvision.core.Phase
import com.houseofswish.swishvision.core.Result
import com.houseofswish.swishvision.core.Roi
import com.houseofswish.swishvision.core.Session
import com.houseofswish.swishvision.core.ShotTracker
import com.houseofswish.swishvision.core.ShotType
import com.houseofswish.swishvision.core.SpotFinder
import com.houseofswish.swishvision.core.TrackPoint
import com.houseofswish.swishvision.core.TrackerConfig
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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
    @Volatile private var experimental = false // the retrained detector, opt-in
    @Volatile private var tracker = ShotTracker()
    private val hoopFinder = HoopFinder()
    // Shooter spots (shadow mode): a person finder runs a few times a second on its own thread and the
    // review record keeps where everyone stood. The shot-type chips stay the answer for now.
    private val personExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var personDet: PersonDetector? = null
    private val personBusy = AtomicBoolean(false)
    private val peopleFound = AtomicReference<Pair<Long, List<Detection>>?>(null)
    @Volatile private var peopleShown: List<Detection> = emptyList()
    private var lastPersonAt = Long.MIN_VALUE / 2
    private var frameNoted = ""
    private val spotFinder = SpotFinder()        // where each shot was taken from (analysis thread)
    private var prevPhase = Phase.IDLE
    private var shotStartT = Long.MIN_VALUE / 2  // camera time the current shot went up
    private var lastFrameT = 0L
    private val ballPath = ArrayDeque<TrackPoint>() // the tracked ball over the last few seconds (finds the shooter)
    private var recorder: ReviewRecorder? = null // review record of what the camera saw (analysis thread)
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
    @Volatile private var hot = false          // severe: slow down between shots even more
    @Volatile private var critical = false     // critical: every other frame even during a shot
    @Volatile private var autoRim = false      // rim came from the hoop finder (and follows the hoop)
    @Volatile private var autoFind = true      // look for the hoop while no rim is set
    @Volatile private var ballRate = 0
    @Volatile private var shotTypeKey = ShotType.FT.key
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    private var showingAdvice = false
    private var frameNo = 0L
    private var lastNearRimAt = Long.MIN_VALUE / 2 // camera time a ball was last seen near the rim

    // --- main thread state ---
    private var session = Session(System.currentTimeMillis())
    private var shotType = ShotType.FT
    private var autoSpots = false     // the camera picks the shot type from where the shooter stands
    private var ftSpotSet = false
    private var lastSpotText = ""
    private var soundOn = true
    // Game sounds: a net swish for makes, an arena buzzer for misses.
    private var sounds: SoundPool? = null
    private var announcer: Announcer? = null
    private var announcerOn = true
    private var introPlayed = false
    private var playerName: String? = null
    private var swishId = 0
    private var buzzerId = 0
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
        playerName = intent.getStringExtra(EXTRA_PLAYER)
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
            playIntro()
            if (hint.visibility == View.VISIBLE) {
                Toast.makeText(this, "Drag the box to move it, or a corner to resize", Toast.LENGTH_LONG).show()
            }
            hint.visibility = View.GONE
        }
        shotTypeKey = shotType.key
        chips.forEach { (type, btn) ->
            btn.setOnClickListener {
                shotType = type
                shotTypeKey = type.key
                if (autoSpots) {
                    // Auto spots: tapping a chip fixes where the last shot was taken from.
                    val last = session.shots.lastOrNull()
                    if (last != null && last.type != type) {
                        session.retypeLast(type)
                        onAnalysis { recorder?.note("retype", type.key) }
                        Toast.makeText(this, "Last shot changed to ${type.label}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    onAnalysis { recorder?.note("shot_type", type.key) }
                }
                refresh()
            }
        }
        val prefs = getSharedPreferences("swishvision", MODE_PRIVATE)
        autoSpots = prefs.getBoolean("autoSpots", false)
        val autoBtn = findViewById<TextView>(R.id.autoSpots)
        val ftBtn = findViewById<Button>(R.id.setFtSpot)
        autoBtn.setOnClickListener {
            autoSpots = !autoSpots
            prefs.edit().putBoolean("autoSpots", autoSpots).apply()
            val on = autoSpots
            onAnalysis { recorder?.note("auto_spots", if (on) "on" else "off") }
            Toast.makeText(this,
                if (on) "Auto spot ON: the camera picks the shot type from where you stand. Wrong? Tap the right chip." +
                    (if (ftSpotSet) "" else " Tap SET FT SPOT, then stand on the free-throw line so it knows free throws.")
                else "Auto spot off: pick the shot type with the chips", Toast.LENGTH_LONG).show()
            refresh()
        }
        ftBtn.setOnClickListener {
            // A 5 second countdown, so a player on their own can tap it and walk to the line.
            ftBtn.isEnabled = false
            for (i in 5 downTo 1) ui.postDelayed({ ftBtn.text = "Get on the line… $i" }, (5 - i) * 1000L)
            ui.postDelayed({ measureFtSpot(ftBtn) }, 5000L)
        }
        for (id in listOf(R.id.makes, R.id.misses, R.id.pct)) findViewById<View>(id).setOnClickListener { showChart() }
        findViewById<Button>(R.id.addMake).setOnClickListener { addShot(Result.MAKE, Method.MANUAL) }
        findViewById<Button>(R.id.addMiss).setOnClickListener { addShot(Result.MISS, Method.MANUAL) }
        findViewById<Button>(R.id.undo).setOnClickListener {
            val undone = session.undo()
            if (undone != null) announcer?.quiet()
            if (undone != null && undone.method == Method.AUTO && !undone.flipped) onAnalysis { recorder?.undoneAuto() }
            refresh()
        }
        findViewById<Button>(R.id.wrong).setOnClickListener {
            session.flipLast()?.let { shot ->
                // A make taken back: "Not so fast, my friend."
                if (shot.result == Result.MISS && announcerOn) announcer?.takeBack() else announcer?.quiet()
                overlay.flash(shot.result)
                if (shot.method == Method.AUTO) onAnalysis { recorder?.wrongCall() }
            }
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
        experimental = getSharedPreferences("swishvision", MODE_PRIVATE).getBoolean("experimentalCamera", false)
        val expBtn = findViewById<TextView>(R.id.expBtn)
        expBtn.alpha = if (experimental) 1f else 0.35f
        expBtn.setOnClickListener {
            val on = !experimental
            experimental = on
            getSharedPreferences("swishvision", MODE_PRIVATE).edit().putBoolean("experimentalCamera", on).apply()
            expBtn.alpha = if (on) 1f else 0.35f
            onAnalysis {
                // Swap detectors on the analysis thread (the GPU delegate lives there).
                runCatching {
                    val d = BallDetector(this, on)
                    detector?.close()
                    detector = d
                    tracker = ShotTracker(TrackerConfig.forCamera(on)) // each detector has its own tuned rules
                    backend = d.backend
                    recorder?.note("camera", d.modelName)
                }.onFailure { Log.e(TAG, "detector swap failed", it) }
            }
            Toast.makeText(this, if (on) "Experimental camera ON (new trained detector)" else "Experimental camera off (standard detector)", Toast.LENGTH_LONG).show()
        }
        val announcerBtn = findViewById<TextView>(R.id.announcerBtn)
        announcerBtn.setOnClickListener {
            announcerOn = !announcerOn
            announcerBtn.alpha = if (announcerOn) 1f else 0.35f
            if (!announcerOn) announcer?.quiet()
            Toast.makeText(this, if (announcerOn) "Announcer on" else "Announcer off", Toast.LENGTH_SHORT).show()
        }
        announcer = runCatching { Announcer(this) }.getOrNull()
        soundBtn.setOnClickListener {
            soundOn = !soundOn
            soundBtn.text = if (soundOn) "🔊" else "🔇"
        }
        status.setOnClickListener { overlay.showDebug = !overlay.showDebug }
        hint.setOnClickListener {
            if (!hasCamera()) askCamera.launch(Manifest.permission.CAMERA)
        }

        sounds = runCatching {
            SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
        }.getOrNull()?.also { pool ->
            swishId = pool.load(this, R.raw.swish, 1)
            buzzerId = pool.load(this, R.raw.buzzer, 1)
        }

        // Build the detector on the analysis thread: the GPU delegate must run where it was created.
        analysisExecutor.execute {
            try {
                val d = BallDetector(this, experimental)
                detector = d
                tracker = ShotTracker(TrackerConfig.forCamera(experimental))
                backend = d.backend
                recorder = runCatching {
                    ReviewRecorder(File(cacheDir, "review/" + System.currentTimeMillis()).apply { mkdirs() })
                }.getOrNull()
                personDet = runCatching { PersonDetector(this) }.onFailure { Log.w(TAG, "person finder", it) }.getOrNull()
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
        analysisExecutor.execute {
            recorder?.abandon() // only reached if the session wasn't ended with Save or Discard
            recorder = null
            detector?.close()
            detector = null
            val pd = personDet
            personDet = null
            if (pd != null) runCatching { personExecutor.execute { pd.close() } }
            personExecutor.shutdown()
        }
        analysisExecutor.shutdown()
        sounds?.release()
        sounds = null
        announcer?.shutdown()
        announcer = null
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

    /**
     * Where is everyone standing? Between shots only (a shot in the air gets the phone's full attention),
     * about 4 times a second (2 when the phone is warm, 1 when it's hot). The frame is shrunk here and the
     * person finder runs on its own thread, so ball tracking never waits for it.
     */
    private fun findPeople(frame: Bitmap, tMs: Long, r: Box?) {
        val pd = personDet ?: return
        if (r == null || tracker.phase != Phase.IDLE) return
        val gap = if (critical) 1000L else if (hot) 500L else 250L
        if (tMs - lastPersonAt < gap || !personBusy.compareAndSet(false, true)) return
        lastPersonAt = tMs
        val input = try {
            pd.prepare(frame)
        } catch (t: Throwable) {
            personBusy.set(false); return
        }
        try {
            personExecutor.execute {
                try {
                    val found = pd.run(input)
                    peopleFound.set(tMs to found)
                    peopleShown = found
                } catch (t: Throwable) {
                    Log.w(TAG, "person finder", t)
                } finally {
                    personBusy.set(false)
                }
            }
        } catch (t: Throwable) {
            personBusy.set(false) // shutting down
        }
    }

    private fun analyze(proxy: ImageProxy) {
        val det = detector
        frameNo++
        val tMs = proxy.imageInfo.timestamp / 1_000_000L
        // Save heat (a hot phone slows everything down): between shots, check every other frame (every
        // third when the phone is hot). Once a ball is near the rim or a shot is up, check every frame.
        val busy = tracker.phase != Phase.IDLE || tMs - lastNearRimAt < 800
        val every = when {
            busy -> if (critical) 2L else 1L
            hot -> 3L
            else -> 2L
        }
        if (det == null || frameNo % every != 0L) {
            proxy.close()
            return
        }
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
        frameW = w
        frameH = h
        val roi = if (r != null) Roi.aroundRim(r, w, h, BallDetector.INPUT) else Roi.full(w, h, BallDetector.INPUT)
        val found = try {
            det.detect(frame, roi)
        } catch (t: Throwable) {
            Log.e(TAG, "detect failed", t)
            Found(emptyList(), emptyList())
        }
        val balls = found.balls
        if (balls.isNotEmpty()) ballFrames++
        if (r != null && balls.any { abs(it.cx - r.cx) < 3f * r.w && it.cy < r.bottom + r.w }) lastNearRimAt = tMs
        // Find the hoop automatically, and keep the box on it if the phone gets nudged.
        val autoBox: Box? = when {
            r == null && autoFind -> hoopFinder.find(found.hoops)
            r != null && autoRim -> hoopFinder.follow(r, found.hoops)
            else -> null
        }
        val call = tracker.update(tMs, balls, r)
        lastFrameT = tMs
        tracker.lastBall?.let { b -> ballPath.addLast(TrackPoint(tMs, b.cx, b.cy, b.w)) }
        while (ballPath.isNotEmpty() && tMs - ballPath.first().t > 4000) ballPath.removeFirst()
        val people = peopleFound.getAndSet(null)
        if (people != null) spotFinder.add(people.first, people.second, w, h)
        val phaseNow = tracker.phase
        if (prevPhase == Phase.IDLE && phaseNow != Phase.IDLE) shotStartT = tMs
        prevPhase = phaseNow
        val spot = if (call != null && r != null) {
            // Where the shooter stood just before the ball went up.
            val start = if (tMs - shotStartT in 0..6000) shotStartT else tMs - 1500
            runCatching { spotFinder.spotAt(start, r, ballPath.toList()) }.getOrNull()
        } else null
        recorder?.let { rec ->
            runCatching {
                rec.onFrame(tMs, frame, roi, r, balls, found.hoops, tracker.lastBall, tracker.phase, call, shotTypeKey, det.modelInput,
                    busy = tracker.phase != Phase.IDLE || tMs - lastNearRimAt < 800, why = if (call != null) tracker.lastWhy else "",
                    people = people)
                val size = "${w}x$h"
                if (frameNoted != size) { frameNoted = size; rec.note("frame", size) }
            }.onFailure { Log.w(TAG, "review recorder", it) }
        }
        if (spot != null) recorder?.note("spot", "${spot.type.key} %.1f ft".format(java.util.Locale.US, spot.feet))
        findPeople(frame, tMs, r)
        if (frame !== frameBmp) frame.recycle()

        val now = SystemClock.elapsedRealtime()
        if (lastFrameAt > 0) {
            val inst = 1000f / max(1L, now - lastFrameAt)
            fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.9f + inst * 0.1f
        }
        lastFrameAt = now

        val snap = OverlayView.Snapshot(w, h, balls, tracker.lastBall, tracker.trail.toList(), tracker.phase, roi, if (r != null) peopleShown else emptyList())
        val fps = fpsEma
        runOnUiThread {
            overlay.update(snap)
            latestFps = fps
            if (r != null && fps > 0f) { fpsSum += fps; fpsCount++ }
            if (call != null && r === rim) addShot(call, Method.AUTO, spot)
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
            playIntro()
            hint.visibility = View.GONE
            Toast.makeText(this, "Found the hoop. Drag the box to adjust it if needed.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Session ----------------

    /** "We talkin' bout practice" once, when tracking first gets going. */
    /** The player is on the free-throw line now: measure where they stand. */
    private fun measureFtSpot(ftBtn: Button) {
        ftBtn.text = "Measuring…"
        onAnalysis {
            val r = rim
            val ok = r != null && spotFinder.setFreeThrowSpot(lastFrameT, r)
            if (ok) recorder?.note("ft_spot", "%.2f".format(java.util.Locale.US, spotFinder.scale))
            ui.post {
                ftBtn.isEnabled = true
                if (ok) ftSpotSet = true
                Toast.makeText(this,
                    when {
                        ok -> "Free-throw spot set"
                        r == null -> "Find the rim first"
                        else -> "Couldn't see you. Stand on the free-throw line with your whole body (feet too) in view, then tap again."
                    }, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun playIntro() {
        if (introPlayed || session.shots.isNotEmpty()) return
        introPlayed = true
        if (announcerOn) announcer?.intro()
    }

    private fun addShot(result: Result, method: Method, spot: SpotFinder.Spot? = null) {
        val now = System.currentTimeMillis()
        if (session.shots.isEmpty() && session.phantoms == 0) session = Session(now) // clock starts at the first shot
        val endedStreak = if (result == Result.MISS) session.currentStreak else 0
        // Auto spot: where the camera saw the shooter. If it couldn't see them (or the player added the shot),
        // assume the same spot as the last shot.
        val type = if (autoSpots) spot?.type ?: session.shots.lastOrNull()?.type ?: shotType else shotType
        lastSpotText = if (!autoSpots) "" else if (spot != null) "📍 ${spot.type.label} ${spot.feet.toInt()} ft" else "📍 ${type.label} (same spot)"
        val shot = session.add(now, result, type, method)
        if (spot?.courtX != null && spot.courtY != null && !(autoSpots && spot.type != type)) {
            shot.courtX = spot.courtX; shot.courtY = spot.courtY
        }
        if (method == Method.MANUAL) {
            val key = type.key
            onAnalysis { recorder?.manualShot(result == Result.MAKE, key) } // the camera missed this one
        }
        overlay.flash(result)
        if (soundOn) {
            sounds?.play(if (result == Result.MAKE) swishId else buzzerId, 1f, 1f, 1, 0, 1f)
        }
        when {
            !announcerOn -> announcer?.quiet()
            result == Result.MAKE -> announcer?.make(session.makes, session.currentStreak, type == ShotType.THREE)
            else -> announcer?.miss(session.missesInRow, endedStreak)
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
        subline.text = "Streak ${s.currentStreak} · Best ${s.bestStreak} · Last 10 $last10 · ${secs / 60}:${"%02d".format(secs % 60)}" +
            if (lastSpotText.isNotEmpty() && s.shots.isNotEmpty()) " · $lastSpotText" else ""
        val lines = s.byType()
        findViewById<TextView>(R.id.autoSpots).alpha = if (autoSpots) 1f else 0.35f
        val ftBtn = findViewById<Button>(R.id.setFtSpot)
        // Shown while auto spots is on and the free-throw spot isn't set yet (or until the first shots).
        ftBtn.visibility = if (autoSpots && (!ftSpotSet || s.shots.isEmpty())) View.VISIBLE else View.GONE
        if (ftBtn.isEnabled) ftBtn.text = if (ftSpotSet) "📍 FT SPOT ✓" else "📍 SET FT SPOT"
        // In auto mode the lit chip is where the last shot came from (tap another to fix it).
        val litType = if (autoSpots) s.shots.lastOrNull()?.type else shotType
        chips.forEach { (type, btn) ->
            val l = lines.getValue(type)
            btn.text = if (l.attempted == 0) type.label else "${type.label} ${l.made}/${l.attempted}"
            val on = type == litType
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
                this@TrackerActivity.hot = st >= PowerManager.THERMAL_STATUS_SEVERE
                this@TrackerActivity.critical = st >= PowerManager.THERMAL_STATUS_CRITICAL
                st >= PowerManager.THERMAL_STATUS_SEVERE
            } else false
            analysisExecutor.execute { ballRate = ballFrames; ballFrames = 0 }
            status.text = when {
                err != null -> "Model error"
                detector == null -> "Loading…"
                else -> (if (experimental) "🧪 " else "") + "$backend · ${latestFps.toInt()}/s · ball ${ballRate}/s" + if (hot) " · HOT" else ""
            }
            status.setTextColor(ContextCompat.getColor(this@TrackerActivity, if (hot || err != null) R.color.miss else R.color.muted))
            setupCheck()
            ui.postDelayed(this, 1000)
        }
    }

    /** Before the first shot: warn about framing that makes calls unreliable. */
    private fun setupCheck() {
        val r = rim
        if (r == null) { showingAdvice = false; return } // the hoop-finding / draw-the-box hint owns the banner
        val fw = frameW
        val fh = frameH
        val advice = if (fw == 0 || session.shots.isNotEmpty()) null else setupAdvice(r, fw, fh)
        if (advice != null) {
            hint.text = advice
            hint.visibility = View.VISIBLE
            showingAdvice = true
        } else if (showingAdvice) {
            hint.visibility = View.GONE
            showingAdvice = false
        }
    }

    private fun setupAdvice(r: Box, fw: Int, fh: Int): String? {
        val u = r.w
        return when {
            u < fw * 0.03f -> "Setup: the rim looks small. Move the phone closer to the hoop."
            u > fw * 0.20f -> "Setup: the rim looks too big. Move the phone back so there's room around it."
            r.y < 2.5f * u -> "Setup: not enough room above the rim. Tilt the phone down a bit or move it back, so the ball is visible as it comes down."
            r.bottom > fh - 1.5f * u -> "Setup: the net is near the bottom edge. Tilt the phone up a little so the whole net is in view."
            r.x < 1.5f * u || r.right > fw - 1.5f * u -> "Setup: the rim is near the side edge. Turn the phone so the rim is more centered."
            else -> null
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
        val json = s.toJson(now, avgFps, detector?.modelName ?: BallDetector.MODEL_NAME)
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
            .setView(sessionView(msg, s))
            .setPositiveButton("Save to Swish Quest") { _, _ ->
                saveReview(json)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, json))
                finish()
            }
            .setNegativeButton("Keep going") { _, _ -> s.endedAt = null }
            .setNeutralButton("Discard") { _, _ -> confirmDiscard(s) }
            .show()
    }

    /** The session summary next to its shot chart (landscape: side by side). */
    private fun sessionView(msg: String, s: Session): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val chartBox = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        chartBox.addView(ShotChartView(this).apply { setShots(s.shots) })
        chartBox.addView(TextView(this).apply {
            textSize = 11f
            text = chartNote(s)
        })
        row.addView(chartBox, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
        row.addView(TextView(this).apply {
            text = msg
            textSize = 14f
            setPadding(pad, 0, 0, 0)
        }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return android.widget.ScrollView(this).apply { addView(row) }
    }

    private fun chartNote(s: Session): String {
        val onChart = com.houseofswish.swishvision.core.ShotChart.charted(s.shots).size
        val fts = s.byType().getValue(ShotType.FT)
        val ft = if (fts.attempted > 0) "Free throws ${fts.made}/${fts.attempted} (not on the chart). " else ""
        return when {
            !ftSpotSet && onChart == 0 -> "${ft}Turn on 📍 and set the FT spot to see where each shot came from."
            onChart < s.attempts - fts.attempted -> "$ft${s.attempts - fts.attempted - onChart} shots aren't on the chart (camera couldn't see where the shooter stood)."
            else -> ft
        }
    }

    /** Tap the score during a session: the shot chart so far. */
    private fun showChart() {
        val s = session
        AlertDialog.Builder(this)
            .setTitle("Shot chart · ${s.makes}/${s.attempts}")
            .setView(android.widget.ScrollView(this).apply {
                val pad = (16 * resources.displayMetrics.density).toInt()
                addView(android.widget.LinearLayout(this@TrackerActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                    addView(ShotChartView(this@TrackerActivity).apply { setShots(s.shots) })
                    addView(TextView(this@TrackerActivity).apply { textSize = 11f; text = chartNote(s) })
                })
            })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmDiscard(s: Session) {
        AlertDialog.Builder(this)
            .setTitle("Discard this session?")
            .setMessage("${s.makes} of ${s.attempts} won't be saved to Swish Quest.")
            .setPositiveButton("Discard") { _, _ ->
                saveReview(s.toJson(System.currentTimeMillis(), 0f, detector?.modelName ?: BallDetector.MODEL_NAME))
                setResult(RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("Keep it") { _, _ -> s.endedAt = null }
            .show()
    }

    private fun onAnalysis(block: () -> Unit) {
        runCatching { analysisExecutor.execute(block) }
    }

    /** Bundle the review record into Downloads/SwishVision (runs after this screen closes). */
    private fun saveReview(summaryJson: String) {
        val app = applicationContext
        onAnalysis {
            val rec = recorder
            recorder = null
            val name = rec?.finish(app, summaryJson)
            if (name != null) {
                val msg = if (rec?.trainSaved == true) "Saved 2 files to Downloads/SwishVision: review + training pictures"
                else "Review file saved to Downloads/SwishVision"
                Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        private const val TAG = "SwishVision"
        const val EXTRA_SHOT_TYPE = "shotType"
        const val EXTRA_PLAYER = "playerName"
        const val EXTRA_RESULT = "sessionJson"
    }
}
