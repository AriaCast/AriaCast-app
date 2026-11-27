package com.aria.ariacast.crash

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aria.ariacast.R

/**
 * Shows a crash report and lets it be shared. Plain views and no theme tricks on purpose: this is
 * what has to work when everything else does not. It runs in the ":crash" process.
 */
class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT) ?: getString(R.string.crash_no_report)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply { text = getString(R.string.crash_title); textSize = 20f; setTypeface(typeface, Typeface.BOLD) }
        val body = TextView(this).apply { text = report; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        val share = Button(this).apply {
            text = getString(R.string.crash_share)
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, getString(R.string.crash_share)))
            }
        }
        val close = Button(this).apply { text = getString(R.string.crash_close); setOnClickListener { finishAndRemoveTask() } }
        val buttons = LinearLayout(this).apply { gravity = Gravity.END; addView(share); addView(close) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(title)
            addView(ScrollView(this@CrashReportActivity).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        })
    }

    companion object {
        const val EXTRA_REPORT = "com.aria.ariacast.CRASH_REPORT"
    }
}
