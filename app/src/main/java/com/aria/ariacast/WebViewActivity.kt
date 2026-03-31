package com.aria.ariacast

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class WebViewActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val url = intent.getStringExtra("url") ?: "about:blank"
        val title = intent.getStringExtra("title") ?: "AriaCast Web"

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
            fitsSystemWindows = true
        }

        val toolbar = Toolbar(this).apply {
            setTitle(title)
            setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)

        val webView = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(url)
        }
        root.addView(webView)

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }
    }
}
