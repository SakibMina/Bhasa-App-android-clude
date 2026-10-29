package com.example.bndict

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast

/** Invisible. Takes focus for a moment so Android lets us read the clipboard, hands text to the service, closes. */
class ClipActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Copy a word or sentence first", Toast.LENGTH_SHORT).show()
        } else {
            startService(Intent(this, FloatingService::class.java)
                .setAction(FloatingService.ACTION_SHOW).putExtra(FloatingService.EXTRA_TEXT, text))
        }
        finish()
        overridePendingTransition(0, 0)
    }
}

/** Select text in any app -> menu -> "Bangla Dictionary". Needs no overlay permission and no clipboard. */
class ProcessTextActivity : Activity() {
    private var card: PopupCard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (text.isBlank()) { finish(); return }
        val c = PopupCard(this) { finish() }
        card = c
        val m = (16 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply { setOnClickListener { finish() } } // tap outside to dismiss
        frame.addView(
            c.view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { setMargins(m, m * 5, m, 0) }
        )
        setContentView(frame)
        c.show(text)
    }

    override fun onDestroy() { card?.dispose(); super.onDestroy() }
}
