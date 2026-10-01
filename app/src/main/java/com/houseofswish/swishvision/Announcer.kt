package com.houseofswish.swishvision

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.houseofswish.swishvision.core.AnnouncerScript
import java.util.Locale
import java.util.Random

/**
 * The announcer: calls out the make count after the swish, streak milestones
 * (heating up, on fire, bonus, double bonus ...) and the occasional classic call.
 * Uses the phone's built-in text-to-speech voice.
 */
class Announcer(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val AFTER_SWISH_MS = 420L // let the swish land first
        // Google TTS voices that sound most like a sports announcer, in order of preference.
        private val PREFERRED = listOf("en-us-x-iom-local", "en-us-x-tpd-local", "en-us-x-iol-local", "en-us-x-iom-network")
    }

    private val tts = TextToSpeech(context.applicationContext, this)
    private val handler = Handler(Looper.getMainLooper())
    private val script = AnnouncerScript()
    private val rnd = Random()
    @Volatile private var ready = false

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
        ready = true
    }

    /** Call right after the swish. [makes] and [streak] include this make. */
    fun make(makes: Int, streak: Int, player: String?, isThree: Boolean) {
        if (!ready) return
        val line = script.forMake(makes, streak, cleanName(player), isThree, rnd.nextDouble(), rnd.nextInt(1000))
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ runCatching { tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "make") } }, AFTER_SWISH_MS)
    }

    /** A miss or a correction: cut off anything still waiting to be said. */
    fun quiet() {
        handler.removeCallbacksAndMessages(null)
        runCatching { tts.stop() }
    }

    fun shutdown() {
        quiet()
        runCatching { tts.shutdown() }
    }

    /** Swish Quest stores names HTML-escaped; turn them back into plain text for speech. */
    private fun cleanName(name: String?): String? = name
        ?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&#x27;", "'")
        ?.replace("&quot;", "\"")?.replace("&lt;", "")?.replace("&gt;", "")
        ?.takeIf { it.isNotBlank() }
}
