package com.example.bndict

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Root(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) { i?.getStringExtra("q")?.takeIf { it.isNotBlank() }?.let { vm.search(it) } }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as App).repo

    var input by mutableStateOf(""); private set
    var result by mutableStateOf<LookupResult?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var saved by mutableStateOf(false); private set
    var suggestions by mutableStateOf<List<String>>(emptyList()); private set

    val recents = repo.recents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val savedItems = repo.saved().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onInput(v: String) {
        input = v
        val p = v.trim()
        viewModelScope.launch { suggestions = if (p.length >= 2 && !p.contains(' ')) repo.suggest(p.lowercase()) else emptyList() }
    }

    fun clear() { input = ""; result = null; error = null; suggestions = emptyList() }

    fun search(q: String = input) {
        val t = q.trim()
        if (t.isEmpty()) return
        input = t; suggestions = emptyList(); loading = true; error = null
        viewModelScope.launch {
            try {
                val r = repo.lookup(t)
                result = r; saved = repo.isSaved(r.query)
            } catch (e: Exception) { result = null; error = userMessage(e) } finally { loading = false }
        }
    }

    fun toggleSave() { val r = result ?: return; viewModelScope.launch { saved = repo.toggleSave(r) } }
    fun unsave(t: String) { viewModelScope.launch { repo.unsave(t) } }
}

@Composable
fun Root(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Translate" to Icons.Default.Search, "Saved" to Icons.Default.Favorite, "Settings" to Icons.Default.SettingsIcon)
                .forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> SearchTab(vm)
                1 -> SavedTab(vm) { tab = 0; vm.search(it) }
                else -> SettingsTab()
            }
        }
    }
}

@Composable
fun SearchTab(vm: MainViewModel) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = vm.input, onValueChange = vm::onInput, modifier = Modifier.fillMaxWidth(),
                label = { Text("English or বাংলা — word, sentence or paragraph") }, maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() })
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.search() }) { Text("Translate") }
                OutlinedButton(onClick = { vm.clear() }) { Text("Clear") }
            }
        }
        if (vm.suggestions.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(vm.suggestions) { s -> TextButton(onClick = { vm.search(s) }) { Text(s) } }
            }
        }
        if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        vm.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        vm.result?.let { r -> item { ResultCard(vm, r) } }
        if (vm.result == null) {
            item { Text("Recent", style = MaterialTheme.typography.titleSmall) }
            items(recents, key = { it.text }) { r ->
                Text(r.text, Modifier.fillMaxWidth().clickable { vm.search(r.text) }.padding(vertical = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ResultCard(vm: MainViewModel, r: LookupResult) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    fun say(bn: Boolean) {
        if (!app.speaker.speak(if (bn) r.bangla else r.english, bn))
            Toast.makeText(ctx, "Voice not available for this language", Toast.LENGTH_SHORT).show()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r is LookupResult.Word) {
                Text(r.query, style = MaterialTheme.typography.headlineMedium)
                r.entry.phonetic?.let { Text(it) }
            } else Text(r.query)
            Text(r.translation, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            if (r is LookupResult.Word) Text(r.entry.asText())
            Row {
                TextButton(onClick = { say(false) }) { Text("🔊 EN") }
                TextButton(onClick = { say(true) }) { Text("🔊 BN") }
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, r.translation))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                TextButton(onClick = { vm.toggleSave() }) { Text(if (vm.saved) "★ Saved" else "☆ Save") }
            }
        }
    }
}

@Composable
fun SavedTab(vm: MainViewModel, open: (String) -> Unit) {
    val items by vm.savedItems.collectAsStateWithLifecycle()
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing saved yet") }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(items, key = { it.text }) { s ->
                Row(Modifier.fillMaxWidth().clickable { open(s.text) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(s.translation, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { vm.unsave(s.text) }) { Text("Remove") }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun PrefSwitch(label: String, key: Preferences.Key<Boolean>) {
    val prefs = (LocalContext.current.applicationContext as App).prefs
    val on by remember(key) { prefs.flow(key) }.collectAsStateWithLifecycle(true)
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { v -> scope.launch { prefs.set(key, v) } })
    }
}

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var running by remember { mutableStateOf(FloatingService.running) }
    var why by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { canDraw = Settings.canDrawOverlays(ctx); running = FloatingService.running }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun start() {
        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        ContextCompat.startForegroundService(ctx, Intent(ctx, FloatingService::class.java))
        running = true
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Floating dictionary", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show floating bubble", Modifier.weight(1f))
            Switch(checked = running, onCheckedChange = { on ->
                if (on) { if (canDraw) start() else why = true }
                else { ctx.stopService(Intent(ctx, FloatingService::class.java)); running = false }
            })
        }
        Text(if (canDraw) "Overlay permission: granted" else "Overlay permission: not granted", style = MaterialTheme.typography.bodySmall)
        PrefSwitch("Auto-popup when text is copied (Android 10+ may block this in the background — use the bubble)", Prefs.AUTO)
        PrefSwitch("Show word meanings", Prefs.WORDS)
        PrefSwitch("Show sentence translations", Prefs.SENTENCES)
        Text("Tip: you can also select text in any app and choose “Bangla Dictionary” from the selection menu — no overlay permission needed.", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Offline", style = MaterialTheme.typography.titleMedium)
        Text("Previously looked-up words and translations work offline. Download the on-device language models once to translate new text offline.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            msg = "Downloading…"
            scope.launch {
                msg = try { (ctx.applicationContext as App).repo.downloadModels(); "Offline models ready." } catch (e: Exception) { userMessage(e) }
            }
        }) { Text("Download offline models (Wi-Fi)") }
        if (msg.isNotEmpty()) Text(msg)

        HorizontalDivider()
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Text("Words and sentences are translated on your device. Only a single English word is sent to dictionaryapi.dev to fetch its definitions. The app reads the clipboard only when you tap the bubble (or when auto-popup is on) and never reads other apps' screens.", style = MaterialTheme.typography.bodySmall)
    }

    if (why) AlertDialog(
        onDismissRequest = { why = false },
        title = { Text("Overlay permission needed") },
        text = { Text("Android requires “Display over other apps” to draw the dictionary bubble and popup on top of your reading app. It is used only for that. The app does not read other apps' content — only text you copy or select.") },
        confirmButton = {
            TextButton(onClick = {
                why = false
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = { why = false }) { Text("Cancel") } }
    )
}
