package com.houseofswish.swishvision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.houseofswish.swishvision.core.Box
import com.houseofswish.swishvision.core.Detection
import com.houseofswish.swishvision.core.Phase
import com.houseofswish.swishvision.core.Result
import com.houseofswish.swishvision.core.Roi
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps a lightweight record of what the camera and tracker saw, so wrong calls can be reviewed.
 *
 * - frames.jsonl: every analysed frame (rim, search area, every ball/hoop detection, tracker phase, call)
 * - events.jsonl: every call and every player correction (+Make, +Miss, Undo, Wrong call, shot type)
 * - NNN_<event>.jpg: a picture sheet of the ~2 s around each call / correction, each tile showing what
 *   the detector saw (ball circles, rim box) and what the tracker was thinking
 *
 * - train/<t>.jpg: full-size pictures of exactly what the detector looked at (its 640 x 640 input),
 *   ~7 a second around each shot, more around the player's corrections. Used to train the detector on
 *   this hoop. <t> matches the frame's "t" in frames.jsonl. Size-capped.
 *
 * No video is kept. When the session ends everything goes to Downloads/SwishVision as two zips:
 * swishvision-review_<date>.zip (the record and sheets) and swishvision-train_<date>.zip (the pictures).
 * All methods must be called on the analysis thread.
 */
class ReviewRecorder(private val dir: File) {

    private class Thumb(val t: Long, val bmp: Bitmap, val phase: Phase, val nBalls: Int)
    private class Pic(val t: Long, val jpg: ByteArray)
    private class PicWindow(val id: Int, val until: Long, var fix: Boolean)
    private class Capture(
        val id: Int,
        var label: String,
        val shotType: String,
        val t0: Long,
        val until: Long,
        val tiles: ArrayList<Thumb>,
        val scene: Bitmap?,
    )

    companion object {
        private const val TILE = 180
        private const val RING_MS = 6000L
        private const val AUTO_BEFORE_MS = 1700L
        private const val AUTO_AFTER_MS = 700L
        private const val MANUAL_BEFORE_MS = 5000L
        private const val MAX_TILES = 42

        // Training pictures
        private const val PIC_EVERY_MS = 150L
        private const val PIC_RING_MS = 4000L
        private const val PIC_QUALITY = 80
        private const val PIC_AUTO_BEFORE_MS = 1200L
        private const val PIC_AUTO_AFTER_MS = 500L
        private const val PIC_FIX_BEFORE_MS = 3500L
        private const val AUTO_PIC_BUDGET = 9_000_000L // pictures around ordinary camera calls
        private const val FIX_PIC_BUDGET = 9_000_000L  // pictures around the player's corrections (most useful)
        private const val TRAIN = "train"
    }

    private val frames = BufferedWriter(FileWriter(File(dir, "frames.jsonl")))
    private val events = BufferedWriter(FileWriter(File(dir, "events.jsonl")))
    private val ring = ArrayDeque<Thumb>()
    private val open = ArrayList<Capture>()
    private val written = HashMap<Int, File>() // capture id -> sheet file
    private var nextId = 1
    private var lastAutoId = 0
    private var lastT = 0L
    private var frameCount = 0L
    private var pendingManual: Pair<String, String>? = null // label, shot type
    var sheets = 0
        private set

    private val picDir = File(dir, TRAIN).apply { mkdirs() }
    private val picRing = ArrayDeque<Pic>()
    private val picWindows = ArrayList<PicWindow>()
    private val picSaved = HashSet<Long>()
    private val jpgBuf = ByteArrayOutputStream(96 * 1024)
    private var lastPicT = Long.MIN_VALUE / 2 // (not MIN_VALUE: t - MIN_VALUE overflows and no picture is ever taken)
    private var lastAutoT = 0L
    private var autoPicBytes = 0L
    private var fixPicBytes = 0L
    var pictures = 0
        private set
    var trainSaved = false
        private set

    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#F5A524") }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.parseColor("#4ADE80") }
    private val otherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#7DD3FC") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    private val textBg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val mark = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.parseColor("#F87171") }

