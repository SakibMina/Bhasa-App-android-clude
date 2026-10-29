package com.example.bndict

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Draws a draggable bubble + the dictionary popup over other apps.
 * Lookup sources (all user-initiated):
 *  1) tap the bubble  -> ClipActivity reads the clipboard (allowed because it has focus) -> popup
 *  2) clipboard listener (auto-popup): reliable on Android 9 and below; Android 10+ blocks background clipboard reads
 */
class FloatingService : Service() {
    companion object {
        @Volatile var running = false
        const val ACTION_SHOW = "com.example.bndict.SHOW"
        const val ACTION_STOP = "com.example.bndict.STOP"
        const val EXTRA_TEXT = "text"
    }

    private lateinit var wm: WindowManager
    private lateinit var cm: ClipboardManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bubble: View? = null
    private var popup: PopupCard? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClip() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        addBubble()
        cm.addPrimaryClipChangedListener(clipListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_SHOW -> intent.getStringExtra(EXTRA_TEXT)?.let { showPopup(it) }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) {
            cm.removePrimaryClipChangedListener(clipListener)
            hidePopup()
            bubble?.let { runCatching { wm.removeView(it) } }
        }
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("float", "Floating dictionary", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FloatingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "float")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Floating dictionary is on")
            .setContentText("Copy a word, then tap the bubble")
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    // ---- clipboard auto-popup
    private fun onClip() {
        scope.launch {
            val prefs = (application as App).prefs
            if (!prefs.flow(Prefs.AUTO).first()) return@launch
            val clip = runCatching { cm.primaryClip }.getOrNull() ?: return@launch
            if (clip.itemCount == 0) return@launch
            val d = clip.description
            if (d.label?.toString() == CLIP_LABEL) return@launch                                   // our own copy
            if (d.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return@launch // passwords etc.
            val text = clip.getItemAt(0).coerceToText(this@FloatingService).toString().trim()
            if (text.isEmpty() || text.length > 3000) return@launch
            if (!prefs.flow(if (isWord(text)) Prefs.WORDS else Prefs.SENTENCES).first()) return@launch
            showPopup(text)
        }
    }

    // ---- overlay windows
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // never steal focus/keyboard from the reading app
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addBubble() {
        val v = TextView(this).apply {
            text = "অ"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE60B57D0.toInt()) }
        }
        val p = lp(dp(52), dp(52)).apply { x = 0; y = dp(200) }
        v.setOnTouchListener(Drag(p, v) {
            startActivity(Intent(this, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        wm.addView(v, p)
        bubble = v
    }

    private fun showPopup(text: String) {
        if (popup == null) {
            val card = PopupCard(this) { hidePopup() }
            val sw = resources.displayMetrics.widthPixels
            val w = (sw * 0.92f).toInt()
            val p = lp(w, WindowManager.LayoutParams.WRAP_CONTENT).apply { x = (sw - w) / 2; y = dp(120) }
            card.dragHandle.setOnTouchListener(Drag(p, card.view) {})
            wm.addView(card.view, p)
            popup = card
        }
        popup?.show(text)
    }

    private fun hidePopup() {
        popup?.let { runCatching { wm.removeView(it.view) }; it.dispose() }
        popup = null
    }

    private inner class Drag(
        val p: WindowManager.LayoutParams, val v: View, val onTap: () -> Unit
    ) : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        override fun onTouch(view: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) { p.x = ox + dx; p.y = oy + dy; wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            return true
        }
    }
}
