package com.example.bndict

import com.google.gson.Gson
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

fun isBengali(s: String) = s.any { it in '\u0980'..'\u09FF' }
fun clean(s: String) = s.trim { it.isWhitespace() || it in ".,;:!?\"'()[]{}“”‘’—-…" }
fun isWord(s: String) = clean(s).let { it.isNotEmpty() && it.none(Char::isWhitespace) }

fun userMessage(e: Throwable): String = when (e) {
    is MlKitException -> "Translation model unavailable. Connect to the internet once to download it (Settings → offline models)."
    is IOException -> "No internet connection."
    else -> e.message ?: "Something went wrong."
}

/**
 * One place for all lookups.
 *  - Translation: ML Kit on-device (no API key; models download once, then fully offline).
 *  - Definitions/phonetics/examples: Free Dictionary API, cached in Room after first use.
 *  Swap `translate()` to use another provider without touching the UI.
 */
class Repository(private val dao: DictDao) {
    private val gson = Gson()
    private val api: DictApi = Retrofit.Builder()
        .baseUrl("https://api.dictionaryapi.dev/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(DictApi::class.java)
    private val translators = ConcurrentHashMap<Boolean, Translator>()

    private fun translator(toBangla: Boolean): Translator = translators.getOrPut(toBangla) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(if (toBangla) TranslateLanguage.ENGLISH else TranslateLanguage.BENGALI)
                .setTargetLanguage(if (toBangla) TranslateLanguage.BENGALI else TranslateLanguage.ENGLISH)
                .build()
        )
    }

    suspend fun translate(text: String, toBangla: Boolean): String {
        val id = "t:${if (toBangla) "bn" else "en"}:$text"
        dao.cached(id)?.let { return it.value }
        val t = translator(toBangla)
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        val out = t.translate(text).await()
        dao.putCache(Cached(id, out))
        return out
    }

    suspend fun downloadModels() {
        for (toBn in listOf(true, false))
            translator(toBn).downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
    }

    suspend fun lookup(raw: String): LookupResult {
        val q = raw.trim()
        require(q.isNotEmpty())
        dao.putRecent(Recent(q.take(200)))
        if (isWord(q)) {
            val w = clean(q)
            return LookupResult.Word(w, word(w))
        }
        val toBn = !isBengali(q)
        return LookupResult.Phrase(q, translate(q, toBn), toBn)
    }

    private suspend fun word(w: String): Entry {
        val id = "w:" + w.lowercase()
        dao.cached(id)?.let { return gson.fromJson(it.value, Entry::class.java) }

        val english: String
        val bangla: String?
        if (isBengali(w)) { english = translate(w, false); bangla = w }
        else { english = w; bangla = runCatching { translate(w, true) }.getOrNull() }

        val enWord = clean(english).takeIf { isWord(it) }?.lowercase()
        val d = enWord?.let { runCatching { api.define(it).firstOrNull() }.getOrNull() }
        if (bangla == null && d == null)
            error("Offline, and the Bangla model isn't downloaded yet. Connect once and download it in Settings.")

        val meanings = d?.meanings.orEmpty().take(4).map { m ->
            Meaning(
                m.partOfSpeech.orEmpty(),
                m.definitions.orEmpty().take(3).map { Def(it.definition.orEmpty(), it.example, null) },
                m.synonyms.orEmpty().take(6),
                m.antonyms.orEmpty().take(6)
            )
        }
        var budget = 3 // translate at most 3 example sentences per word
        val withBn = meanings.map { m ->
            m.copy(defs = m.defs.map { df ->
                val ex = df.example
                if (ex != null && budget > 0 && bangla != null) {
                    budget--
                    df.copy(exampleBn = runCatching { translate(ex, true) }.getOrNull())
                } else df
            })
        }
        val entry = Entry(english, bangla ?: "—", d?.phonetic ?: d?.phonetics?.firstNotNullOfOrNull { it.text }, withBn)
        if (d != null && bangla != null) dao.putCache(Cached(id, gson.toJson(entry)))
        return entry
    }

    fun recents() = dao.recents()
    fun saved() = dao.saved()
    suspend fun suggest(prefix: String) = dao.suggest(prefix)
    suspend fun isSaved(t: String) = dao.isSaved(t) > 0
    suspend fun unsave(t: String) = dao.unsave(t)
    suspend fun toggleSave(r: LookupResult): Boolean =
        if (dao.isSaved(r.query) > 0) { dao.unsave(r.query); false }
        else { dao.save(Saved(r.query, r.translation, r is LookupResult.Phrase)); true }
}
