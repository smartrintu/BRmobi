package com.example.ottapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE50914))) { App() }
        }
    }
}

@Composable
fun App() {
    var selected by remember { mutableStateOf<Movie?>(null) }
    val movie = selected
    if (movie == null) HomeScreen { selected = it }
    else { BackHandler { selected = null }; PlayerScreen(movie) { selected = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Movie) -> Unit) {
    var movies by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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

    Scaffold(topBar = {
        TopAppBar(title = { Text("OTT App") },
            actions = { TextButton(onClick = { load(true) }) { Text("Refresh") } })
    }) { pad ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { load(true) },
            modifier = Modifier.padding(pad).fillMaxSize()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val err = error
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (err != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(err); Spacer(Modifier.height(8.dp))
                                Button(onClick = { load(true) }) { Text("Retry") }
                            }
                        }
                    } else if (movies.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Abhi koi movie nahi hai. Neeche kheench ke refresh karo.",
                                Modifier.fillMaxWidth().padding(24.dp))
                        }
                    }
                    items(movies) { m ->
                        Column(Modifier.clickable { onOpen(m) }) {
                            AsyncImage(m.thumbUrl.ifEmpty { null }, m.title,
                                Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                    .clip(MaterialTheme.shapes.medium).background(Color.DarkGray),
                                contentScale = ContentScale.Crop)
                            Text(m.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

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

@Composable
fun PlayerScreen(movie: Movie, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    var fullscreen by remember { mutableStateOf(false) }
    val player = remember(movie.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(movie.videoUrl)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(fullscreen) { setFullscreen(activity, fullscreen); onDispose { } }
    DisposableEffect(Unit) { onDispose { setFullscreen(activity, false) } }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = if (fullscreen) Modifier.fillMaxWidth().weight(1f)
                           else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            if (!fullscreen) {
                Column(Modifier.padding(16.dp)) {
                    Text(movie.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(movie.description)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { fullscreen = true }) { Text("Full screen") }
                        Button(onClick = { downloadMovie(ctx, movie) }) { Text("Download") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBack) { Text("Back") }
                }
            }
        }
        if (fullscreen) {
            TextButton(onClick = { fullscreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("✕ Exit") }
        }
    }
}
