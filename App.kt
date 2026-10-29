package com.example.bndict

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/** Label we put on clips we copy ourselves, so the clipboard listener ignores them (no loops). */
const val CLIP_LABEL = "bndict"

class App : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDb::class.java, "bndict.db").build() }
    val repo by lazy { Repository(db.dao()) }
    val prefs by lazy { Prefs(this) }
    val speaker by lazy { Speaker(this) }
}

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class Prefs(private val ctx: Context) {
    companion object {
        val AUTO = booleanPreferencesKey("auto_popup")
        val WORDS = booleanPreferencesKey("show_words")
        val SENTENCES = booleanPreferencesKey("show_sentences")
    }

    fun flow(k: Preferences.Key<Boolean>): Flow<Boolean> = ctx.store.data.map { it[k] ?: true }
    suspend fun set(k: Preferences.Key<Boolean>, v: Boolean) { ctx.store.edit { it[k] = v } }
}

class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    @Volatile private var ready = false
    override fun onInit(status: Int) { ready = status == TextToSpeech.SUCCESS }

    /** Returns false if the engine or the language voice isn't available. */
    fun speak(text: String, bangla: Boolean): Boolean {
        if (!ready) return false
        val loc = if (bangla) Locale("bn", "BD") else Locale.US
        if (tts.isLanguageAvailable(loc) < TextToSpeech.LANG_AVAILABLE) return false
        tts.language = loc
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bndict")
        return true
    }
}