    /** One analysed frame. [thumbSrc] is the detector's square model input (the search area). */
    fun onFrame(
        t: Long, frame: Bitmap, roi: Roi, rim: Box?, balls: List<Detection>, hoops: List<Detection>,
        tracked: Detection?, phase: Phase, call: Result?, shotType: String, thumbSrc: Bitmap,
        busy: Boolean = true, why: String = "",
    ) {
        lastT = t
        frameCount++
        writeFrame(t, roi, rim, balls, hoops, tracked, phase, call)
        if (busy || picWindows.isNotEmpty()) keepPicture(t, thumbSrc) else picWindows.removeAll { t >= it.until }

        // Thumbnails at ~15 per second (every other frame), plus always the frame of a call.
        if (frameCount % 2L == 0L || call != null) {
            val th = thumbnail(thumbSrc, roi, rim, balls, tracked, phase)
            val thumb = Thumb(t, th, phase, balls.size)
            ring.addLast(thumb)
            while (ring.isNotEmpty() && t - ring.first().t > RING_MS) ring.removeFirst()
            for (c in open) c.tiles += thumb
        }

        if (call != null) {
            val id = nextId++
            lastAutoId = id
            val label = "auto_" + call.name
            event(t, label, shotType, id, why)
            lastAutoT = t
            picRing.filter { t - it.t <= PIC_AUTO_BEFORE_MS }.forEach { savePic(it, fix = false) }
            picWindows += PicWindow(id, t + PIC_AUTO_AFTER_MS, fix = false)
            open += Capture(id, label, shotType, t, t + AUTO_AFTER_MS,
                ArrayList(ring.filter { t - it.t <= AUTO_BEFORE_MS }), scene(frame))
        }
        pendingManual?.let { (label, type) ->
            // A shot the camera missed: the shot happened in the last few seconds.
            pendingManual = null
            val id = nextId++
            event(t, label, type, id)
            picRing.filter { t - it.t <= PIC_FIX_BEFORE_MS }.forEach { savePic(it, fix = true) }
            val tiles = ArrayList(ring.filter { t - it.t <= MANUAL_BEFORE_MS })
            writeSheet(Capture(id, label, type, t, t, tiles, scene(frame)))
        }
        val done = open.filter { t >= it.until }
        for (c in done) {
            open.remove(c)
            writeSheet(c)
        }
    }

    /** Player pressed +Make / +Miss: capture the last few seconds on the next frame. */
    fun manualShot(made: Boolean, shotType: String) {
        pendingManual = Pair(if (made) "manual_MAKE" else "manual_MISS", shotType)
    }

    /** Player pressed Wrong call: the last automatic call was wrong. */
    fun wrongCall() = flagLastAuto("WRONG")

    /** Player pressed Undo on an automatic call: it was a shot that never happened. */
    fun undoneAuto() = flagLastAuto("UNDONE")

    fun note(kind: String, detail: String = "") {
        event(lastT, kind, detail, 0)
    }

    private fun flagLastAuto(flag: String) {
        val id = lastAutoId
        if (id == 0) return
        event(lastT, flag.lowercase(Locale.US), "", id)
        // A wrong camera call is a moment worth training on: keep its pictures even if the ordinary budget is used up.
        val t0 = lastAutoT
        picRing.filter { it.t >= t0 - PIC_AUTO_BEFORE_MS && it.t <= t0 + PIC_AUTO_AFTER_MS }.forEach { savePic(it, fix = true) }
        picWindows.firstOrNull { it.id == id }?.fix = true
        open.firstOrNull { it.id == id }?.let { it.label = it.label + "_" + flag; return }
        written[id]?.let { f ->
            val renamed = File(f.parentFile, f.name.removeSuffix(".jpg") + "_" + flag + ".jpg")
            if (f.renameTo(renamed)) written[id] = renamed
        }
    }

    /** Leaving without a session: throw the record away. */
    fun abandon() {
        runCatching { frames.close() }
        runCatching { events.close() }
        dir.deleteRecursively()
    }

