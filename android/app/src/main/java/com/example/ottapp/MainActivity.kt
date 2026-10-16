package com.example.ottapp

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

val Navy = Color(0xFF000A1E)
val Blue = Color(0xFF1E90FF)
const val FREE_SECONDS = 30 * 60L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Navy, surface = Navy)) { App() }
        }
    }
}

/** Device id, free-watch time aur subscription ki local state. */
class AppState(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ott", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("device", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    var watched by mutableStateOf(prefs.getLong("watched", 0L))
        private set
    var subUntil by mutableStateOf(prefs.getLong("subUntil", 0L))
        private set
    val subscribed: Boolean get() = subUntil > System.currentTimeMillis()
    val freeLeft: Long get() = (FREE_SECONDS - watched).coerceAtLeast(0L)
    fun addWatched(sec: Long) {
        watched += sec
        if (watched % 10L == 0L) saveWatched()
    }
    fun saveWatched() { prefs.edit().putLong("watched", watched).apply() }
    fun setSub(until: Long) { subUntil = until; prefs.edit().putLong("subUntil", until).apply() }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val st = remember { AppState(ctx) }
    var selected by remember { mutableStateOf<Movie?>(null) }
    LaunchedEffect(Unit) {
        try { st.setSub(Api.subscription(st.deviceId)) } catch (e: Exception) { }
    }
    val movie = selected
    if (movie == null) HomeScreen(st) { selected = it }
    else { BackHandler { selected = null }; PlayerScreen(movie, st) { selected = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(st: AppState, onOpen: (Movie) -> Unit) {
    var movies by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPlayGuide by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(pull: Boolean) {
        scope.launch {
            if (pull) refreshing = true else loading = true
            error = null
            try { movies = Api.movies() } catch (e: Exception) { error = "Load nahi hua: ${e.message}" }
            refreshing = false; loading = false
        }
    }
    LaunchedEffect(Unit) { load(false) }

    Column(Modifier.fillMaxSize().background(Navy)) {
        Box(Modifier.fillMaxWidth()) {
            Image(
                painterResource(R.drawable.logo), "SmartRin2",
                Modifier.fillMaxWidth().height(160.dp).padding(top = 4.dp),
                contentScale = ContentScale.Fit
            )
            Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { load(true) }) { Text("Refresh", color = Color.White) }
                TextButton(onClick = { showPlayGuide = true }) { Text("Play Store Guide", color = Color.White) }
            }
        }
        val status = if (st.subscribed)
            "✅ Subscription active: " + DateFormat.getDateInstance().format(Date(st.subUntil))
        else "Free dekhne ka time: ${st.freeLeft / 60} min baaki"
        Text(status, Modifier.fillMaxWidth().padding(bottom = 4.dp), fontSize = 12.sp,
            textAlign = TextAlign.Center, color = Color(0xFF8FB8FF))

        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { load(true) }, modifier = Modifier.fillMaxSize()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val err = error
                val groups = movies.groupBy { it.category }.toList()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (err != null) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(err); Spacer(Modifier.height(8.dp))
                                Button(onClick = { load(true) }) { Text("Retry") }
                            }
                        }
                    } else if (movies.isEmpty()) {
                        item {
                            Text("Abhi koi movie nahi hai. Neeche kheench ke refresh karo.",
                                Modifier.fillMaxWidth().padding(24.dp))
                        }
                    }
                    items(groups) { group ->
                        Column {
                            Text(group.first, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                                fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(group.second) { m -> MovieCard(m, onOpen) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showPlayGuide) {
        AlertDialog(
            onDismissRequest = { showPlayGuide = false },
            title = { Text("Google Play Store Guide", color = Color.White) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("1. App ka package name unique rakhein aur app icon taiyar karein.", color = Color(0xFFE3EAF5))
                    Text("2. Release ke liye Android App Bundle (.aab) build karein.", color = Color(0xFFE3EAF5))
                    Text("3. Release signing key banayein aur uska secure backup rakhein. Keystore/password kisi ke saath share na karein.", color = Color(0xFFE3EAF5))
                    Text("4. Privacy Policy URL aur Play Console ka Data safety form sahi tarah bharein.", color = Color(0xFFE3EAF5))
                    Text("5. App ke asli screenshots, app description, category aur contact details taiyar karein.", color = Color(0xFFE3EAF5))
                    Text("6. Content rating questionnaire aur app access instructions complete karein.", color = Color(0xFFE3EAF5))
                    Text("7. Play Console mein internal/closed testing karein aur console ke current testing rules follow karein.", color = Color(0xFFE3EAF5))
                    Text("8. Production release submit karein; policy ya testing issue aaye to pehle use fix karein.", color = Color(0xFFE3EAF5))
                    Text("Important: Movies/videos ke distribution rights hone chahiye. Sirf tutorial se approval guaranteed nahi hota.", color = Color(0xFFFFD180), fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlayGuide = false }) { Text("Close") }
            },
            containerColor = Color(0xFF17243B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFE3EAF5)
        )
    }
}

@Composable
fun MovieCard(m: Movie, onOpen: (Movie) -> Unit) {
    Column(Modifier.width(125.dp).clickable { onOpen(m) }) {
        AsyncImage(
            model = m.thumbUrl.ifEmpty { null }, contentDescription = m.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF14213D)),
            contentScale = ContentScale.Crop
        )
        Text(
            m.title.ifBlank { "Untitled movie" },
            Modifier.fillMaxWidth().padding(top = 6.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFFFFFFFF)
        )
        if (m.languages.isNotBlank())
            Text(m.languages, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp, color = Color(0xFF9CC5FF))
    }
}

