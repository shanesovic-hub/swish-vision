package com.houseofswish.swishvision

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import org.json.JSONObject

/**
 * Swish Quest, as an app. Shows the live Swish Quest site (same accounts, players and data)
 * and adds Swish Vision: a "Track with camera" button that opens the camera tracker and
 * saves the result through Swish Quest's own Log Session save.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME = "https://swish-quest.web.app/"
        private val OUR_HOSTS = setOf("swish-quest.web.app", "swish-quest.firebaseapp.com")
    }

    private lateinit var web: WebView
    private lateinit var offline: View
    private var bridgeJs = ""

    private val tracker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val json = res.data?.getStringExtra(TrackerActivity.EXTRA_RESULT)
        if (res.resultCode == RESULT_OK && json != null) {
            web.evaluateJavascript("window.__svApply && window.__svApply(${JSONObject.quote(json)})") { out ->
                val msg = when {
                    out.contains("saved") -> "Camera session saved to Swish Quest"
                    out.contains("filled") -> "Check the numbers, then tap Save Session"
                    out.contains("empty") -> "No shots to save"
                    else -> "Couldn't save automatically. The session file is kept on the phone."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
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

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridgeJs = assets.open("bridge.js").bufferedReader().use { it.readText() }

        // Same colour as Swish Quest's bottom bar, so the strip above the phone's gesture bar blends in.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#111111")) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        offline = offlineView()
        root.addView(offline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Android 15 draws edge-to-edge: keep the page clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Render Swish Quest at its designed size, like Chrome does. (By default the in-app browser
            // also applies the phone's font-size setting, which pushed the bottom tabs off screen.)
            textZoom = 100
            userAgentString = "$userAgentString SwishQuestApp"
        }
        web.addJavascriptInterface(Bridge(), "SwishVisionNative")
        web.webChromeClient = WebChromeClient() // enables the site's alert()/confirm() dialogs
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host in OUR_HOSTS) return false
                // Anything else (Google Sheets, Instagram, etc.) opens in the phone's browser.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (Uri.parse(url).host in OUR_HOSTS) view.evaluateJavascript(bridgeJs, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) offline.visibility = View.VISIBLE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Swish Quest is one page with screens: Back goes to the dashboard first, then exits.
                web.evaluateJavascript(
                    "(function(){var a=document.querySelector('.screen.active');" +
                        "if(a&&a.id!=='dashboard'&&typeof showNav==='function'&&document.getElementById('bottom-nav')){showNav('dashboard');return 'handled';}" +
                        "return 'exit';})()"
                ) { out -> if (!out.contains("handled")) finish() }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(HOME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

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
