package com.ashplay

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

// Website address. Local server: http://192.168.x.x/   Hosting: https://yourdomain.com/
private const val SITE_URL = "http://192.168.168.10/"

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0F0F12")
        window.navigationBarColor = Color.parseColor("#0F0F12")

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0F0F12")) }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0F0F12"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) {
                        v.loadData(
                            "<body style='background:#0f0f12;color:#fff;font-family:sans-serif;text-align:center;padding:60px 20px'>" +
                                "<h3>Cannot connect to server</h3><p>Check your Wi-Fi / internet and the server.</p>" +
                                "<button style='font-size:16px;padding:10px 24px' onclick='location.href=\"$SITE_URL\"'>Try again</button></body>",
                            "text/html; charset=utf-8", "UTF-8"
                        )
                    }
                }
            }

            // Makes the default video fullscreen button work
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                    if (customView != null) { cb.onCustomViewHidden(); return }
                    customView = view
                    customCallback = cb
                    web.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                }

                override fun onHideCustomView() { exitFullscreen() }
                override fun getDefaultVideoPoster() =
                    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            }
        }
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(SITE_URL)
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