// ---------------- helpers ----------------

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun setFullscreen(activity: Activity?, on: Boolean) {
    activity ?: return
    val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (on) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        c.show(WindowInsetsCompat.Type.systemBars())
    }
}

fun initialBrightness(a: Activity?): Float {
    val cur = a?.window?.attributes?.screenBrightness ?: -1f
    if (cur >= 0f) return cur
    return try {
        Settings.System.getInt(a!!.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Exception) { 0.5f }
}

fun setBrightness(a: Activity?, v: Float) {
    val w = a?.window ?: return
    val lp = w.attributes
    lp.screenBrightness = v
    w.attributes = lp
}

fun resetBrightness(a: Activity?) = setBrightness(a, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

fun downloadMovie(ctx: Context, m: Movie) {
    val ext = m.videoUrl.substringAfterLast('.', "mp4").take(5)
    val base = m.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "movie" }
    val name = "$base.$ext"
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    fun req() = DownloadManager.Request(Uri.parse(m.videoUrl))
        .setTitle(m.title).setDescription("Downloading")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    try {
        dm.enqueue(req().setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name))
    } catch (e: Exception) {
        dm.enqueue(req().setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name))
    }
    Toast.makeText(ctx, "Download shuru ho gaya (notification dekho)", Toast.LENGTH_LONG).show()
}

data class AudioOpt(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

fun audioOptions(t: Tracks): List<AudioOpt> {
    val out = ArrayList<AudioOpt>()
    t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
        for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val lang = f.language
            val lbl = f.label
            val label = when {
                !lang.isNullOrBlank() && lang != "und" ->
                    Locale(lang).displayLanguage.replaceFirstChar { it.uppercase() }
                !lbl.isNullOrBlank() -> lbl
                else -> "Audio ${out.size + 1}"
            }
            out.add(AudioOpt(g, i, label, g.isTrackSelected(i)))
        }
    }
    return out
}

/** Right side upar/neeche swipe = brightness, left side = volume. */
class SwipeControls(
    private val slop: Float,
    private val onStart: () -> Unit,
    private val onBrightness: (Float) -> Unit,
    private val onVolume: (Float) -> Unit
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var right = true

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y
                dragging = false
                right = e.x > v.width / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                    dragging = true; lastY = e.y; onStart()
                }
                if (dragging) {
                    val delta = (lastY - e.y) / v.height.coerceAtLeast(1) * 1.2f
                    lastY = e.y
                    if (right) onBrightness(delta) else onVolume(delta)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging = false; return true }
            }
        }
        return false
    }
}

