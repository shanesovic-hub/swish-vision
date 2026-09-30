package com.houseofswish.swishvision

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Swish Quest, as an app. Shows the live Swish Quest site (same accounts, players and data)
 * and adds Swish Vision: a "Track with camera" button that opens the camera tracker and
 * saves the result through Swish Quest's own Log Session save.
 *
 * Camera sessions are delivered reliably: stored on the phone first, then handed to the page
 * only once Swish Quest is signed in and loaded, retried until the page confirms, and never
 * counted twice. They survive the app being closed or the page being reloaded.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME = "https://swish-quest.web.app/"
        private val OUR_HOSTS = setOf("swish-quest.web.app", "swish-quest.firebaseapp.com")
        private const val PREFS = "swishvision"
        private const val KEY_PENDING = "pendingSessions"
        private const val RETRY_MS = 1500L
        private const val RETRY_WINDOW_MS = 90_000L
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var offline: View
    private var bridgeJs = ""
    private val ui = Handler(Looper.getMainLooper())
    private var delivering = false
    private var deliverUntil = 0L
    private var gaveUpNotice = false

    private val tracker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val json = res.data?.getStringExtra(TrackerActivity.EXTRA_RESULT)
        if (res.resultCode == RESULT_OK && json != null) {
            addPending(json) // on the phone first, so nothing is lost whatever happens next
            startDelivering()
        }
    }

    /** What the Swish Quest page can call: window.SwishVisionNative.startTracking('ft') */
    inner class Bridge {
        @JavascriptInterface
        fun startTracking(shotType: String) {
            runOnUiThread {
                tracker.launch(
                    Intent(this@MainActivity, TrackerActivity::class.java)
                        .putExtra(TrackerActivity.EXTRA_SHOT_TYPE, shotType)
                )
            }
        }

        @JavascriptInterface
        fun version(): String = BuildConfigLite.versionName(this@MainActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridgeJs = assets.open("bridge.js").bufferedReader().use { it.readText() }

        // Same colour as Swish Quest's bottom bar, so the strip above the phone's gesture bar blends in.
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#111111")) }
        web = buildWebView()
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        offline = offlineView()
        root.addView(offline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Android 15 draws edge-to-edge: keep the page clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            // Consumed here: the page must not add the same space again (that doubled the bottom bar).
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Swish Quest is one page with screens: Back goes to the dashboard first, then exits.
                web.evaluateJavascript(
                    "(function(){var a=document.querySelector('.screen.active');" +
                        "if(a&&a.id!=='dashboard'&&typeof showNav==='function'&&document.getElementById('bottom-nav')){showNav('dashboard');return 'handled';}" +
                        "return 'exit';})()"
                ) { out -> if (out == null || !out.contains("handled")) finish() }
            }
        })

        // Always start from a fresh load: Swish Quest is a single page, and a restored page
        // can come back half-loaded after Android has cleared the app from memory.
        web.loadUrl(HOME)
    }

    override fun onResume() {
        super.onResume()
        if (pending().isNotEmpty()) startDelivering() // e.g. a session left over from last time
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun buildWebView(): WebView {
        val w = WebView(this)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Render Swish Quest at its designed size, like Chrome does. (By default the in-app browser
            // also applies the phone's font-size setting, which pushed the bottom tabs off screen.)
            textZoom = 100
            userAgentString = "$userAgentString SwishQuestApp"
        }
        w.addJavascriptInterface(Bridge(), "SwishVisionNative")
        w.webChromeClient = WebChromeClient() // enables the site's alert()/confirm() dialogs
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host in OUR_HOSTS) return false
                // Anything else (Google Sheets, Instagram, etc.) opens in the phone's browser.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (Uri.parse(url).host in OUR_HOSTS) {
                    view.evaluateJavascript(bridgeJs, null)
                    if (pending().isNotEmpty()) startDelivering()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) offline.visibility = View.VISIBLE
            }

            // Android may shut the page down to save memory (e.g. while the camera is running).
            // Rebuild it instead of letting the whole app crash; pending sessions are kept.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== web) return true
                val index = root.indexOfChild(view)
                root.removeView(view)
                view.destroy()
                web = buildWebView()
                root.addView(web, maxOf(0, index), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                web.loadUrl(HOME)
                return true
            }
        }
        return w
    }

    // ---------------- Reliable delivery of camera sessions ----------------

    private fun pending(): List<String> = runCatching {
        val a = JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PENDING, "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun setPending(list: List<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_PENDING, JSONArray(list).toString()).commit()
    }

    private fun addPending(json: String) = setPending(pending() + json)

    private fun removePending(json: String) = setPending(pending().filter { it != json })

    private fun startDelivering() {
        deliverUntil = SystemClock.uptimeMillis() + RETRY_WINDOW_MS
        gaveUpNotice = false
        if (!delivering) {
            delivering = true
            ui.post(deliverTick)
        }
    }

    private val deliverTick = object : Runnable {
        override fun run() {
            val json = pending().firstOrNull()
            if (json == null) {
                delivering = false
                return
            }
            web.evaluateJavascript(
                "(window.__svApply ? window.__svApply(${JSONObject.quote(json)}) : 'noscript')"
            ) { raw ->
                val out = raw ?: "null"
                when {
                    out.contains("saved") -> {
                        removePending(json)
                        toast("Camera session saved to Swish Quest ✓")
                        ui.post(this)
                    }
                    out.contains("duplicate") || out.contains("empty") -> {
                        removePending(json) // already in Swish Quest, or nothing to add
                        ui.post(this)
                    }
                    out.contains("filled") -> {
                        removePending(json) // numbers are on the Log Session screen for the player to save
                        toast("Check the numbers, then tap Save Session")
                        ui.post(this)
                    }
                    SystemClock.uptimeMillis() < deliverUntil -> {
                        // Swish Quest is still starting, signing in, or syncing. Try again shortly.
                        ui.postDelayed(this, RETRY_MS)
                    }
                    else -> {
                        delivering = false
                        if (!gaveUpNotice) {
                            gaveUpNotice = true
                            toast("Your camera session is kept on the phone. It will save as soon as Swish Quest is signed in and loaded.")
                        }
                    }
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun offlineView(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E1216"))
            visibility = View.GONE
            setPadding(48, 48, 48, 48)
        }
        box.addView(TextView(this).apply {
            text = "Swish Quest needs an internet connection."
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        })
        box.addView(Button(this).apply {
            text = "Try again"
            setOnClickListener {
                box.visibility = View.GONE
                web.reload()
            }
        })
        return box
    }
}

/** Version name without enabling BuildConfig generation. */
object BuildConfigLite {
    fun versionName(ctx: android.content.Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")
}
