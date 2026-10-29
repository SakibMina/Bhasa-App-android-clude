package com.example.bndict

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import retrofit2.http.GET
import retrofit2.http.Path

// ---------- domain model (also what we cache as JSON) ----------
data class Def(val text: String, val example: String?, val exampleBn: String?)
data class Meaning(val pos: String, val defs: List<Def>, val synonyms: List<String>, val antonyms: List<String>)
data class Entry(val english: String, val bangla: String, val phonetic: String?, val meanings: List<Meaning>)

sealed interface LookupResult {
    val query: String
    val english: String
    val bangla: String
    /** The "other side" of what the user typed/copied. */
    val translation: String

    data class Word(override val query: String, val entry: Entry) : LookupResult {
        override val english: String get() = entry.english
        override val bangla: String get() = entry.bangla
        override val translation: String get() = if (isBengali(query)) entry.english else entry.bangla
    }

    data class Phrase(override val query: String, val translated: String, val toBangla: Boolean) : LookupResult {
        override val english: String get() = if (toBangla) query else translated
        override val bangla: String get() = if (toBangla) translated else query
        override val translation: String get() = translated
    }
}

fun Entry.asText(): String = meanings.joinToString("\n\n") { m ->
    buildString {
        append(m.pos.uppercase())
        m.defs.forEachIndexed { i, d ->
            append("\n${i + 1}. ${d.text}")
            d.example?.let { append("\n   “$it”") }
            d.exampleBn?.let { append("\n   $it") }
        }
        if (m.synonyms.isNotEmpty()) append("\nSynonyms: ${m.synonyms.joinToString()}")
        if (m.antonyms.isNotEmpty()) append("\nAntonyms: ${m.antonyms.joinToString()}")
    }
}.ifEmpty { "No detailed definitions found." }

// ---------- Room ----------
@Entity data class Cached(@PrimaryKey val id: String, val value: String)
@Entity data class Recent(@PrimaryKey val text: String, val ts: Long = System.currentTimeMillis())
@Entity data class Saved(
    @PrimaryKey val text: String,
    val translation: String,
    val isPhrase: Boolean,
    val ts: Long = System.currentTimeMillis()
)

@Dao
interface DictDao {
    @Query("SELECT * FROM Cached WHERE id = :id") suspend fun cached(id: String): Cached?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCache(c: Cached)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRecent(r: Recent)
    @Query("SELECT * FROM Recent ORDER BY ts DESC LIMIT 30") fun recents(): Flow<List<Recent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(s: Saved)
    @Query("DELETE FROM Saved WHERE text = :t") suspend fun unsave(t: String)
    @Query("SELECT * FROM Saved ORDER BY ts DESC") fun saved(): Flow<List<Saved>>
    @Query("SELECT COUNT(*) FROM Saved WHERE text = :t") suspend fun isSaved(t: String): Int

    /** Prefix suggestions from words already looked up (works offline). */
    @Query("SELECT substr(id, 3) FROM Cached WHERE id LIKE 'w:' || :p || '%' LIMIT 8")
    suspend fun suggest(p: String): List<String>
}

@Database(entities = [Cached::class, Recent::class, Saved::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() { abstract fun dao(): DictDao }

// ---------- Free Dictionary API (api.dictionaryapi.dev) ----------
interface DictApi {
    @GET("api/v2/entries/en/{word}") suspend fun define(@Path("word") word: String): List<ApiEntry>
}
data class ApiEntry(val phonetic: String?, val phonetics: List<ApiPhonetic>?, val meanings: List<ApiMeaning>?)
data class ApiPhonetic(val text: String?)
data class ApiMeaning(val partOfSpeech: String?, val definitions: List<ApiDef>?, val synonyms: List<String>?, val antonyms: List<String>?)
data class ApiDef(val definition: String?, val example: String?)