@Composable
fun OverlayBtn(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 16.sp,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

// ---------------- player ----------------

@Composable
fun PlayerScreen(movie: Movie, st: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val slop = remember { ViewConfiguration.get(ctx).scaledTouchSlop.toFloat() }
    val scope = rememberCoroutineScope()

    var fullscreen by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(!st.subscribed && st.watched >= FREE_SECONDS) }
    var hud by remember { mutableStateOf("") }
    var bright by remember { mutableStateOf(initialBrightness(activity)) }
    var vol by remember { mutableStateOf(0f) }

    val exo = remember(movie.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(movie.videoUrl)); prepare(); playWhenReady = !showPaywall
        }
    }
    var tracks by remember(exo) { mutableStateOf(exo.currentTracks) }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onTracksChanged(tracks2: Tracks) { tracks = tracks2 }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    DisposableEffect(fullscreen) { setFullscreen(activity, fullscreen); onDispose { } }
    DisposableEffect(Unit) {
        onDispose { st.saveWatched(); setFullscreen(activity, false); resetBrightness(activity) }
    }

    // 30 minute free-watch counter
    LaunchedEffect(exo) {
        while (true) {
            delay(1000)
            if (exo.isPlaying && !st.subscribed) {
                st.addWatched(1)
                if (st.watched >= FREE_SECONDS) { exo.pause(); showPaywall = true }
            }
        }
    }
    LaunchedEffect(hud) { if (hud.isNotEmpty()) { delay(900); hud = "" } }
    LaunchedEffect(showUnlock) { if (showUnlock) { delay(3000); showUnlock = false } }

    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = locked) { showUnlock = true }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            if (fullscreen) Modifier.fillMaxWidth().weight(1f)
            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        ) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = exo
                        keepScreenOn = true
                        setOnTouchListener(SwipeControls(
                            slop,
                            onStart = {
                                vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            },
                            onBrightness = { d ->
                                bright = (bright + d).coerceIn(0.02f, 1f)
                                setBrightness(activity, bright)
                                hud = "☀ ${(bright * 100).roundToInt()}%"
                            },
                            onVolume = { d ->
                                vol = (vol + d).coerceIn(0f, 1f)
                                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * max).roundToInt(), 0)
                                hud = "🔊 ${(vol * 100).roundToInt()}%"
                            }
                        ))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (hud.isNotEmpty()) {
                Text(
                    hud, color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (!locked) {
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlayBtn("🌐") { showLang = true }
                    OverlayBtn("🔒") { locked = true; showUnlock = false }
                    if (fullscreen) OverlayBtn("✕") { fullscreen = false }
                }
            } else {
                // Lock mode: saari touches block, sirf unlock button chalega
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { showUnlock = true } })
                if (showUnlock) {
                    OverlayBtn("🔓 Unlock", Modifier.align(Alignment.Center)) { locked = false; showUnlock = false }
                }
            }
        }
        if (!fullscreen) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .background(Color(0xFF080F20)).padding(16.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF17243B)).padding(16.dp)
                ) {
                    Text(
                        movie.title.ifBlank { "Untitled movie" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White, fontWeight = FontWeight.Bold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        movie.category + if (movie.languages.isNotBlank()) "  •  " + movie.languages else "",
                        fontSize = 13.sp, color = Color(0xFF9CC5FF),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (movie.description.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(movie.description, color = Color(0xFFE3EAF5), fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { fullscreen = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) { Text("Full screen", color = Color.White, maxLines = 1) }
                    Button(
                        onClick = { downloadMovie(ctx, movie) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) { Text("Download", color = Color.White, maxLines = 1) }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF9CC5FF))
                ) { Text("← Back") }
            }
        }
    }

    if (showLang) {
        val options = audioOptions(tracks)
        AlertDialog(
            onDismissRequest = { showLang = false },
            title = { Text("Language / Audio") },
            text = {
                if (options.size <= 1) {
                    Text("Is video me sirf ek hi audio language hai.")
                } else {
                    Column {
                        options.forEach { o ->
                            TextButton(onClick = {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                                    .build()
                                showLang = false
                            }) { Text((if (o.selected) "✓ " else "") + o.label) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLang = false }) { Text("Close") } }
        )
    }

    if (showPaywall) {
        var code by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Free 30 minute poore ho gaye") },
            text = {
                Column {
                    Text("Aage dekhne ke liye subscription chahiye.")
                    Text(Api.CONTACT_TEXT, fontSize = 12.sp, color = Color(0xFF8FB8FF),
                        modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(value = code, onValueChange = { code = it },
                        label = { Text("Subscription code") }, singleLine = true)
                    if (msg.isNotEmpty()) Text(msg, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(enabled = !busy && code.isNotBlank(), onClick = {
                    busy = true; msg = ""
                    scope.launch {
                        try {
                            st.setSub(Api.redeem(code, st.deviceId))
                            showPaywall = false
                            exo.play()
                        } catch (e: Exception) { msg = e.message ?: "Error" }
                        busy = false
                    }
                }) { Text("Activate") }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("Back") } }
        )
    }
}
