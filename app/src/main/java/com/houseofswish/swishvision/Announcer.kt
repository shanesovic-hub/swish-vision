package com.houseofswish.swishvision

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.houseofswish.swishvision.core.AnnouncerScript
import java.util.Locale

/**
 * The announcer. After the swish, the phone's voice says the make count, then one of Shane's
 * recorded calls plays (heating up, on fire, bonus, from downtown, Bang ...). See [AnnouncerScript]
 * for which call plays when. A new shot, Undo or Wrong call cuts off whatever is still playing.
 */
class Announcer(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val AFTER_SWISH_MS = 420L   // let the swish land first
        private const val AFTER_BUZZER_MS = 500L  // the buzzer is 0.45 s
        private const val AFTER_COUNT_MS = 60L    // short breath between the count and the call
        private const val COUNT_MAX_MS = 2000L    // if the voice never reports it finished the count
        // Google TTS voices that sound most like a sports announcer, in order of preference.
        private val PREFERRED = listOf("en-us-x-iom-local", "en-us-x-tpd-local", "en-us-x-iol-local", "en-us-x-iom-network")

        private val CLIPS = mapOf(
            "bonus_1" to R.raw.bonus_1,
            "bonus_2" to R.raw.bonus_2,
            "bonus_3" to R.raw.bonus_3,
            "bonus_4" to R.raw.bonus_4,
            "bonus_5" to R.raw.bonus_5,
            "call_bang" to R.raw.call_bang,
            "call_boom_dynamite" to R.raw.call_boom_dynamite,
            "call_boomshakalaka" to R.raw.call_boomshakalaka,
            "call_booyah" to R.raw.call_booyah,
            "call_bottom_of_net" to R.raw.call_bottom_of_net,
            "call_buckets" to R.raw.call_buckets,
            "call_cold_blooded" to R.raw.call_cold_blooded,
            "call_cool_pillow" to R.raw.call_cool_pillow,
            "call_dagger" to R.raw.call_dagger,
            "call_hocus_pocus" to R.raw.call_hocus_pocus,
            "call_it_might_be" to R.raw.call_it_might_be,
            "call_money" to R.raw.call_money,
            "call_no_regard" to R.raw.call_no_regard,
            "call_nothing_but_net" to R.raw.call_nothing_but_net,
            "call_popcorn" to R.raw.call_popcorn,
            "call_right_between_eyes" to R.raw.call_right_between_eyes,
            "call_rye_bread" to R.raw.call_rye_bread,
            "call_splash" to R.raw.call_splash,
            "call_straight_cash" to R.raw.call_straight_cash,
            "call_yes" to R.raw.call_yes,
            "miss_not_so_fast" to R.raw.miss_not_so_fast,
            "miss_you_got_this" to R.raw.miss_you_got_this,
            "start_practice" to R.raw.start_practice,
            "streak_dare_en_fuego" to R.raw.streak_dare_en_fuego,
            "streak_en_fuego" to R.raw.streak_en_fuego,
            "streak_heating_up" to R.raw.streak_heating_up,
            "streak_on_fire" to R.raw.streak_on_fire,
            "three_downtown" to R.raw.three_downtown,
            "three_parking_lot" to R.raw.three_parking_lot,
            "three_way_downtown" to R.raw.three_way_downtown,
        )
    }

    private val app = context.applicationContext
    private val tts = TextToSpeech(app, this)
    private val handler = Handler(Looper.getMainLooper())
    private val script = AnnouncerScript()
    @Volatile private var ready = false

    private val pool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
    }.getOrNull()
    private val soundIds = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private var stream = 0

    // Every new shot gets a new number; anything still scheduled for an older shot does nothing.
    private var token = 0
    private var pending: AnnouncerScript.Call? = null
    private var callStartedFor = -1

    init {
        pool?.let { p ->
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
            CLIPS.forEach { (name, res) -> runCatching { soundIds[name] = p.load(app, res, 1) } }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val lang = runCatching { tts.setLanguage(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val voices = tts.voices.orEmpty()
            val pick = PREFERRED.firstNotNullOfOrNull { name -> voices.firstOrNull { it.name == name } }
                ?: voices.filter { it.locale.language == "en" && it.locale.country == "US" && !it.isNetworkConnectionRequired }
                    .maxByOrNull { it.quality }
            if (pick != null) tts.voice = pick
        }
        tts.setPitch(1.0f)
        tts.setSpeechRate(1.12f) // a little quicker, like play-by-play
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { handler.post { countFinished(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { handler.post { countFinished(utteranceId) } }
            override fun onError(utteranceId: String, errorCode: Int) { handler.post { countFinished(utteranceId) } }
            override fun onStop(utteranceId: String, interrupted: Boolean) {} // a newer shot took over
        })
        ready = true
    }

    /** Call right after the swish. [makes] and [streak] include this make. */
    fun make(makes: Int, streak: Int, isThree: Boolean) {
        val call = script.forMake(makes, streak, isThree)
        val t = newShot()
        pending = call
        handler.postDelayed({
            if (t != token) return@postDelayed
            if (ready && runCatching { tts.speak(call.count, TextToSpeech.QUEUE_FLUSH, null, "count$t") }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.SUCCESS) {
                handler.postDelayed({ startCall(t) }, COUNT_MAX_MS) // safety net; normally onDone starts it
            } else {
                startCall(t)
            }
        }, AFTER_SWISH_MS)
    }

    /** Call right after the buzzer. [missesInRow] includes this miss; [endedStreak] is the make streak it ended. */
    fun miss(missesInRow: Int, endedStreak: Int) {
        val t = newShot()
        val clip = script.forMiss(missesInRow, endedStreak) ?: return
        handler.postDelayed({ if (t == token) play(clip, null) }, AFTER_BUZZER_MS)
    }

    /** The camera just took back a make ("Wrong call"). */
    fun takeBack() {
        newShot()
        play(AnnouncerScript.NOT_SO_FAST, null)
    }

    /** Tracking is ready to go. */
    fun intro() {
        newShot()
        play(AnnouncerScript.START, null)
    }

    /** Undo, or the announcer was switched off: cut off anything still playing or waiting. */
    fun quiet() {
        newShot()
    }

    fun shutdown() {
        quiet()
        runCatching { tts.shutdown() }
        runCatching { pool?.release() }
    }

    private fun newShot(): Int {
        token++
        pending = null
        handler.removeCallbacksAndMessages(null)
        runCatching { tts.stop() }
        if (stream != 0) runCatching { pool?.stop(stream) }
        stream = 0
        return token
    }

    private fun countFinished(utteranceId: String) {
        if (utteranceId == "count$token") handler.postDelayed({ startCall(utteranceId.removePrefix("count").toInt()) }, AFTER_COUNT_MS)
    }

    private fun startCall(t: Int) {
        if (t != token || callStartedFor == t) return
        callStartedFor = t
        val call = pending ?: return
        play(call.clip, call.say)
    }

    /** Play a recorded clip; if it can't be played, the phone voice says the words instead. */
    private fun play(clip: String?, say: String?) {
        if (clip != null) {
            val id = soundIds[clip]
            val isLoaded = id != null && synchronized(loaded) { id in loaded }
            if (isLoaded && pool != null) {
                stream = runCatching { pool.play(id!!, 1f, 1f, 1, 0, 1f) }.getOrDefault(0)
                if (stream != 0) return
            }
        }
        val words = say ?: clip?.let { AnnouncerScript.TEXT[it] } ?: return
        if (ready) runCatching { tts.speak(words, TextToSpeech.QUEUE_FLUSH, null, "say$token") }
    }
}