    /** Close out the session and bundle everything into Downloads/SwishVision. Returns the file name, or null. */
    fun finish(context: Context, summaryJson: String?): String? {
        for (c in open.toList()) writeSheet(c)
        open.clear()
        runCatching { frames.close() }
        runCatching { events.close() }
        if (summaryJson != null) runCatching {
            val sum = org.json.JSONObject(summaryJson)
                .put("appVersion", BuildConfigLite.versionName(context))
                .put("trainingPictures", pictures)
            File(dir, "summary.json").writeText(sum.toString())
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val name = "swishvision-review_$stamp.zip"
        val record = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        val ok = runCatching { exportZip(context, name, record, "") }.getOrDefault(false)
        val pics = picDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        trainSaved = pics.isNotEmpty() &&
            runCatching { exportZip(context, "swishvision-train_$stamp.zip", pics, "$TRAIN/") }.getOrDefault(false)
        dir.deleteRecursively()
        return if (ok) name else null
    }

    // ---------------- internals ----------------

    private fun writeFrame(
        t: Long, roi: Roi, rim: Box?, balls: List<Detection>, hoops: List<Detection>,
        tracked: Detection?, phase: Phase, call: Result?,
    ) {
        val sb = StringBuilder(160)
        sb.append("{\"t\":").append(t)
        sb.append(",\"roi\":[").append(roi.x).append(',').append(roi.y).append(',').append(roi.w).append(',').append(roi.h).append(']')
        if (rim != null) sb.append(",\"rim\":[").append(f(rim.x)).append(',').append(f(rim.y)).append(',').append(f(rim.w)).append(',').append(f(rim.h)).append(']')
        sb.append(",\"b\":["); dets(sb, balls); sb.append(']')
        if (hoops.isNotEmpty()) { sb.append(",\"h\":["); dets(sb, hoops); sb.append(']') }
        if (tracked != null) sb.append(",\"k\":").append(balls.indexOfFirst { it === tracked })
        sb.append(",\"p\":\"").append(phase.name).append('"')
        if (call != null) sb.append(",\"c\":\"").append(call.name).append('"')
        sb.append("}\n")
        frames.write(sb.toString())
    }

    private fun dets(sb: StringBuilder, list: List<Detection>) {
        list.forEachIndexed { i, d ->
            if (i > 0) sb.append(',')
            sb.append('[').append(f(d.cx)).append(',').append(f(d.cy)).append(',').append(f(d.w)).append(',').append(f(d.h))
                .append(',').append(String.format(Locale.US, "%.2f", d.score)).append(']')
        }
    }

    private fun f(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun event(t: Long, kind: String, detail: String, id: Int, why: String = "") {
        val w = if (why.isEmpty()) "" else ",\"why\":\"$why\""
        events.write("{\"t\":$t,\"e\":\"$kind\",\"d\":\"$detail\",\"id\":$id$w}\n")
        events.flush()
    }

    private fun thumbnail(src: Bitmap, roi: Roi, rim: Box?, balls: List<Detection>, tracked: Detection?, phase: Phase): Bitmap {
        val th = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGB_565)
        val c = Canvas(th)
        c.drawBitmap(src, null, Rect(0, 0, TILE, TILE), filter)
        val k = roi.scale * TILE / src.width.toFloat() // frame px -> tile px
        fun tx(x: Float) = (x - roi.x) * k
        fun ty(y: Float) = (y - roi.y) * k
        rim?.let { c.drawRect(RectF(tx(it.x), ty(it.y), tx(it.right), ty(it.bottom)), rimPaint) }
        for (d in balls) {
            c.drawCircle(tx(d.cx), ty(d.cy), max(3f, d.w * k / 2f), if (d === tracked) ballPaint else otherPaint)
        }
        return th
    }

    private fun scene(frame: Bitmap): Bitmap? = runCatching {
        val w = 480
        val h = max(1, (frame.height * w / frame.width.toFloat()).toInt())
        Bitmap.createScaledBitmap(frame, w, h, true).copy(Bitmap.Config.RGB_565, false)
    }.getOrNull()

    private fun writeSheet(c: Capture) {
        val all = c.tiles.distinctBy { it.t }.sortedBy { it.t }
        if (all.isEmpty() && c.scene == null) return
        val step = max(1, ceil(all.size / MAX_TILES.toDouble()).toInt())
        val tiles = all.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
        all.lastOrNull { it.t <= c.t0 }?.let { if (it !in tiles) tiles += it } // always include the call frame
        tiles.sortBy { it.t }
        val cols = 7
        val rows = max(1, ceil(tiles.size / cols.toDouble()).toInt())
        val header = 34
        val sceneH = if (c.scene != null) 270 else 0
        val sheet = Bitmap.createBitmap(cols * TILE, header + sceneH + rows * TILE, Bitmap.Config.RGB_565)
        val cv = Canvas(sheet)
        cv.drawColor(Color.parseColor("#0E1216"))
        cv.drawText("#${c.id}  ${c.label}  ${c.shotType}   t=${c.t0}", 8f, 24f, text)
        c.scene?.let { cv.drawBitmap(it, null, Rect(0, header, min(cols * TILE, 480), header + 270), filter) }
        val callTile = tiles.lastOrNull { it.t <= c.t0 }
        tiles.forEachIndexed { i, th ->
            val x = (i % cols) * TILE
            val y = header + sceneH + (i / cols) * TILE
            cv.drawBitmap(th.bmp, x.toFloat(), y.toFloat(), null)
            val label = String.format(Locale.US, "%+.1fs %s", (th.t - c.t0) / 1000f, phaseShort(th.phase))
            cv.drawRect(x.toFloat(), (y + TILE - 20).toFloat(), (x + TILE).toFloat(), (y + TILE).toFloat(), textBg)
            cv.drawText(label, (x + 4).toFloat(), (y + TILE - 5).toFloat(), text)
            if (th === callTile) cv.drawRect(x + 2f, y + 2f, x + TILE - 2f, y + TILE - 2f, mark)
        }
        val file = File(dir, String.format(Locale.US, "%03d_%s.jpg", c.id, c.label))
        FileOutputStream(file).use { sheet.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        sheet.recycle()
        written[c.id] = file
        sheets++
    }

    private fun phaseShort(p: Phase) = when (p) {
        Phase.IDLE -> "idle"
        Phase.ARMED -> "UP"
        Phase.PENDING_MAKE -> "IN?"
        Phase.COOLDOWN -> "cool"
    }

    /** Every [PIC_EVERY_MS], keep a full-size JPEG of the detector's input in a short ring. */
    private fun keepPicture(t: Long, src: Bitmap) {
        if (t - lastPicT >= PIC_EVERY_MS) {
            lastPicT = t
            runCatching {
                jpgBuf.reset()
                src.compress(Bitmap.CompressFormat.JPEG, PIC_QUALITY, jpgBuf)
                val pic = Pic(t, jpgBuf.toByteArray())
                picRing.addLast(pic)
                while (picRing.isNotEmpty() && t - picRing.first().t > PIC_RING_MS) picRing.removeFirst()
                for (w in picWindows) savePic(pic, w.fix)
            }
        }
        picWindows.removeAll { t >= it.until }
    }

    private fun savePic(p: Pic, fix: Boolean) {
        if (p.t in picSaved) return
        val size = p.jpg.size.toLong()
        if (fix) {
            if (fixPicBytes + size > FIX_PIC_BUDGET) return
            fixPicBytes += size
        } else {
            if (autoPicBytes + size > AUTO_PIC_BUDGET) return
            autoPicBytes += size
        }
        runCatching { File(picDir, "${p.t}.jpg").writeBytes(p.jpg) }.onSuccess {
            picSaved += p.t
            pictures++
        }
    }

    private fun exportZip(context: Context, name: String, files: List<File>, prefix: String): Boolean {
        if (files.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SwishVision")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { zip(files, prefix, it) } ?: return false
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return true
        }
        val out = File(context.getExternalFilesDir(null), name)
        FileOutputStream(out).use { zip(files, prefix, it) }
        return true
    }

    private fun zip(files: List<File>, prefix: String, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            for (f in files) {
                z.setLevel(if (f.name.endsWith(".jpg")) 0 else 6) // pictures are already compressed
                z.putNextEntry(ZipEntry(prefix + f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }
}
