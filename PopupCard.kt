package com.example.bndict

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MaxHeightScroll(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) =
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST))
}

/**
 * The popup UI, built from plain Views (light on low-end phones, no Compose lifecycle needed in a Service).
 * Hosted either in a WindowManager overlay (FloatingService) or in a translucent Activity (ProcessTextActivity).
 */
class PopupCard(private val ctx: Context, private val close: () -> Unit) {
    private val app = ctx.applicationContext as App
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var result: LookupResult? = null

    private fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
    private fun tv(size: Float, bold: Boolean = false) = TextView(ctx).apply {
        textSize = size; setTextColor(0xFF1B1B1F.toInt()); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun action(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; setTextColor(0xFF0B57D0.toInt())
        setPadding(dp(6), dp(8), dp(6), dp(8)); setOnClickListener { onClick() }
    }

    private val title = tv(17f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val expandBtn = action("▼") { toggleExpand() }
    /** Touch this part to move the popup. */
    val dragHandle = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(expandBtn)
        addView(action("✕") { close() })
    }
    private val phonetic = tv(13f)
    private val status = tv(13f)
    private val main = tv(20f, true).apply { setTextColor(0xFF0B57D0.toInt()) }
    private val details = tv(14f).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
    private val saveBtn = action("☆ Save") { toggleSave() }

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setPadding(dp(14), dp(8), dp(14), dp(6))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x33000000) }
        elevation = dp(8).toFloat()
        addView(dragHandle); addView(phonetic); addView(status)
        addView(MaxHeightScroll(ctx, dp(260)).apply {
            addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(main); addView(details) })
        })
        addView(LinearLayout(ctx).apply {
            addView(action("🔊 EN") { speak(false) })
            addView(action("🔊 BN") { speak(true) })
            addView(action("Copy") { copy() })
            addView(saveBtn)
            addView(action("Open ›") { open() })
        })
    }

    fun show(text: String) {
        val q = text.trim()
        title.text = q.take(80); phonetic.text = ""; main.text = ""; details.text = ""
        status.text = "…"; status.visibility = View.VISIBLE
        job?.cancel()
        job = scope.launch {
            try {
                val r = app.repo.lookup(q)
                result = r
                status.visibility = View.GONE
                title.text = r.query.take(80)
                main.text = r.translation
                when (r) {
                    is LookupResult.Word -> { phonetic.text = r.entry.phonetic.orEmpty(); details.text = r.entry.asText() }
                    is LookupResult.Phrase -> details.text = "Original:\n${r.query}"
                }
                refreshSave()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status.text = userMessage(e) }
        }
    }

    private fun toggleExpand() {
        val open = details.visibility != View.VISIBLE
        details.visibility = if (open) View.VISIBLE else View.GONE
        expandBtn.text = if (open) "▲" else "▼"
    }

    private fun speak(bangla: Boolean) {
        val r = result ?: return
        if (!app.speaker.speak(if (bangla) r.bangla else r.english, bangla))
            Toast.makeText(ctx, "Voice not available for this language", Toast.LENGTH_SHORT).show()
    }

    private fun copy() {
        val r = result ?: return
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, r.translation))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun open() {
        val q = result?.query ?: return
        ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("q", q)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        close()
    }

    private fun refreshSave() {
        val r = result ?: return
        scope.launch { saveBtn.text = if (app.repo.isSaved(r.query)) "★ Saved" else "☆ Save" }
    }

    private fun toggleSave() {
        val r = result ?: return
        scope.launch { saveBtn.text = if (app.repo.toggleSave(r)) "★ Saved" else "☆ Save" }
    }

    fun dispose() { scope.cancel() }
}
