package com.neotv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// =========================================================================
// CONFIGURATION DES PANELS À DISTANCE & CODE SECRET ADMIN
// =========================================================================
const val REMOTE_PANEL_URLS_JSON = "https://raw.githubusercontent.com/sfpp-ott/NeoTV/main/panel_urls.json"
const val REMOTE_PANEL_DEVICES_JSON = "https://raw.githubusercontent.com/sfpp-ott/NeoTV/main/panel_devices.json"
val DEFAULT_PROVIDER_URLS = listOf(
    "http://votre-serveur-principal.com:8080"
)
// Code secret à taper dans "Nom d'utilisateur" pour débloquer la case URL Serveur :
const val SECRET_ADMIN_UNLOCK_CODE = "888899"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF151A24),
                    primary = Color(0xFF38BDF8)
                )
            ) {
                NeoTvApp()
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
fun buildSafeExoPlayer(context: Context): ExoPlayer {
    val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderersFactory).build()
}

fun Modifier.tvFocusableClick(
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    defaultBorderWidth: Dp = 1.dp,
    defaultBorderColor: Color = Color(0xFF262F40),
    focusedBgColor: Color = Color(0xFF28354E),
    onFocused: () -> Unit = {},
    onClick: () -> Unit
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    this
        .clip(shape)
        .border(
            width = if (isFocused) 3.dp else defaultBorderWidth,
            color = if (isFocused) Color(0xFF38BDF8) else defaultBorderColor,
            shape = shape
        )
        .background(if (isFocused) focusedBgColor else Color.Transparent)
        .onFocusChanged {
            isFocused = it.isFocused
            if (it.isFocused) onFocused()
        }
        .focusable()
        .clickable { onClick() }
}

fun Modifier.tiviGuideCellFocus(
    isCurrentBroadcast: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val bg = when {
        isFocused -> Color(0xFFF1F5F9)
        isCurrentBroadcast -> Color(0xFF243046)
        else -> Color(0xFF161D2A)
    }
    this
        .clip(RoundedCornerShape(4.dp))
        .background(bg)
        .onFocusChanged {
            isFocused = it.isFocused
            if (it.isFocused) onFocused()
        }
        .focusable()
        .clickable { onClick() }
}

fun isAdultText(text: String): Boolean {
    val lower = text.lowercase(Locale.ROOT)
    val adultKeywords = listOf(
        "adult", "xxx", "18+", "+18", "x-rated", "erot", "porn",
        "for adults", "sexe", "hentai", "dorcel", "hustler", "brazzers", "penthouse", "private"
    )
    return adultKeywords.any { lower.contains(it) }
}

data class DeviceIdentity(
    val macAddress: String,
    val deviceKey: String
)

data class RemoteDeviceConfig(
    val server: String,
    val user: String,
    val pass: String
)

data class MediaEntry(
    val id: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val logoUrl: String,
    val streamUrl: String,
    val type: String, // "LIVE", "VOD", "SERIES"
    val tvArchive: Int = 0,
    val tvArchiveDuration: Int = 0,
    val trailerId: String = "",
    val isAdult: Boolean = false,
    val addedTimestamp: Long = 0L
)

data class CategoryItem(
    val id: String,
    val name: String,
    val isAdult: Boolean = false
)

data class AccountInfo(
    val status: String = "Inconnu",
    val expDateFormatted: String = "Non disponible",
    val activeCons: String = "0",
    val maxCons: String = "1"
)

data class EpgProgram(
    val title: String,
    val description: String,
    val startTimestamp: Long,
    val stopTimestamp: Long,
    val startFormatted: String,
    val stopFormatted: String,
    val dayKey: String,
    val dayLabel: String,
    val hasArchive: Boolean
)

data class VodDetails(
    val plot: String,
    val director: String,
    val cast: String,
    val genre: String,
    val releaseDate: String,
    val duration: String,
    val rating: String,
    val backdropUrl: String,
    val youtubeTrailer: String
)

data class EpisodeItem(
    val id: String,
    val title: String,
    val season: Int,
    val streamUrl: String
)

data class TrackOption(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean
)

fun openTrailerYoutube(context: Context, title: String, youtubeCode: String) {
    val clean = youtubeCode.trim()
    val url = when {
        clean.startsWith("http") -> clean
        clean.isNotBlank() && clean != "null" -> "https://www.youtube.com/watch?v=$clean"
        else -> {
            val cleanTitle = title.replace(Regex("""^\|[A-Za-z0-9 -]+\|\s*"""), "").trim()
            val query = URLEncoder.encode("$cleanTitle bande annonce VF", "UTF-8")
            "https://www.youtube.com/results?search_query=$query"
        }
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

@Composable
fun NeoTvApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neotv_pro_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }

    var parentalPin by remember { mutableStateOf(prefs.getString("parental_pin", "0000") ?: "0000") }
    val parentalEnabled = true
    var pendingPinAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var accountInfo by remember { mutableStateOf(AccountInfo()) }

    var liveChannels by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var liveCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }

    var vodMovies by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var vodCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }

    var seriesList by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var seriesCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }

    var favorites by remember {
        mutableStateOf(prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet())
    }

    var watchHistoryIds by remember {
        mutableStateOf(prefs.getStringSet("history_ids", emptySet())?.toList() ?: emptyList())
    }

    var recentChannelIds by remember {
        mutableStateOf(prefs.getStringSet("recent_live_ids", emptySet())?.toList() ?: emptyList())
    }

    var epgCache by remember { mutableStateOf(emptyMap<String, List<EpgProgram>>()) }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var selectedTab by rememberSaveable { mutableStateOf(1) }
    var selectedCatId by rememberSaveable { mutableStateOf("ALL") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    var showSettings by remember { mutableStateOf(false) }
    var hasAutoLaunchedLive by rememberSaveable { mutableStateOf(false) }

    var playingMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var customStreamOverride by remember { mutableStateOf<String?>(null) }
    var activeReplayProgram by remember { mutableStateOf<EpgProgram?>(null) }
    var startPositionMs by remember { mutableStateOf(0L) }

    var selectedMovieForDetails by remember { mutableStateOf<MediaEntry?>(null) }
    var movieDetails by remember { mutableStateOf<VodDetails?>(null) }
    var isMovieDetailsLoading by remember { mutableStateOf(false) }

    var selectedSeries by remember { mutableStateOf<MediaEntry?>(null) }
    var seriesEpisodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var seriesTrailer by remember { mutableStateOf("") }
    var seriesPlot by remember { mutableStateOf("") }
    var isSeriesLoading by remember { mutableStateOf(false) }

    fun runWithParentalCheck(isAdult: Boolean, action: () -> Unit) {
        if (parentalEnabled && isAdult) {
            pendingPinAction = action
        } else {
            action()
        }
    }

    fun recordRecentLiveChannel(channel: MediaEntry) {
        prefs.edit().putString("last_live_channel_id", channel.id).apply()
        val updated = (listOf(channel.id) + recentChannelIds).distinct().take(15)
        recentChannelIds = updated
        prefs.edit().putStringSet("recent_live_ids", updated.toSet()).apply()
    }

    fun toggleFavorite(id: String) {
        val next = favorites.toMutableSet()
        if (next.contains(id)) next.remove(id) else next.add(id)
        favorites = next
        prefs.edit().putStringSet("favs", next).apply()
    }

    fun saveWatchProgress(mediaId: String, positionMs: Long, durationMs: Long) {
        if (positionMs > 5000L) {
            prefs.edit()
                .putLong("pos_$mediaId", positionMs)
                .putLong("dur_$mediaId", durationMs.coerceAtLeast(1L))
                .apply()
            val updated = (listOf(mediaId) + watchHistoryIds).distinct().take(30)
            watchHistoryIds = updated
            prefs.edit().putStringSet("history_ids", updated.toSet()).apply()
        }
    }

    fun loadEpgForChannel(streamId: String) {
        if (serverUrl.isBlank() || epgCache.containsKey(streamId)) return
        scope.launch {
            val progs = fetchChannelEpgList(serverUrl, username, password, streamId)
            if (progs.isNotEmpty()) {
                epgCache = epgCache + (streamId to progs)
            }
        }
    }

    fun loadAllXtreamData(forceRefresh: Boolean = false) {
        if (username.isBlank()) {
            scope.launch {
                val dev = getOrCreateDeviceIdentity(context)
                val remote = fetchRemoteDeviceActivation(dev.macAddress, dev.deviceKey)
                if (remote != null) {
                    val resolvedUrl = if (remote.server.isNotBlank()) {
                        remote.server
                    } else {
                        resolveAuthorizedServerForUser(remote.user, remote.pass, "")
                    }
                    serverUrl = resolvedUrl
                    username = remote.user
                    password = remote.pass
                    prefs.edit()
                        .putString("server", serverUrl)
                        .putString("user", username)
                        .putString("pass", password)
                        .apply()
                    loadAllXtreamData(forceRefresh = true)
                } else {
                    showSettings = true
                }
            }
            return
        }
        isLoading = true
        errorMsg = null
        scope.launch {
            try {
                if (serverUrl.isBlank()) {
                    statusText = "Recherche du serveur autorisé..."
                    serverUrl = resolveAuthorizedServerForUser(username, password, "")
                    prefs.edit().putString("server", serverUrl).apply()
                }

                statusText = "Vérification de l'abonnement..."
                accountInfo = fetchAccountInfo(serverUrl, username, password)

                statusText = "Synchronisation TV en direct..."
                val liveRes = fetchXtreamLiveStreaming(context, serverUrl, username, password, forceRefresh)
                liveCategories = liveRes.first
                liveChannels = liveRes.second

                if (!hasAutoLaunchedLive && liveChannels.isNotEmpty()) {
                    hasAutoLaunchedLive = true
                    val lastId = prefs.getString("last_live_channel_id", "") ?: ""
                    val startChannel = liveChannels.firstOrNull { it.id == lastId && !it.isAdult }
                        ?: liveChannels.firstOrNull { !it.isAdult }
                    if (startChannel != null) {
                        loadEpgForChannel(startChannel.id)
                        recordRecentLiveChannel(startChannel)
                        playingMedia = startChannel
                    }
                }
                System.gc()

                statusText = "Synchronisation Films..."
                val vodRes = fetchXtreamVodStreaming(context, serverUrl, username, password, forceRefresh)
                vodCategories = vodRes.first
                vodMovies = vodRes.second
                System.gc()

                statusText = "Synchronisation Séries..."
                val serRes = fetchXtreamSeriesStreaming(context, serverUrl, username, password, forceRefresh)
                seriesCategories = serRes.first
                seriesList = serRes.second
                System.gc()
            } catch (_: Throwable) {
                errorMsg = "Erreur de chargement : vérifiez votre connexion ou vos identifiants."
            } finally {
                isLoading = false
                statusText = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        loadAllXtreamData(forceRefresh = false)
    }

    if (pendingPinAction != null) {
        ParentalPinPromptDialog(
            expectedPin = parentalPin,
            onSuccess = {
                val act = pendingPinAction
                pendingPinAction = null
                act?.invoke()
            },
            onDismiss = { pendingPinAction = null }
        )
    }

    when {
        playingMedia != null -> {
            val currentMedia = playingMedia!!
            val progs = epgCache[currentMedia.id] ?: emptyList()
            val recentChannels = remember(recentChannelIds, liveChannels) {
                val map = liveChannels.associateBy { it.id }
                val list = recentChannelIds.mapNotNull { map[it] }
                if (list.isEmpty()) liveChannels.take(12) else list
            }

            UltimateVideoPlayerScreen(
                media = currentMedia,
                streamUrlToPlay = customStreamOverride ?: currentMedia.streamUrl,
                isReplayMode = customStreamOverride != null,
                activeReplayProgram = activeReplayProgram,
                epgList = progs,
                epgCache = epgCache,
                onRequestEpg = { loadEpgForChannel(it) },
                startPositionMs = startPositionMs,
                serverUrl = serverUrl,
                username = username,
                password = password,
                liveChannelList = if (currentMedia.type == "LIVE") {
                    liveChannels.filter {
                        if (selectedCatId == "ALL") !it.isAdult
                        else if (selectedCatId == "FAV") favorites.contains(it.id)
                        else it.categoryId == selectedCatId
                    }
                } else emptyList(),
                recentLiveChannels = recentChannels,
                onOpenGuide = {
                    selectedTab = 1
                    customStreamOverride = null
                    activeReplayProgram = null
                    playingMedia = null
                },
                onSwitchChannel = { nextChannel ->
                    runWithParentalCheck(nextChannel.isAdult) {
                        loadEpgForChannel(nextChannel.id)
                        recordRecentLiveChannel(nextChannel)
                        customStreamOverride = null
                        activeReplayProgram = null
                        startPositionMs = 0L
                        playingMedia = nextChannel
                    }
                },
                onPlayReplayProgram = { targetChannel, prog ->
                    runWithParentalCheck(targetChannel.isAdult) {
                        loadEpgForChannel(targetChannel.id)
                        recordRecentLiveChannel(targetChannel)
                        val replayUrl = buildCatchupUrl(serverUrl, username, password, targetChannel.id, prog.startTimestamp, prog.stopTimestamp)
                        activeReplayProgram = prog
                        customStreamOverride = replayUrl
                        playingMedia = targetChannel
                    }
                },
                onReturnToLive = {
                    customStreamOverride = null
                    activeReplayProgram = null
                },
                onSaveProgress = { pos, dur ->
                    if (currentMedia.type != "LIVE") {
                        saveWatchProgress(currentMedia.id, pos, dur)
                        if (currentMedia.id.startsWith("ep_") && currentMedia.categoryId.isNotBlank()) {
                            saveWatchProgress(currentMedia.categoryId, pos, dur)
                        }
                    }
                },
                onBack = {
                    customStreamOverride = null
                    activeReplayProgram = null
                    playingMedia = null
                }
            )
        }
        selectedMovieForDetails != null -> {
            val movie = selectedMovieForDetails!!
            val savedPos = prefs.getLong("pos_${movie.id}", 0L)
            MovieDetailScreen(
                movie = movie,
                details = movieDetails,
                isLoading = isMovieDetailsLoading,
                savedPositionMs = savedPos,
                isFavorite = favorites.contains(movie.id),
                onToggleFav = { toggleFavorite(movie.id) },
                onPlayFromStart = {
                    startPositionMs = 0L
                    customStreamOverride = null
                    activeReplayProgram = null
                    playingMedia = movie
                },
                onResumePlay = {
                    startPositionMs = savedPos
                    customStreamOverride = null
                    activeReplayProgram = null
                    playingMedia = movie
                },
                onWatchTrailer = {
                    val trailerCode = movieDetails?.youtubeTrailer?.ifBlank { movie.trailerId } ?: movie.trailerId
                    openTrailerYoutube(context, movie.title, trailerCode)
                },
                onBack = { selectedMovieForDetails = null }
            )
        }
        selectedSeries != null -> {
            val serie = selectedSeries!!
            val lastEpId = prefs.getString("last_ep_id_${serie.id}", "") ?: ""

            SeriesDetailScreen(
                series = serie,
                episodes = seriesEpisodes,
                plot = seriesPlot,
                isLoading = isSeriesLoading,
                prefs = prefs,
                lastPlayedEpId = lastEpId,
                isFavorite = favorites.contains(serie.id),
                onToggleFav = { toggleFavorite(serie.id) },
                onBack = { selectedSeries = null },
                onWatchTrailer = {
                    openTrailerYoutube(context, serie.title, seriesTrailer.ifBlank { serie.trailerId })
                },
                onPlayEpisode = { ep ->
                    val epKey = "ep_${ep.id}"
                    startPositionMs = prefs.getLong("pos_$epKey", 0L)
                    customStreamOverride = null
                    activeReplayProgram = null
                    prefs.edit()
                        .putString("last_ep_id_${serie.id}", ep.id)
                        .putString("last_ep_label_${serie.id}", "S${ep.season} • ${ep.title}")
                        .apply()
                    saveWatchProgress(serie.id, startPositionMs.coerceAtLeast(10000L), 3600000L)

                    playingMedia = MediaEntry(
                        id = epKey,
                        title = "${serie.title} - S${ep.season} : ${ep.title}",
                        categoryId = serie.id,
                        categoryName = "Série",
                        logoUrl = serie.logoUrl,
                        streamUrl = ep.streamUrl,
                        type = "VOD"
                    )
                }
            )
        }
        showSettings -> {
            IptvSetupScreen(
                initialServer = serverUrl,
                initialUser = username,
                initialPass = password,
                accountInfo = accountInfo,
                currentPin = parentalPin,
                onUpdatePin = { newPin ->
                    parentalPin = newPin
                    prefs.edit()
                        .putBoolean("parental_enabled", true)
                        .putString("parental_pin", newPin)
                        .apply()
                },
                onSave = { srv, usr, pwd ->
                    serverUrl = srv.trim().removeSuffix("/")
                    username = usr.trim()
                    password = pwd.trim()
                    prefs.edit()
                        .putString("server", serverUrl)
                        .putString("user", username)
                        .putString("pass", password)
                        .apply()
                    showSettings = false
                    hasAutoLaunchedLive = false
                    loadAllXtreamData(forceRefresh = true)
                },
                onCancel = { showSettings = false }
            )
        }
        else -> {
            BackHandler(enabled = selectedTab != 0 || selectedCatId != "ALL" || searchQuery.isNotEmpty()) {
                when {
                    searchQuery.isNotEmpty() -> searchQuery = ""
                    selectedCatId != "ALL" -> selectedCatId = "ALL"
                    else -> selectedTab = 0
                }
            }

            val resumeItems = remember(watchHistoryIds, vodMovies, liveChannels, seriesList) {
                val allMedia = (vodMovies + liveChannels + seriesList).associateBy { it.id }
                watchHistoryIds.mapNotNull { id ->
                    allMedia[id]?.let { entry ->
                        if (entry.type == "SERIES") {
                            val lastEpLabel = prefs.getString("last_ep_label_${entry.id}", "") ?: ""
                            if (lastEpLabel.isNotBlank()) entry.copy(title = "${entry.title} ($lastEpLabel)") else entry
                        } else entry
                    }
                }
            }

            MainScreen(
                selectedTab = selectedTab,
                onChangeTab = { newTab ->
                    if (newTab != selectedTab) {
                        selectedTab = newTab
                        selectedCatId = "ALL"
                        searchQuery = ""
                    }
                },
                selectedCatId = selectedCatId,
                onChangeCategory = { catId, isAdultCat ->
                    runWithParentalCheck(isAdultCat) {
                        selectedCatId = catId
                    }
                },
                searchQuery = searchQuery,
                onChangeSearch = { searchQuery = it },
                gridState = gridState,
                accountInfo = accountInfo,
                parentalEnabled = parentalEnabled,
                liveChannels = liveChannels,
                liveCategories = liveCategories,
                vodMovies = vodMovies,
                vodCategories = vodCategories,
                seriesList = seriesList,
                seriesCategories = seriesCategories,
                resumeItems = resumeItems,
                prefs = prefs,
                favorites = favorites,
                epgCache = epgCache,
                isLoading = isLoading,
                statusText = statusText,
                errorMsg = errorMsg,
                onToggleFav = { toggleFavorite(it) },
                onRequestEpg = { loadEpgForChannel(it) },
                onRefreshCatalog = { loadAllXtreamData(forceRefresh = true) },
                onOpenSettings = { showSettings = true },
                onPlayReplayFromGrid = { ch, prog ->
                    runWithParentalCheck(ch.isAdult) {
                        recordRecentLiveChannel(ch)
                        val replayUrl = buildCatchupUrl(serverUrl, username, password, ch.id, prog.startTimestamp, prog.stopTimestamp)
                        activeReplayProgram = prog
                        customStreamOverride = replayUrl
                        playingMedia = ch
                    }
                },
                onSelectMedia = { entry ->
                    runWithParentalCheck(entry.isAdult) {
                        when (entry.type) {
                            "SERIES" -> {
                                selectedSeries = entry
                                isSeriesLoading = true
                                scope.launch {
                                    val info = fetchSeriesEpisodesAndInfo(serverUrl, username, password, entry.id)
                                    seriesEpisodes = info.first
                                    seriesPlot = info.second
                                    seriesTrailer = info.third
                                    isSeriesLoading = false
                                }
                            }
                            "VOD" -> {
                                selectedMovieForDetails = entry
                                movieDetails = null
                                isMovieDetailsLoading = true
                                scope.launch {
                                    val rawId = entry.id.removePrefix("vod_")
                                    movieDetails = fetchVodInfo(serverUrl, username, password, rawId)
                                    isMovieDetailsLoading = false
                                }
                            }
                            else -> {
                                loadEpgForChannel(entry.id)
                                recordRecentLiveChannel(entry)
                                customStreamOverride = null
                                activeReplayProgram = null
                                startPositionMs = 0L
                                playingMedia = entry
                            }
                        }
                    }
                }
            )
        }
    }
}
@Composable
fun MainScreen(
    selectedTab: Int,
    onChangeTab: (Int) -> Unit,
    selectedCatId: String,
    onChangeCategory: (String, Boolean) -> Unit,
    searchQuery: String,
    onChangeSearch: (String) -> Unit,
    gridState: LazyGridState,
    accountInfo: AccountInfo,
    parentalEnabled: Boolean,
    liveChannels: List<MediaEntry>,
    liveCategories: List<CategoryItem>,
    vodMovies: List<MediaEntry>,
    vodCategories: List<CategoryItem>,
    seriesList: List<MediaEntry>,
    seriesCategories: List<CategoryItem>,
    resumeItems: List<MediaEntry>,
    prefs: android.content.SharedPreferences,
    favorites: Set<String>,
    epgCache: Map<String, List<EpgProgram>>,
    isLoading: Boolean,
    statusText: String,
    errorMsg: String?,
    onToggleFav: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
    onRefreshCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlayReplayFromGrid: (MediaEntry, EpgProgram) -> Unit,
    onSelectMedia: (MediaEntry) -> Unit
) {
    val tabs = listOf("Accueil", "TV en direct", "Films", "Séries", "Favoris")
    var showSearchDialog by remember { mutableStateOf(false) }

    val currentCategories = when (selectedTab) {
        1 -> liveCategories
        2 -> vodCategories
        3 -> seriesCategories
        else -> emptyList()
    }

    val currentList = remember(selectedTab, liveChannels, vodMovies, seriesList, favorites, selectedCatId, searchQuery, parentalEnabled) {
        val base = when (selectedTab) {
            1 -> if (selectedCatId == "FAV") liveChannels.filter { favorites.contains(it.id) } else liveChannels
            2 -> vodMovies
            3 -> seriesList
            4 -> (liveChannels + vodMovies + seriesList).filter { favorites.contains(it.id) }
            else -> liveChannels
        }
        base.filter { item ->
            val matchCat = when {
                selectedCatId == "ALL" -> if (parentalEnabled) !item.isAdult else true
                selectedCatId == "FAV" -> true
                selectedCatId.startsWith("CUSTOM_") -> true
                else -> item.categoryId == selectedCatId
            }
            val matchSearch = searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
            matchCat && matchSearch
        }
    }

    if (showSearchDialog) {
        val allSearchable = remember(liveChannels, vodMovies, seriesList, parentalEnabled) {
            (vodMovies + seriesList + liveChannels).filter { !parentalEnabled || !it.isAdult }
        }
        InstantSearchModalDialog(
            initialQuery = searchQuery,
            allItems = allSearchable,
            onQueryChanged = { liveQuery -> onChangeSearch(liveQuery) },
            onSelectResult = { chosen ->
                showSearchDialog = false
                onSelectMedia(chosen)
            },
            onDismiss = { showSearchDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEO", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(" TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            ) {
                itemsIndexed(tabs) { index, tab ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .tvFocusableClick(CircleShape, onClick = { onChangeTab(index) })
                            .background(if (isSelected) Color(0xFF8B5CF6) else Color(0xFF151A24))
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = tab,
                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .tvFocusableClick(CircleShape, onClick = { showSearchDialog = true })
                        .background(Color(0xFF151A24))
                        .padding(7.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Rechercher", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                }

                Box(
                    modifier = Modifier
                        .tvFocusableClick(CircleShape, onClick = onRefreshCatalog)
                        .background(Color(0xFF151A24))
                        .padding(7.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualiser", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                }

                Box(
                    modifier = Modifier
                        .tvFocusableClick(CircleShape, onClick = onOpenSettings)
                        .background(Color(0xFF8B5CF6))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (liveChannels.isEmpty()) "Activation / Compte" else "Exp: ${accountInfo.expDateFormatted}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(statusText, color = Color(0xFF38BDF8), fontSize = 12.sp)
            }
        }

        errorMsg?.let {
            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp)
        }

        if (selectedTab != 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .tvFocusableClick(
                        RoundedCornerShape(12.dp),
                        onClick = { showSearchDialog = true }
                    )
                    .background(Color(0xFF151A24))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (searchQuery.isEmpty()) "Rechercher un film, une série ou une chaîne (Appuyez sur OK)..." else "Filtre actif : $searchQuery",
                        color = if (searchQuery.isEmpty()) Color(0xFF94A3B8) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (searchQuery.isEmpty()) FontWeight.Normal else FontWeight.Bold
                    )
                }

                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .tvFocusableClick(CircleShape, onClick = { onChangeSearch("") })
                            .background(Color(0xFF262F40))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Effacer ✕", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (currentCategories.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    item {
                        CategoryChip("Ajoutés récemment (${currentList.size})", selectedCatId == "ALL") {
                            onChangeCategory("ALL", false)
                        }
                    }
                    items(currentCategories) { cat ->
                        val label = if (cat.isAdult) "🔒 ${cat.name}" else cat.name
                        CategoryChip(label, selectedCatId == cat.id) {
                            onChangeCategory(cat.id, cat.isAdult)
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                HomeDashboard(
                    liveChannels = liveChannels.filter { (!parentalEnabled || !it.isAdult) && (searchQuery.isBlank() || it.title.contains(searchQuery, true)) },
                    vodMovies = vodMovies.filter { (!parentalEnabled || !it.isAdult) && (searchQuery.isBlank() || it.title.contains(searchQuery, true)) },
                    seriesList = seriesList.filter { (!parentalEnabled || !it.isAdult) && (searchQuery.isBlank() || it.title.contains(searchQuery, true)) },
                    resumeItems = resumeItems,
                    prefs = prefs,
                    onSelectMedia = onSelectMedia,
                    onSelectTab = onChangeTab
                )
            }
            1 -> {
                TvGuideScreen(
                    categories = liveCategories,
                    selectedCatId = selectedCatId,
                    onSelectCategory = { catId, isAdultCat -> onChangeCategory(catId, isAdultCat) },
                    channels = currentList,
                    favorites = favorites,
                    onToggleFav = onToggleFav,
                    epgCache = epgCache,
                    onRequestEpg = onRequestEpg,
                    onPlayChannelFullscreen = onSelectMedia,
                    onPlayReplayProgram = onPlayReplayFromGrid
                )
            }
            2, 3, 4 -> {
                PosterGrid(
                    gridState = gridState,
                    items = currentList,
                    favorites = favorites,
                    onToggleFav = onToggleFav,
                    onPlayMedia = onSelectMedia
                )
            }
        }
    }
}

@Composable
fun InstantSearchModalDialog(
    initialQuery: String,
    allItems: List<MediaEntry>,
    onQueryChanged: (String) -> Unit,
    onSelectResult: (MediaEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialQuery) }

    val matchingResults = remember(text, allItems) {
        val q = text.trim()
        if (q.isEmpty()) emptyList()
        else allItems.asSequence()
            .filter { it.title.contains(q, ignoreCase = true) }
            .take(30)
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151A24),
        title = {
            Text("Recherche instantanée", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { newText ->
                        text = newText
                        onQueryChanged(newText.trim())
                    },
                    placeholder = { Text("Tapez les premières lettres...", color = Color(0xFF64748B), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (text.trim().isNotEmpty()) {
                    Text(
                        text = "${matchingResults.size} résultat(s) immédiat(s) :",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(matchingResults, key = { it.id }) { item ->
                            val badge = when (item.type) {
                                "VOD" -> "🎬 FILM"
                                "SERIES" -> "📺 SÉRIE"
                                else -> "🔴 TV"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvFocusableClick(RoundedCornerShape(8.dp), onClick = { onSelectResult(item) })
                                    .background(Color(0xFF0B0E14))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = item.logoUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E293B)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$badge • ${item.categoryName}", color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text("Fermer", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (text.isNotEmpty()) {
                OutlinedButton(onClick = {
                    text = ""
                    onQueryChanged("")
                }) {
                    Text("Effacer", color = Color(0xFFEF4444))
                }
            }
        }
    )
}

@Composable
fun ParentalPinPromptDialog(
    expectedPin: String,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151A24),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFACC15))
                Text("Contrôle Parental (18+)", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Veuillez entrer votre code PIN parental pour déverrouiller ce contenu :", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        enteredPin = it
                        error = false
                    },
                    label = { Text("Code PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text("Code PIN incorrect !", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (enteredPin == expectedPin) {
                        onSuccess()
                    } else {
                        error = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text("Déverrouiller", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = Color.White)
            }
        }
    )
}

// =========================================================================
// 1. GUIDE TV HORIZONTAL AVEC CRÉATION ET SUPPRESSION DE GROUPES PERSONNALISÉS
// =========================================================================
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvGuideScreen(
    categories: List<CategoryItem>,
    selectedCatId: String,
    onSelectCategory: (String, Boolean) -> Unit,
    channels: List<MediaEntry>,
    favorites: Set<String>,
    onToggleFav: (String) -> Unit,
    epgCache: Map<String, List<EpgProgram>>,
    onRequestEpg: (String) -> Unit,
    onPlayChannelFullscreen: (MediaEntry) -> Unit,
    onPlayReplayProgram: (MediaEntry, EpgProgram) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neotv_pro_prefs", Context.MODE_PRIVATE) }

    var customGroupNames by remember {
        mutableStateOf(prefs.getStringSet("custom_groups", emptySet())?.toSet() ?: emptySet())
    }
    var groupUpdateTick by remember { mutableStateOf(0) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var groupToDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var channelToAssignGroup by remember { mutableStateOf<MediaEntry?>(null) }
    var showCategoriesSidebar by remember { mutableStateOf(true) }

    fun addOrRemoveChannelInGroup(groupName: String, channelId: String) {
        val key = "group_members_$groupName"
        val current = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.contains(channelId)) current.remove(channelId) else current.add(channelId)
        prefs.edit().putStringSet(key, current).apply()
        groupUpdateTick++
    }

    fun deleteCustomGroup(groupName: String) {
        val next = customGroupNames - groupName
        customGroupNames = next
        prefs.edit()
            .putStringSet("custom_groups", next)
            .remove("group_members_$groupName")
            .apply()
        if (selectedCatId == "CUSTOM_$groupName") {
            onSelectCategory("ALL", false)
        }
        groupUpdateTick++
    }

    val displayedChannels = remember(channels, selectedCatId, favorites, customGroupNames, groupUpdateTick) {
        when {
            selectedCatId == "FAV" -> channels.filter { favorites.contains(it.id) }
            selectedCatId.startsWith("CUSTOM_") -> {
                val gName = selectedCatId.removePrefix("CUSTOM_")
                val members = prefs.getStringSet("group_members_$gName", emptySet()) ?: emptySet()
                channels.filter { members.contains(it.id) }
            }
            else -> channels
        }
    }

    val lastSavedChannelId = remember { prefs.getString("last_live_channel_id", "") ?: "" }
    var previewChannel by remember(displayedChannels) {
        mutableStateOf(displayedChannels.firstOrNull { it.id == lastSavedChannelId } ?: displayedChannels.firstOrNull())
    }
    var highlightedProgram by remember { mutableStateOf<EpgProgram?>(null) }

    val miniPlayer = remember {
        buildSafeExoPlayer(context).apply { volume = 1f }
    }

    LaunchedEffect(previewChannel?.id) {
        val url = previewChannel?.streamUrl
        if (!url.isNullOrBlank()) {
            delay(350L)
            miniPlayer.stop()
            miniPlayer.clearMediaItems()
            miniPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            miniPlayer.prepare()
            miniPlayer.playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            miniPlayer.stop()
            miniPlayer.release()
        }
    }

    if (showCreateGroupDialog) {
        var newGroupName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            containerColor = Color(0xFF151A24),
            title = { Text("Créer un nouveau groupe TV", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("Nom du groupe (ex: Sport, Enfants, Soirée...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newGroupName.trim()
                        if (clean.isNotEmpty()) {
                            val next = customGroupNames + clean
                            customGroupNames = next
                            prefs.edit().putStringSet("custom_groups", next).apply()
                        }
                        showCreateGroupDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                ) {
                    Text("Créer", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) {
                    Text("Annuler", color = Color.White)
                }
            }
        )
    }

    if (groupToDeleteConfirm != null) {
        val gName = groupToDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { groupToDeleteConfirm = null },
            containerColor = Color(0xFF151A24),
            title = { Text("Supprimer ce groupe ?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Voulez-vous vraiment supprimer votre groupe personnalisé « $gName » ?",
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteCustomGroup(gName)
                        groupToDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Oui, supprimer", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDeleteConfirm = null }) {
                    Text("Annuler", color = Color.White)
                }
            }
        )
    }

    if (channelToAssignGroup != null) {
        val ch = channelToAssignGroup!!
        AlertDialog(
            onDismissRequest = { channelToAssignGroup = null },
            containerColor = Color(0xFF151A24),
            title = { Text("Favoris & Groupes : ${ch.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        val isFav = favorites.contains(ch.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableClick(RoundedCornerShape(8.dp), onClick = {
                                    onToggleFav(ch.id)
                                    channelToAssignGroup = null
                                })
                                .background(if (isFav) Color(0xFF8B5CF6) else Color(0xFF0B0E14))
                                .padding(12.dp)
                        ) {
                            Text(if (isFav) "⭐ Retirer des Favoris" else "⭐ Ajouter aux Favoris", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(customGroupNames.toList()) { gName ->
                        val members = prefs.getStringSet("group_members_$gName", emptySet()) ?: emptySet()
                        val isInGroup = members.contains(ch.id)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .tvFocusableClick(RoundedCornerShape(8.dp), onClick = {
                                        addOrRemoveChannelInGroup(gName, ch.id)
                                        channelToAssignGroup = null
                                    })
                                    .background(if (isInGroup) Color(0xFF38BDF8) else Color(0xFF0B0E14))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = if (isInGroup) "📁 Retirer de « $gName »" else "📁 Ajouter à « $gName »",
                                    color = if (isInGroup) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .tvFocusableClick(RoundedCornerShape(8.dp), onClick = {
                                        channelToAssignGroup = null
                                        groupToDeleteConfirm = gName
                                    })
                                    .background(Color(0xFF261218)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Supprimer groupe", tint = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    channelToAssignGroup = null
                    showCreateGroupDialog = true
                }) {
                    Text("+ Nouveau groupe", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { channelToAssignGroup = null }) {
                    Text("Fermer", color = Color.White)
                }
            }
        )
    }

    var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000L)
            nowSec = System.currentTimeMillis() / 1000L
        }
    }

    val timelineStartSec = remember { (nowSec / 1800L) * 1800L }
    val timelineEndSec = remember { timelineStartSec + 6 * 3600L }

    val dpPerMinute = 4.5f
    val slotWidthDp = (30 * dpPerMinute).dp
    val channelColumnWidth = 235.dp

    val currentTimeOffsetDp = (((nowSec - timelineStartSec).coerceAtLeast(0L) / 60f) * dpPerMinute).dp

    val timeSlots = remember(timelineStartSec) {
        val sdf = SimpleDateFormat("HH:mm", Locale.FRANCE)
        (0..11).map { idx ->
            val slotSec = timelineStartSec + idx * 1800L
            sdf.format(Date(slotSec * 1000L))
        }
    }
    val dateHeader = remember(nowSec) {
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.FRANCE).format(Date(nowSec * 1000L))
    }

    val horizontalScrollState = rememberScrollState()

    Row(modifier = Modifier.fillMaxSize().padding(top = 2.dp)) {
        if (showCategoriesSidebar) {
            LazyColumn(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF0E131B))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                item {
                    CategoryRowItem("➕ Créer un groupe", false) { showCreateGroupDialog = true }
                }
                item {
                    CategoryRowItem("Favoris (${favorites.size})", selectedCatId == "FAV") { onSelectCategory("FAV", false) }
                }
                items(customGroupNames.toList()) { gName ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            CategoryRowItem("📁 $gName", selectedCatId == "CUSTOM_$gName") {
                                onSelectCategory("CUSTOM_$gName", false)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .tvFocusableClick(
                                    RoundedCornerShape(5.dp),
                                    defaultBorderWidth = 0.dp,
                                    focusedBgColor = Color(0xFFEF4444),
                                    onClick = { groupToDeleteConfirm = gName }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Supprimer $gName",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                item {
                    CategoryRowItem("Toutes les Chaînes", selectedCatId == "ALL") { onSelectCategory("ALL", false) }
                }
                items(categories) { cat ->
                    val display = if (cat.isAdult) "🔒 ${cat.name}" else cat.name
                    CategoryRowItem(display, selectedCatId == cat.id) { onSelectCategory(cat.id, cat.isAdult) }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .background(Color(0xFF0B0E14))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(235.dp)
                        .fillMaxHeight()
                        .tvFocusableClick(RoundedCornerShape(6.dp), onClick = {
                            previewChannel?.let { onPlayChannelFullscreen(it) }
                        })
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                                player = miniPlayer
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                val currentChannelProgs = previewChannel?.let { epgCache[it.id] } ?: emptyList()
                val activeProg = highlightedProgram
                    ?: currentChannelProgs.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
                    ?: currentChannelProgs.firstOrNull()

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeProg?.title ?: previewChannel?.title ?: "Pas d'information",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = previewChannel?.categoryName ?: "",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (activeProg != null) {
                        val totalDuration = (activeProg.stopTimestamp - activeProg.startTimestamp).coerceAtLeast(1L)
                        val isNow = activeProg.startTimestamp <= nowSec && activeProg.stopTimestamp > nowSec
                        val elapsed = (nowSec - activeProg.startTimestamp).coerceIn(0L, totalDuration)
                        val progFraction = elapsed.toFloat() / totalDuration.toFloat()
                        val remainMin = if (isNow) {
                            ((activeProg.stopTimestamp - nowSec) / 60L).coerceAtLeast(1L)
                        } else {
                            (totalDuration / 60L).coerceAtLeast(1L)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "${activeProg.startFormatted} – ${activeProg.stopFormatted}",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (isNow) {
                                LinearProgressIndicator(
                                    progress = progFraction,
                                    modifier = Modifier.width(65.dp).height(4.dp).clip(CircleShape),
                                    color = Color(0xFF38BDF8),
                                    trackColor = Color(0xFF334155)
                                )
                            }
                            Text(
                                text = "${remainMin} min",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp
                            )
                        }

                        Text(
                            text = activeProg.description.ifBlank { "Programme diffusé sur ${previewChannel?.title ?: ""}." },
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "Pas d'information sur le programme en cours.",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E131B))
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .width(channelColumnWidth)
                        .clickable { showCategoriesSidebar = !showCategoriesSidebar }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateHeader,
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                    timeSlots.forEach { slotLabel ->
                        Box(modifier = Modifier.width(slotWidthDp)) {
                            Text(slotLabel, color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(displayedChannels, key = { _, ch -> ch.id }) { index, ch ->
                        LaunchedEffect(ch.id) { onRequestEpg(ch.id) }
                        val allProgs = epgCache[ch.id] ?: emptyList()
                        val visibleProgs = remember(allProgs, timelineStartSec) {
                            allProgs.filter { it.stopTimestamp > timelineStartSec && it.startTimestamp < timelineEndSec }
                        }
                        val isPlayingInMini = previewChannel?.id == ch.id
                        val isFav = favorites.contains(ch.id)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(Color(0xFF0E131B)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .width(channelColumnWidth)
                                    .fillMaxHeight()
                                    .tvFocusableClick(
                                        RoundedCornerShape(4.dp),
                                        defaultBorderWidth = 0.dp,
                                        onFocused = {
                                            previewChannel = ch
                                            highlightedProgram = visibleProgs.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
                                                ?: visibleProgs.firstOrNull()
                                        },
                                        onClick = {
                                            if (previewChannel?.id == ch.id) {
                                                onPlayChannelFullscreen(ch)
                                            } else {
                                                previewChannel = ch
                                            }
                                        }
                                    )
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .tvFocusableClick(
                                            CircleShape,
                                            defaultBorderWidth = 0.dp,
                                            onClick = { channelToAssignGroup = ch }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "Favori",
                                        tint = if (isFav) Color(0xFFFACC15) else Color(0xFF475569),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "${index + 1}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(24.dp)
                                )
                                AsyncImage(
                                    model = ch.logoUrl,
                                    contentDescription = ch.title,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = ch.title,
                                    color = if (isPlayingInMini) Color(0xFF38BDF8) else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isPlayingInMini) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                if (ch.tvArchive == 1) {
                                    Icon(
                                        Icons.Default.History,
                                        contentDescription = "Replay",
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .horizontalScroll(horizontalScrollState)
                                    .fillMaxHeight(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (visibleProgs.isEmpty()) {
                                    repeat(6) { slotIdx ->
                                        var isCellFocused by remember { mutableStateOf(false) }
                                        Box(
                                            modifier = Modifier
                                                .width(slotWidthDp * 2)
                                                .fillMaxHeight()
                                                .padding(1.dp)
                                                .onFocusChanged { isCellFocused = it.isFocused }
                                                .tiviGuideCellFocus(
                                                    isCurrentBroadcast = slotIdx == 0,
                                                    onFocused = {
                                                        previewChannel = ch
                                                        highlightedProgram = null
                                                    },
                                                    onClick = {
                                                        if (previewChannel?.id == ch.id) onPlayChannelFullscreen(ch)
                                                        else previewChannel = ch
                                                    }
                                                )
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(
                                                text = "Pas d'information",
                                                color = if (isCellFocused) Color.Black else Color(0xFF64748B),
                                                fontSize = 12.sp,
                                                fontWeight = if (isCellFocused) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                } else {
                                    visibleProgs.forEach { prog ->
                                        var isCellFocused by remember { mutableStateOf(false) }
                                        val effectiveStart = prog.startTimestamp.coerceAtLeast(timelineStartSec)
                                        val effectiveStop = prog.stopTimestamp.coerceAtMost(timelineEndSec)
                                        val effectiveMinutes = ((effectiveStop - effectiveStart) / 60L).coerceAtLeast(8L)
                                        val boxWidth = (effectiveMinutes * dpPerMinute).dp

                                        val isCurrent = prog.startTimestamp <= nowSec && prog.stopTimestamp > nowSec
                                        val isPast = prog.stopTimestamp <= nowSec
                                        val canReplay = isPast && (ch.tvArchive == 1 || prog.hasArchive)

                                        Box(
                                            modifier = Modifier
                                                .width(boxWidth)
                                                .fillMaxHeight()
                                                .padding(1.dp)
                                                .onFocusChanged { isCellFocused = it.isFocused }
                                                .tiviGuideCellFocus(
                                                    isCurrentBroadcast = isCurrent,
                                                    onFocused = {
                                                        previewChannel = ch
                                                        highlightedProgram = prog
                                                    },
                                                    onClick = {
                                                        highlightedProgram = prog
                                                        when {
                                                            canReplay -> onPlayReplayProgram(ch, prog)
                                                            previewChannel?.id == ch.id -> onPlayChannelFullscreen(ch)
                                                            else -> previewChannel = ch
                                                        }
                                                    }
                                                )
                                                .padding(horizontal = 8.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(
                                                text = prog.title,
                                                color = if (isCellFocused) Color(0xFF0F172A) else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isCellFocused || isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (horizontalScrollState.value == 0) {
                    val lineX = channelColumnWidth + currentTimeOffsetDp
                    Column(
                        modifier = Modifier
                            .offset(x = lineX - 4.dp)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF38BDF8))
                        )
                        Box(
                            modifier = Modifier
                                .width(1.5.dp)
                                .fillMaxHeight()
                                .background(Color(0xAA38BDF8))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryRowItem(name: String, isSelected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val bg = when {
        isFocused -> Color(0xFFF1F5F9)
        isSelected -> Color(0xFF28354E)
        else -> Color.Transparent
    }
    val txtColor = if (isFocused) Color(0xFF0F172A) else Color.White

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = name,
            color = txtColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected || isFocused) FontWeight.ExtraBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TvActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    baseBgColor: Color,
    baseContentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color(0xFF38BDF8) else Color(0x44FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .background(if (isFocused) Color(0xFF38BDF8) else baseBgColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isFocused) Color.Black else baseContentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = if (isFocused) Color.Black else baseContentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MovieDetailScreen(
    movie: MediaEntry,
    details: VodDetails?,
    isLoading: Boolean,
    savedPositionMs: Long,
    isFavorite: Boolean,
    onToggleFav: () -> Unit,
    onPlayFromStart: () -> Unit,
    onResumePlay: () -> Unit,
    onWatchTrailer: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var isMuted by remember { mutableStateOf(false) }
    val playButtonFocusRequester = remember { FocusRequester() }

    val previewPlayer = remember {
        buildSafeExoPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(movie.streamUrl)))
            prepare()
            seekTo(480_000L)
            playWhenReady = true
            volume = 0.8f
        }
    }

    LaunchedEffect(Unit) {
        delay(150L)
        runCatching { playButtonFocusRequester.requestFocus() }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer.stop()
            previewPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                    player = previewPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to Color(0xFF0B0E14),
                        0.28f to Color(0xFF0B0E14),
                        0.50f to Color(0xCC0B0E14),
                        0.75f to Color(0x440B0E14),
                        1.00f to Color.Transparent
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color(0x660B0E14),
                        0.20f to Color.Transparent,
                        0.45f to Color(0x660B0E14),
                        0.62f to Color(0xDD0B0E14),
                        0.74f to Color(0xFF0B0E14),
                        1.00f to Color(0xFF0B0E14)
                    )
                )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .tvFocusableClick(CircleShape, onClick = onBack)
                            .background(Color(0xAA151A24)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .tvFocusableClick(CircleShape, onClick = {
                                    isMuted = !isMuted
                                    previewPlayer.volume = if (isMuted) 0f else 1f
                                })
                                .background(Color(0xAA151A24)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Son extrait",
                                tint = Color.White
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .tvFocusableClick(CircleShape, onClick = onToggleFav)
                                .background(Color(0xAA151A24)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favori",
                                tint = if (isFavorite) Color(0xFFFACC15) else Color.White
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    AsyncImage(
                        model = movie.logoUrl,
                        contentDescription = movie.title,
                        modifier = Modifier
                            .width(115.dp)
                            .height(170.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(movie.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!details?.rating.isNullOrBlank()) {
                                Text("⭐ ${details?.rating}/10", color = Color(0xFFFACC15), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            if (!details?.releaseDate.isNullOrBlank()) {
                                Text(details?.releaseDate ?: "", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            }
                            if (!details?.duration.isNullOrBlank()) {
                                Text(details?.duration ?: "", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Text(
                            text = details?.genre?.ifBlank { movie.categoryName } ?: movie.categoryName,
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TvActionButton(
                        text = if (savedPositionMs > 10000L) "Depuis le début" else "Lecture",
                        icon = Icons.Default.PlayArrow,
                        baseBgColor = Color.White,
                        baseContentColor = Color.Black,
                        modifier = Modifier.focusRequester(playButtonFocusRequester),
                        onClick = {
                            previewPlayer.pause()
                            onPlayFromStart()
                        }
                    )

                    if (savedPositionMs > 10000L) {
                        val min = (savedPositionMs / 60000).toInt()
                        TvActionButton(
                            text = "Reprendre (${min}m)",
                            icon = Icons.Default.PlayArrow,
                            baseBgColor = Color(0xFF8B5CF6),
                            baseContentColor = Color.White,
                            onClick = {
                                previewPlayer.pause()
                                onResumePlay()
                            }
                        )
                    }

                    TvActionButton(
                        text = "Bande-annonce",
                        icon = Icons.Default.Movie,
                        baseBgColor = Color(0xFFDC2626),
                        baseContentColor = Color.White,
                        onClick = {
                            previewPlayer.pause()
                            onWatchTrailer()
                        }
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF151A24))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Synopsis", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (isLoading) {
                        Text("Chargement du résumé...", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    } else {
                        Text(
                            text = details?.plot?.ifBlank { "Aucun synopsis détaillé renseigné pour ce film." } ?: "",
                            color = Color(0xFFE2E8F0),
                            fontSize = 14.sp
                        )
                        if (!details?.cast.isNullOrBlank()) {
                            Text("Distribution : ${details?.cast}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        if (!details?.director.isNullOrBlank()) {
                            Text("Réalisation : ${details?.director}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SeriesDetailScreen(
    series: MediaEntry,
    episodes: List<EpisodeItem>,
    plot: String,
    isLoading: Boolean,
    prefs: android.content.SharedPreferences,
    lastPlayedEpId: String,
    isFavorite: Boolean,
    onToggleFav: () -> Unit,
    onBack: () -> Unit,
    onWatchTrailer: () -> Unit,
    onPlayEpisode: (EpisodeItem) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }

    LaunchedEffect(seasons, lastPlayedEpId) {
        if (selectedSeason == null && seasons.isNotEmpty()) {
            val lastEpSeason = episodes.firstOrNull { it.id == lastPlayedEpId }?.season
            selectedSeason = lastEpSeason ?: seasons.first()
        }
    }

    val previewPlayer = remember {
        buildSafeExoPlayer(context).apply {
            volume = 0.6f
        }
    }

    LaunchedEffect(episodes.firstOrNull()?.streamUrl) {
        val firstUrl = episodes.firstOrNull()?.streamUrl
        if (!firstUrl.isNullOrBlank()) {
            previewPlayer.stop()
            previewPlayer.clearMediaItems()
            previewPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(firstUrl)))
            previewPlayer.prepare()
            previewPlayer.seekTo(180_000L)
            previewPlayer.playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer.stop()
            previewPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                    player = previewPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to Color(0xFF0B0E14),
                        0.28f to Color(0xFF0B0E14),
                        0.50f to Color(0xCC0B0E14),
                        0.75f to Color(0x440B0E14),
                        1.00f to Color.Transparent
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color(0x660B0E14),
                        0.20f to Color.Transparent,
                        0.45f to Color(0x660B0E14),
                        0.62f to Color(0xDD0B0E14),
                        0.74f to Color(0xFF0B0E14),
                        1.00f to Color(0xFF0B0E14)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .tvFocusableClick(CircleShape, onClick = onBack)
                            .background(Color(0xAA151A24)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(series.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                        if (plot.isNotBlank()) {
                            Text(plot, color = Color(0xFFCBD5E1), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val resumeEp = episodes.firstOrNull { it.id == lastPlayedEpId }
                    if (resumeEp != null) {
                        TvActionButton(
                            text = "Reprendre S${resumeEp.season}",
                            icon = Icons.Default.PlayArrow,
                            baseBgColor = Color(0xFF8B5CF6),
                            baseContentColor = Color.White,
                            onClick = {
                                previewPlayer.pause()
                                onPlayEpisode(resumeEp)
                            }
                        )
                    }

                    TvActionButton(
                        text = "Bande-annonce",
                        icon = Icons.Default.Movie,
                        baseBgColor = Color(0xFFDC2626),
                        baseContentColor = Color.White,
                        onClick = {
                            previewPlayer.pause()
                            onWatchTrailer()
                        }
                    )

                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .tvFocusableClick(CircleShape, onClick = onToggleFav)
                            .background(Color(0xAA151A24)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favori",
                            tint = if (isFavorite) Color(0xFFFACC15) else Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (seasons.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { s ->
                        CategoryChip("Saison $s", selectedSeason == s) { selectedSeason = s }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                }
            } else {
                val filteredEps = episodes.filter { selectedSeason == null || it.season == selectedSeason }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredEps) { ep ->
                        val epPos = prefs.getLong("pos_ep_${ep.id}", 0L)
                        val epDur = prefs.getLong("dur_ep_${ep.id}", 1L).coerceAtLeast(1L)
                        val hasStarted = epPos > 10000L
                        val isLastPlayed = ep.id == lastPlayedEpId
                        val progress = if (hasStarted) (epPos.toFloat() / epDur.toFloat()).coerceIn(0.05f, 0.98f) else 0f

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableClick(RoundedCornerShape(10.dp), onClick = {
                                    previewPlayer.pause()
                                    onPlayEpisode(ep)
                                })
                                .background(if (isLastPlayed) Color(0xFF1E1B4B) else Color(0xFF151A24))
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Saison ${ep.season}", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        if (hasStarted) {
                                            Text("• En cours (${(epPos / 60000).toInt()} min)", color = Color(0xFFA855F7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(ep.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            }
                            if (hasStarted) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = progress,
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                    color = Color(0xFFA855F7),
                                    trackColor = Color(0xFF334155)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeDashboard(
    liveChannels: List<MediaEntry>,
    vodMovies: List<MediaEntry>,
    seriesList: List<MediaEntry>,
    resumeItems: List<MediaEntry>,
    prefs: android.content.SharedPreferences,
    onSelectMedia: (MediaEntry) -> Unit,
    onSelectTab: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            val featured = vodMovies.firstOrNull() ?: liveChannels.firstOrNull()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .tvFocusableClick(RoundedCornerShape(18.dp), onClick = { if (featured != null) onSelectMedia(featured) })
                    .background(Color(0xFF151A24))
            ) {
                if (featured != null && featured.logoUrl.isNotBlank()) {
                    AsyncImage(
                        model = featured.logoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xEE0B0E14), Color(0xCC1E1B4B), Color(0x660B0E14))
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "DERNIERS AJOUTS • ${liveChannels.size} CHAÎNES • ${vodMovies.size} FILMS • ${seriesList.size} SÉRIES",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = featured?.title ?: "Bienvenue sur NeoTV",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Direct TV • Replay • Films & Séries triés par date d'ajout",
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvActionButton(
                                text = "TV en direct",
                                icon = Icons.Default.LiveTv,
                                baseBgColor = Color(0xFF8B5CF6),
                                baseContentColor = Color.White,
                                onClick = { onSelectTab(1) }
                            )
                            TvActionButton(
                                text = "Regarder",
                                icon = Icons.Default.PlayArrow,
                                baseBgColor = Color.White,
                                baseContentColor = Color.Black,
                                onClick = { if (featured != null) onSelectMedia(featured) }
                            )
                        }
                    }
                }
            }
        }

        if (resumeItems.isNotEmpty()) {
            item {
                Text("Reprendre la lecture (Films & Séries)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resumeItems) { item ->
                        val pos = prefs.getLong("pos_${item.id}", 0L)
                        val dur = prefs.getLong("dur_${item.id}", 1L).coerceAtLeast(1L)
                        val progress = (pos.toFloat() / dur.toFloat()).coerceIn(0.08f, 0.98f)

                        Column(
                            modifier = Modifier
                                .width(205.dp)
                                .tvFocusableClick(RoundedCornerShape(14.dp), onClick = { onSelectMedia(item) })
                                .background(Color(0xFF151A24))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = item.logoUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = if (item.type == "SERIES") "Reprendre la série" else "${(progress * 100).toInt()}% visionné",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                color = Color(0xFFA855F7),
                                trackColor = Color(0xFF334155)
                            )
                        }
                    }
                }
            }
        }

        if (vodMovies.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Derniers Films ajoutés sur le serveur", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Tout voir >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(2) })
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(vodMovies.take(25)) { movie ->
                        PosterCard(movie, onClick = { onSelectMedia(movie) })
                    }
                }
            }
        }

        if (seriesList.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dernières Séries ajoutées sur le serveur", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Tout voir >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(3) })
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(seriesList.take(25)) { serie ->
                        PosterCard(serie, onClick = { onSelectMedia(serie) })
                    }
                }
            }
        }
    }
}
// =========================================================================
// 3. LECTEUR VIDÉO PLEIN ÉCRAN AVEC BARRE D'AVANCEMENT INTERACTIVE ET NAVIGATION FIRE STICK 100% FLUIDE
// =========================================================================
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun UltimateVideoPlayerScreen(
    media: MediaEntry,
    streamUrlToPlay: String,
    isReplayMode: Boolean,
    activeReplayProgram: EpgProgram?,
    epgList: List<EpgProgram>,
    epgCache: Map<String, List<EpgProgram>>,
    onRequestEpg: (String) -> Unit,
    startPositionMs: Long,
    serverUrl: String,
    username: String,
    password: String,
    liveChannelList: List<MediaEntry>,
    recentLiveChannels: List<MediaEntry>,
    onOpenGuide: () -> Unit,
    onSwitchChannel: (MediaEntry) -> Unit,
    onPlayReplayProgram: (MediaEntry, EpgProgram) -> Unit,
    onReturnToLive: () -> Unit,
    onSaveProgress: (Long, Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var osdVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }

    var showLeftReplayDrawer by remember { mutableStateOf(false) }
    var showTracksDialog by remember { mutableStateOf(false) }

    var resizeModeIndex by remember { mutableStateOf(0) }
    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Normal",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étiré 16:9"
    )

    var liveTimeshiftOffsetSec by remember(media.id, streamUrlToPlay) { mutableStateOf(0L) }
    var activeUrl by remember(streamUrlToPlay) { mutableStateOf(streamUrlToPlay) }
    var isPausedState by remember { mutableStateOf(false) }
    var isSeekBarFocused by remember { mutableStateOf(false) }

    val primaryOsdFocusRequester = remember { FocusRequester() }
    val backgroundPlayerFocusRequester = remember { FocusRequester() }

    val exoPlayer = remember {
        buildSafeExoPlayer(context)
    }

    LaunchedEffect(osdVisible, interactionTick, showLeftReplayDrawer, showTracksDialog) {
        if (osdVisible && !showLeftReplayDrawer && !showTracksDialog) {
            delay(120L)
            if (interactionTick <= 1) {
                runCatching { primaryOsdFocusRequester.requestFocus() }
            }
            delay(6500L)
            osdVisible = false
        } else if (!osdVisible && !showLeftReplayDrawer && !showTracksDialog) {
            delay(80L)
            runCatching { backgroundPlayerFocusRequester.requestFocus() }
        }
    }

    fun wakeUpOsd() {
        val wasHidden = !osdVisible
        osdVisible = true
        if (wasHidden) {
            interactionTick = 1
        } else {
            interactionTick++
        }
    }

    LaunchedEffect(activeUrl) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(activeUrl)))
        exoPlayer.prepare()
        if (startPositionMs > 0L && media.type != "LIVE") {
            exoPlayer.seekTo(startPositionMs)
        }
        exoPlayer.playWhenReady = true
        isPausedState = false
        wakeUpOsd()
    }

    var currentPosMs by remember { mutableStateOf(0L) }
    var totalDurMs by remember { mutableStateOf(1L) }

    LaunchedEffect(media.id, activeUrl) {
        while (true) {
            delay(1000L)
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            totalDurMs = exoPlayer.duration.coerceAtLeast(1L)
            if (exoPlayer.isPlaying && media.type != "LIVE") {
                onSaveProgress(currentPosMs, totalDurMs)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (media.type != "LIVE") {
                onSaveProgress(exoPlayer.currentPosition, exoPlayer.duration)
            }
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    BackHandler {
        when {
            showTracksDialog -> showTracksDialog = false
            showLeftReplayDrawer -> showLeftReplayDrawer = false
            osdVisible -> osdVisible = false
            media.type == "LIVE" -> onOpenGuide()
            else -> onBack()
        }
    }

    val nowSec = System.currentTimeMillis() / 1000L
    val currentEpg = activeReplayProgram
        ?: epgList.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
        ?: epgList.firstOrNull()
    val nextEpg = remember(epgList, currentEpg) {
        if (currentEpg == null) null
        else epgList.firstOrNull { it.startTimestamp >= currentEpg.stopTimestamp }
    }

    fun jumpBySeconds(deltaSec: Long) {
        wakeUpOsd()
        if (media.type != "LIVE" || isReplayMode || exoPlayer.isCurrentMediaItemSeekable) {
            val dur = exoPlayer.duration.let { if (it > 0L) it else Long.MAX_VALUE }
            val target = (exoPlayer.currentPosition + deltaSec * 1000L).coerceIn(0L, dur)
            exoPlayer.seekTo(target)
            currentPosMs = target
        } else if (media.tvArchive == 1 && serverUrl.isNotBlank()) {
            val progStart = currentEpg?.startTimestamp ?: (nowSec - 3600L)
            val maxBackSec = (nowSec - progStart).coerceAtLeast(3600L)
            liveTimeshiftOffsetSec = (liveTimeshiftOffsetSec + deltaSec).coerceIn(-maxBackSec, 0L)
            if (liveTimeshiftOffsetSec < -10L) {
                val targetUnixSec = nowSec + liveTimeshiftOffsetSec
                activeUrl = buildCatchupUrl(serverUrl, username, password, media.id, targetUnixSec, targetUnixSec + 7200L)
            } else {
                liveTimeshiftOffsetSec = 0L
                activeUrl = media.streamUrl
                onReturnToLive()
            }
        }
    }

    val dateTopRight = remember(nowSec) {
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.FRANCE).format(Date(nowSec * 1000L))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(backgroundPlayerFocusRequester)
                .focusable(enabled = !osdVisible && !showLeftReplayDrawer)
                .onKeyEvent { event ->
                    if (!osdVisible && !showLeftReplayDrawer && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                wakeUpOsd()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (media.type == "LIVE") {
                                    showLeftReplayDrawer = true
                                } else {
                                    wakeUpOsd()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                wakeUpOsd()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (showLeftReplayDrawer) {
                        showLeftReplayDrawer = false
                    } else {
                        osdVisible = !osdVisible
                        if (osdVisible) interactionTick = 1
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        isFocusable = false
                        resizeMode = resizeModes[resizeModeIndex].first
                    }
                },
                update = { view ->
                    view.resizeMode = resizeModes[resizeModeIndex].first
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (osdVisible && !showLeftReplayDrawer) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 14.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = media.categoryName.ifBlank { "TV EN DIRECT" },
                    color = Color(0xFFE2E8F0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dateTopRight,
                    color = Color(0xFFE2E8F0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.15f to Color(0xD90B0E14),
                            0.45f to Color(0xF50B0E14),
                            1.0f to Color(0xFF0B0E14)
                        )
                    )
                    .padding(horizontal = 26.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(58.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF161D2A))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = media.logoUrl,
                            contentDescription = media.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = currentEpg?.title ?: media.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentEpg != null) {
                                val totalDurSec = (currentEpg.stopTimestamp - currentEpg.startTimestamp).coerceAtLeast(1L)
                                val elapsedSec = (nowSec + liveTimeshiftOffsetSec - currentEpg.startTimestamp).coerceIn(0L, totalDurSec)
                                val frac = elapsedSec.toFloat() / totalDurSec.toFloat()
                                val remainMin = ((currentEpg.stopTimestamp - nowSec) / 60L).coerceAtLeast(0L)

                                Text(
                                    text = "${currentEpg.startFormatted} – ${currentEpg.stopFormatted}",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                LinearProgressIndicator(
                                    progress = frac,
                                    modifier = Modifier.width(65.dp).height(4.dp).clip(CircleShape),
                                    color = Color(0xFF38BDF8),
                                    trackColor = Color(0xFF334155)
                                )
                                Text(
                                    text = "${remainMin} min",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 13.sp
                                )
                            }

                            Text(
                                text = media.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            TechBadge("FHD")
                            TechBadge("50 FPS")
                            TechBadge("STEREO")
                        }

                        if (nextEpg != null) {
                            Text(
                                text = "${nextEpg.startFormatted} – ${nextEpg.stopFormatted}  ${nextEpg.title}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val progDurationSec = if (currentEpg != null) {
                    (currentEpg.stopTimestamp - currentEpg.startTimestamp).coerceAtLeast(60L)
                } else {
                    (totalDurMs / 1000L).coerceAtLeast(60L)
                }
                val progElapsedSec = if (media.type == "LIVE" && !isReplayMode && currentEpg != null) {
                    ((nowSec - currentEpg.startTimestamp) + liveTimeshiftOffsetSec).coerceIn(0L, progDurationSec)
                } else {
                    (currentPosMs / 1000L).coerceIn(0L, progDurationSec)
                }
                val barFraction = (progElapsedSec.toFloat() / progDurationSec.toFloat()).coerceIn(0f, 1f)

                val elapsedMinStr = String.format("%02d:%02d", progElapsedSec / 60, progElapsedSec % 60)
                val totalMinStr = String.format("%02d:%02d", progDurationSec / 60, progDurationSec % 60)
                val offsetMinStr = if (liveTimeshiftOffsetSec < 0L) {
                    val absSec = kotlin.math.abs(liveTimeshiftOffsetSec)
                    String.format(" [-%02d:%02d]", absSec / 60, absSec % 60)
                } else ""

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stepSec = if (media.type == "LIVE") 60L else 30L
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (isSeekBarFocused) 2.dp else 0.dp,
                                color = if (isSeekBarFocused) Color(0xFF38BDF8) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(if (isSeekBarFocused) Color(0xFF1E293B) else Color.Transparent)
                            .onFocusChanged {
                                isSeekBarFocused = it.isFocused
                                if (it.isFocused) wakeUpOsd()
                            }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    when (event.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            jumpBySeconds(-stepSec)
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            jumpBySeconds(stepSec)
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LinearProgressIndicator(
                            progress = barFraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isSeekBarFocused) 8.dp else 5.dp)
                                .clip(CircleShape),
                            color = if (isSeekBarFocused) Color.White else Color(0xFF38BDF8),
                            trackColor = Color(0xFF334155)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "$elapsedMinStr / $totalMinStr$offsetMinStr",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isSeekBarFocused) {
                                Text(
                                    text = "◀ ▶ Flèches Gauche/Droite pour reculer ou avancer",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerCircleButton("⏮ Début", "Début") {
                                if (media.type == "LIVE" && currentEpg != null) {
                                    val backToStart = currentEpg.startTimestamp - nowSec
                                    jumpBySeconds(backToStart - liveTimeshiftOffsetSec)
                                } else {
                                    exoPlayer.seekTo(0L)
                                    currentPosMs = 0L
                                }
                            }
                            PlayerCircleButton("⏪ 5m", "-5 min") { jumpBySeconds(-300L) }
                            PlayerCircleButton("⏪ 1m", "-1 min") { jumpBySeconds(-60L) }

                            var isPlayFocused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .then(
                                        if (media.type != "LIVE") Modifier.focusRequester(primaryOsdFocusRequester)
                                        else Modifier
                                    )
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isPlayFocused) 3.dp else 1.dp,
                                        color = if (isPlayFocused) Color.White else Color(0xFF38BDF8),
                                        shape = CircleShape
                                    )
                                    .background(if (isPlayFocused) Color(0xFF38BDF8) else Color(0xFF28354E))
                                    .onFocusChanged {
                                        isPlayFocused = it.isFocused
                                        if (it.isFocused) wakeUpOsd()
                                    }
                                    .focusable()
                                    .clickable {
                                        wakeUpOsd()
                                        if (exoPlayer.isPlaying) {
                                            exoPlayer.pause()
                                            isPausedState = true
                                        } else {
                                            exoPlayer.play()
                                            isPausedState = false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPausedState) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = "Play/Pause",
                                    tint = if (isPlayFocused) Color.Black else Color.White
                                )
                            }

                            PlayerCircleButton("1m ⏩", "+1 min") { jumpBySeconds(60L) }
                            PlayerCircleButton("5m ⏩", "+5 min") { jumpBySeconds(300L) }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerCircleButton("💬 Audio/ST", "Audio/ST") { showTracksDialog = true }
                            PlayerCircleButton("📐 ${resizeModes[resizeModeIndex].second}", "Format") {
                                wakeUpOsd()
                                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                            }
                            if (media.type == "LIVE") {
                                val isBehindLive = isReplayMode || liveTimeshiftOffsetSec < 0L
                                PlayerCircleButton(
                                    label = "((•)) Direct",
                                    contentDesc = "Retour au direct",
                                    isHighlighted = isBehindLive
                                ) {
                                    wakeUpOsd()
                                    liveTimeshiftOffsetSec = 0L
                                    activeUrl = media.streamUrl
                                    onReturnToLive()
                                }
                            }
                        }
                    }
                }

                if (media.type == "LIVE") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        item {
                            OsdBottomActionCard(
                                icon = Icons.Default.List,
                                title = "Guide TV",
                                modifier = Modifier.focusRequester(primaryOsdFocusRequester),
                                onFocused = { wakeUpOsd() },
                                onClick = onOpenGuide
                            )
                        }
                        item {
                            OsdBottomActionCard(
                                icon = Icons.Default.History,
                                title = "Replay / Jours",
                                onFocused = { wakeUpOsd() },
                                onClick = {
                                    osdVisible = false
                                    showLeftReplayDrawer = true
                                }
                            )
                        }
                        items(recentLiveChannels, key = { "rec_${it.id}" }) { ch ->
                            val chProgs = epgCache[ch.id] ?: emptyList()
                            val chCurrent = chProgs.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
                            Column(
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(64.dp)
                                    .tvFocusableClick(
                                        RoundedCornerShape(8.dp),
                                        focusedBgColor = Color(0xFF38BDF8),
                                        onFocused = { wakeUpOsd() },
                                        onClick = { onSwitchChannel(ch) }
                                    )
                                    .background(if (ch.id == media.id) Color(0xFF1E293B) else Color(0xFF141A24))
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                AsyncImage(
                                    model = ch.logoUrl,
                                    contentDescription = ch.title,
                                    modifier = Modifier.height(28.dp).fillMaxWidth(),
                                    contentScale = ContentScale.Fit
                                )
                                Text(
                                    text = chCurrent?.title ?: ch.title,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showLeftReplayDrawer && liveChannelList.isNotEmpty()) {
            TiviMateLeftReplayOverlay(
                channels = liveChannelList,
                initialChannel = media,
                epgCache = epgCache,
                onRequestEpg = onRequestEpg,
                onSelectChannelOrReplay = { chosenCh, chosenProg ->
                    showLeftReplayDrawer = false
                    val isPast = chosenProg != null && chosenProg.stopTimestamp <= nowSec
                    if (isPast && (chosenCh.tvArchive == 1 || chosenProg?.hasArchive == true)) {
                        onPlayReplayProgram(chosenCh, chosenProg!!)
                    } else {
                        onSwitchChannel(chosenCh)
                    }
                },
                onClose = { showLeftReplayDrawer = false }
            )
        }

        if (showTracksDialog) {
            AudioAndSubtitlesDialog(exoPlayer = exoPlayer, onDismiss = { showTracksDialog = false })
        }
    }
}

@Composable
fun TiviMateLeftReplayOverlay(
    channels: List<MediaEntry>,
    initialChannel: MediaEntry,
    epgCache: Map<String, List<EpgProgram>>,
    onRequestEpg: (String) -> Unit,
    onSelectChannelOrReplay: (MediaEntry, EpgProgram?) -> Unit,
    onClose: () -> Unit
) {
    var selectedChannel by remember { mutableStateOf(initialChannel) }
    LaunchedEffect(selectedChannel.id) {
        onRequestEpg(selectedChannel.id)
    }

    val channelPrograms = epgCache[selectedChannel.id] ?: emptyList()
    val nowSec = System.currentTimeMillis() / 1000L

    val availableDays = remember(channelPrograms) {
        val pairs = channelPrograms.map { it.dayKey to it.dayLabel }.distinctBy { it.first }
        if (pairs.isEmpty()) {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date())
            val todayLbl = SimpleDateFormat("EEE,\nd MMM", Locale.FRANCE).format(Date())
            listOf(todayKey to todayLbl)
        } else pairs
    }

    val todayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date()) }
    var selectedDayKey by remember(selectedChannel.id, availableDays) {
        mutableStateOf(
            availableDays.firstOrNull { it.first == todayKey }?.first
                ?: availableDays.lastOrNull()?.first
                ?: todayKey
        )
    }

    val dayPrograms = remember(channelPrograms, selectedDayKey) {
        channelPrograms.filter { it.dayKey == selectedDayKey }
    }

    var focusedProg by remember(dayPrograms) {
        mutableStateOf(
            dayPrograms.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
                ?: dayPrograms.lastOrNull()
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD90B0E14))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .width(225.dp)
                .fillMaxHeight()
                .background(Color(0x99111622))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(channels, key = { _, ch -> "ov_${ch.id}" }) { idx, ch ->
                val isSelected = ch.id == selectedChannel.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusableClick(
                            RoundedCornerShape(6.dp),
                            defaultBorderWidth = 0.dp,
                            focusedBgColor = Color(0xFFF1F5F9),
                            onFocused = { selectedChannel = ch },
                            onClick = { onSelectChannelOrReplay(ch, null) }
                        )
                        .background(if (isSelected) Color(0xFF243046) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${idx + 1}", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                    AsyncImage(
                        model = ch.logoUrl,
                        contentDescription = ch.title,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ch.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .width(390.dp)
                .fillMaxHeight()
                .background(Color(0x99111622))
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = selectedChannel.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    contentScale = ContentScale.Fit
                )
                Column {
                    Text(selectedChannel.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text(selectedChannel.categoryName, color = Color(0xFF38BDF8), fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (dayPrograms.isEmpty()) {
                        item {
                            Text("Aucun programme archivé pour ce jour.", color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                        }
                    } else {
                        items(dayPrograms) { prog ->
                            var isFocused by remember { mutableStateOf(false) }
                            val isNow = prog.startTimestamp <= nowSec && prog.stopTimestamp > nowSec
                            val isPast = prog.stopTimestamp <= nowSec
                            val canReplay = isPast && (selectedChannel.tvArchive == 1 || prog.hasArchive)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .tiviGuideCellFocus(
                                        isCurrentBroadcast = isNow,
                                        onFocused = { focusedProg = prog },
                                        onClick = { onSelectChannelOrReplay(selectedChannel, prog) }
                                    )
                                    .padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = prog.startFormatted,
                                    color = if (isFocused) Color.Black else Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = prog.title,
                                    color = if (isFocused) Color.Black else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = if (isNow || isFocused) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (canReplay) {
                                    Icon(
                                        Icons.Default.History,
                                        contentDescription = "Replay disponible",
                                        tint = if (isFocused) Color.Black else Color(0xFF38BDF8),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .width(74.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF0E131B))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(availableDays, key = { it.first }) { (dKey, dLabel) ->
                        val isDaySelected = dKey == selectedDayKey
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableClick(
                                    RoundedCornerShape(6.dp),
                                    defaultBorderWidth = if (isDaySelected) 1.dp else 0.dp,
                                    defaultBorderColor = if (isDaySelected) Color.White else Color.Transparent,
                                    onClick = { selectedDayKey = dKey }
                                )
                                .background(if (isDaySelected) Color(0xFF28354E) else Color.Transparent)
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dLabel,
                                color = if (isDaySelected) Color.White else Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = if (isDaySelected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = focusedProg?.title ?: selectedChannel.title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (focusedProg != null) {
                val durMin = ((focusedProg!!.stopTimestamp - focusedProg!!.startTimestamp) / 60L).coerceAtLeast(1L)
                Text(
                    text = "${focusedProg!!.dayLabel.replace("\n", " ")} • ${focusedProg!!.startFormatted} – ${focusedProg!!.stopFormatted} • ${durMin} min",
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = focusedProg!!.description.ifBlank { "Appuyez sur OK pour lancer ce programme en Replay ou en Direct." },
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun TechBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF262F40))
            .border(1.dp, Color(0xFF475569), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun PlayerCircleButton(
    label: String,
    contentDesc: String,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val bg = when {
        isFocused -> Color(0xFF38BDF8)
        isHighlighted -> Color(0xFF0284C7)
        else -> Color(0xFF1E293B)
    }
    val txtColor = if (isFocused) Color.Black else Color.White

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color.White else Color(0xFF334155),
                shape = RoundedCornerShape(18.dp)
            )
            .background(bg)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = txtColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun OsdBottomActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(108.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color.White else Color(0xFF262F40),
                shape = RoundedCornerShape(8.dp)
            )
            .background(if (isFocused) Color(0xFF38BDF8) else Color(0xFF151A24))
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = title,
            tint = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            title,
            color = if (isFocused) Color.Black else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AudioAndSubtitlesDialog(exoPlayer: ExoPlayer, onDismiss: () -> Unit) {
    val tracksGroups = exoPlayer.currentTracks.groups
    val audioOptions = mutableListOf<TrackOption>()
    val subtitleOptions = mutableListOf<TrackOption>()

    for (gIndex in 0 until tracksGroups.size) {
        val group = tracksGroups[gIndex]
        if (group.type == C.TRACK_TYPE_AUDIO) {
            for (tIndex in 0 until group.length) {
                val format = group.getTrackFormat(tIndex)
                val lang = format.language?.let { Locale(it).displayLanguage } ?: "Piste Audio ${audioOptions.size + 1}"
                val label = format.label ?: lang
                audioOptions.add(TrackOption(label, gIndex, tIndex, group.isTrackSelected(tIndex)))
            }
        } else if (group.type == C.TRACK_TYPE_TEXT) {
            for (tIndex in 0 until group.length) {
                val format = group.getTrackFormat(tIndex)
                val lang = format.language?.let { Locale(it).displayLanguage } ?: "Sous-titre ${subtitleOptions.size + 1}"
                val label = format.label ?: lang
                subtitleOptions.add(TrackOption(label, gIndex, tIndex, group.isTrackSelected(tIndex)))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151A24),
        title = { Text("Pistes Audio & Sous-titres", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("🔊 Langues Audio (${audioOptions.size})", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                items(audioOptions) { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusableClick(RoundedCornerShape(8.dp), onClick = {
                                val mediaTrackGroup = tracksGroups[opt.groupIndex].mediaTrackGroup
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(mediaTrackGroup, opt.trackIndex))
                                    .build()
                                onDismiss()
                            })
                            .background(if (opt.isSelected) Color(0xFF8B5CF6) else Color(0xFF0B0E14))
                            .padding(10.dp)
                    ) {
                        Text(opt.label, color = Color.White, fontSize = 13.sp)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("💬 Sous-titres (${subtitleOptions.size})", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusableClick(RoundedCornerShape(8.dp), onClick = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                onDismiss()
                            })
                            .background(Color(0xFF0B0E14))
                            .padding(10.dp)
                    ) {
                        Text("Désactiver les sous-titres", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    }
                }
                items(subtitleOptions) { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusableClick(RoundedCornerShape(8.dp), onClick = {
                                val mediaTrackGroup = tracksGroups[opt.groupIndex].mediaTrackGroup
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(mediaTrackGroup, opt.trackIndex))
                                    .build()
                                onDismiss()
                            })
                            .background(if (opt.isSelected) Color(0xFF8B5CF6) else Color(0xFF0B0E14))
                            .padding(10.dp)
                    ) {
                        Text(opt.label, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer", color = Color(0xFF38BDF8)) }
        }
    )
}

@Composable
fun PosterCard(item: MediaEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(125.dp)
            .tvFocusableClick(RoundedCornerShape(12.dp), onClick = onClick)
            .background(Color(0xFF151A24))
    ) {
        AsyncImage(
            model = item.logoUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxWidth().height(175.dp).background(Color(0xFF1E293B)),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.padding(8.dp)) {
            Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.categoryName, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterGrid(
    gridState: LazyGridState,
    items: List<MediaEntry>,
    favorites: Set<String>,
    onToggleFav: (String) -> Unit,
    onPlayMedia: (MediaEntry) -> Unit
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 115.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { item ->
            Box(
                modifier = Modifier
                    .tvFocusableClick(RoundedCornerShape(12.dp), onClick = { onPlayMedia(item) })
                    .background(Color(0xFF151A24))
            ) {
                Column {
                    AsyncImage(
                        model = item.logoUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF1E293B)),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.categoryName, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                IconButton(
                    onClick = { onToggleFav(item.id) },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    val isFav = favorites.contains(item.id)
                    Icon(
                        imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favori",
                        tint = if (isFav) Color(0xFFFACC15) else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .tvFocusableClick(CircleShape, onClick = onClick)
            .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF151A24))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// =========================================================================
// 4. IDENTITÉ D'APPAREIL (ADRESSE MAC & CLÉ 6 CHIFFRES) + ACTIVATION À DISTANCE
// =========================================================================
fun getOrCreateDeviceIdentity(context: Context): DeviceIdentity {
    val prefs = context.getSharedPreferences("neotv_pro_prefs", Context.MODE_PRIVATE)
    val savedMac = prefs.getString("device_mac", null)
    val savedKey = prefs.getString("device_key", null)
    if (!savedMac.isNullOrBlank() && !savedKey.isNullOrBlank()) {
        return DeviceIdentity(savedMac, savedKey)
    }

    val androidId = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "neotv_default"
    } catch (_: Exception) {
        "neotv_fallback"
    }

    val hash = MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
    val b3 = String.format("%02X", hash[0])
    val b4 = String.format("%02X", hash[1])
    val b5 = String.format("%02X", hash[2])
    val mac = "00:1A:79:$b3:$b4:$b5"

    val keyNum = ((hash[3].toInt() and 0xFF) * 65536 + (hash[4].toInt() and 0xFF) * 256 + (hash[5].toInt() and 0xFF)) % 900000 + 100000
    val key = keyNum.toString()

    prefs.edit()
        .putString("device_mac", mac)
        .putString("device_key", key)
        .apply()

    return DeviceIdentity(mac, key)
}

suspend fun fetchRemoteDeviceActivation(mac: String, key: String): RemoteDeviceConfig? = withContext(Dispatchers.IO) {
    try {
        val raw = httpGet("$REMOTE_PANEL_DEVICES_JSON?t=${System.currentTimeMillis()}")
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val itemMac = obj.optString("mac", "").trim().uppercase(Locale.ROOT)
            val itemKey = obj.optString("key", "").trim()
            if (itemMac == mac.uppercase(Locale.ROOT) && (itemKey.isEmpty() || itemKey == key)) {
                val srv = obj.optString("server", "").trim().removeSuffix("/")
                val usr = obj.optString("user", "").trim()
                val pwd = obj.optString("pass", "").trim()
                if (usr.isNotBlank() && pwd.isNotBlank()) {
                    return@withContext RemoteDeviceConfig(srv, usr, pwd)
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

suspend fun resolveAuthorizedServerForUser(user: String, pass: String, fallbackServer: String): String = withContext(Dispatchers.IO) {
    val candidateUrls = mutableListOf<String>()
    try {
        val raw = httpGet(REMOTE_PANEL_URLS_JSON)
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val u = arr.optString(i).trim().removeSuffix("/")
            if (u.isNotBlank()) candidateUrls.add(u)
        }
    } catch (_: Exception) {}

    DEFAULT_PROVIDER_URLS.forEach {
        val clean = it.trim().removeSuffix("/")
        if (clean.isNotBlank() && !candidateUrls.contains(clean)) candidateUrls.add(clean)
    }
    if (fallbackServer.isNotBlank() && !candidateUrls.contains(fallbackServer)) {
        candidateUrls.add(fallbackServer)
    }

    for (srv in candidateUrls) {
        try {
            val cleanBase = if (srv.startsWith("http")) srv else "http://$srv"
            val resp = httpGet("$cleanBase/player_api.php?username=$user&password=$pass")
            val auth = JSONObject(resp).optJSONObject("user_info")?.optInt("auth", 0) ?: 0
            if (auth == 1) return@withContext cleanBase
        } catch (_: Exception) {}
    }
    candidateUrls.firstOrNull() ?: fallbackServer
}

@Composable
fun IptvSetupScreen(
    initialServer: String,
    initialUser: String,
    initialPass: String,
    accountInfo: AccountInfo,
    currentPin: String,
    onUpdatePin: (String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceIdentity = remember { getOrCreateDeviceIdentity(context) }

    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    var isVerifyingPanel by remember { mutableStateOf(false) }
    var activationStatusMsg by remember { mutableStateOf<String?>(null) }

    var adminUnlocked by remember { mutableStateOf(false) }
    var customAdminServer by remember { mutableStateOf(initialServer) }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var pinChangeMessage by remember { mutableStateOf<String?>(null) }

    if (showChangePinDialog) {
        ChangeParentalPinDialog(
            currentPin = currentPin,
            onPinChanged = { newPin ->
                onUpdatePin(newPin)
                showChangePinDialog = false
                pinChangeMessage = "Votre code parental a bien été modifié !"
            },
            onDismiss = { showChangePinDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Espace Client & Activation",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // BLOC 1 : ACTIVATION À DISTANCE PAR ADRESSE MAC & CLÉ D'APPAREIL (ZÉRO SAISIE POUR LE CLIENT !)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF151A24))
                    .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "📲 Activation à distance (Sans rien taper)",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Communiquez ces deux codes à votre installateur pour qu'il active votre abonnement à distance :",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B0E14))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ADRESSE MAC", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(deviceIdentity.macAddress, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B0E14))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("CLÉ D'APPAREIL (DEVICE KEY)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(deviceIdentity.deviceKey, color = Color(0xFFFACC15), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                activationStatusMsg?.let {
                    Text(it, color = Color(0xFFFACC15), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                TvActionButton(
                    text = if (isVerifyingPanel) "Recherche de votre activation..." else "🔄 Activer mon abonnement à distance",
                    icon = Icons.Default.Refresh,
                    baseBgColor = Color(0xFF0284C7),
                    baseContentColor = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (isVerifyingPanel) return@TvActionButton
                        isVerifyingPanel = true
                        activationStatusMsg = "Vérification de votre appareil sur le serveur..."
                        scope.launch {
                            val remoteCfg = fetchRemoteDeviceActivation(deviceIdentity.macAddress, deviceIdentity.deviceKey)
                            if (remoteCfg != null) {
                                val resolvedUrl = if (remoteCfg.server.isNotBlank()) {
                                    remoteCfg.server
                                } else {
                                    resolveAuthorizedServerForUser(remoteCfg.user, remoteCfg.pass, "")
                                }
                                isVerifyingPanel = false
                                activationStatusMsg = "✅ Appareil activé avec succès !"
                                onSave(resolvedUrl, remoteCfg.user, remoteCfg.pass)
                            } else {
                                isVerifyingPanel = false
                                activationStatusMsg = "⏳ Aucun abonnement trouvé pour la clé ${deviceIdentity.deviceKey}. Transmettez votre MAC et votre Clé à votre installateur."
                            }
                        }
                    }
                )
            }
        }

        // BLOC 2 : CONNEXION MANUELLE (Nom d'utilisateur & Mot de passe + Code secret 888899)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF151A24))
                    .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Connexion manuelle par identifiants", color = Color(0xFF8B5CF6), fontWeight = FontWeight.Bold, fontSize = 15.sp)

                if (adminUnlocked) {
                    OutlinedTextField(
                        value = customAdminServer,
                        onValueChange = { customAdminServer = it },
                        label = { Text("🔧 [Mode Admin Déverrouillé] URL Serveur") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = user,
                    onValueChange = {
                        if (it.trim() == SECRET_ADMIN_UNLOCK_CODE) {
                            adminUnlocked = true
                            user = initialUser
                        } else {
                            user = it
                        }
                    },
                    label = { Text("Nom d'utilisateur (Username)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Mot de passe (Password)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        text = if (isVerifyingPanel) "Connexion en cours..." else "Se connecter",
                        icon = Icons.Default.Check,
                        baseBgColor = Color(0xFF8B5CF6),
                        baseContentColor = Color.White,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (isVerifyingPanel) return@TvActionButton
                            if (user.trim() == SECRET_ADMIN_UNLOCK_CODE) {
                                adminUnlocked = true
                                user = initialUser
                                return@TvActionButton
                            }
                            isVerifyingPanel = true
                            scope.launch {
                                val resolvedUrl = if (adminUnlocked && customAdminServer.isNotBlank()) {
                                    customAdminServer.trim()
                                } else {
                                    resolveAuthorizedServerForUser(user.trim(), pass.trim(), customAdminServer.trim())
                                }
                                isVerifyingPanel = false
                                onSave(resolvedUrl, user, pass)
                            }
                        }
                    )
                    TvActionButton(
                        text = "Retour",
                        icon = Icons.Default.ArrowBack,
                        baseBgColor = Color(0xFF262F40),
                        baseContentColor = Color.White,
                        modifier = Modifier.weight(1f),
                        onClick = onCancel
                    )
                }
            }
        }

        // BLOC 3 : INFORMATIONS D'ABONNEMENT
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF151A24))
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Informations de votre abonnement :", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("• Statut du compte : ${accountInfo.status}", color = Color.White, fontSize = 13.sp)
                Text("• Date d'expiration : ${accountInfo.expDateFormatted}", color = Color(0xFFFACC15), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("• Connexions actives : ${accountInfo.activeCons} / ${accountInfo.maxCons}", color = Color(0xFFCBD5E1), fontSize = 13.sp)
            }
        }

        // BLOC 4 : CONTRÔLE PARENTAL
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF151A24))
                    .border(1.dp, Color(0xFFA855F7), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("🔒 Contrôle Parental (Contenus Adultes 18+)", color = Color(0xFFA855F7), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Verrouille les catégories et contenus adultes par mot de passe (par défaut : 0000)", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }

                pinChangeMessage?.let {
                    Text(it, color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                TvActionButton(
                    text = "Modifier le code PIN / Mot de passe parental",
                    icon = Icons.Default.Lock,
                    baseBgColor = Color(0xFF262F40),
                    baseContentColor = Color.White,
                    onClick = { showChangePinDialog = true }
                )
            }
        }
    }
}

@Composable
fun ChangeParentalPinDialog(
    currentPin: String,
    onPinChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151A24),
        title = { Text("Modifier le mot de passe parental", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = oldPin,
                    onValueChange = { oldPin = it; errorMsg = null },
                    label = { Text("Ancien code PIN (0000 par défaut)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it; errorMsg = null },
                    label = { Text("Nouveau code PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMsg?.let {
                    Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        oldPin != currentPin -> errorMsg = "L'ancien code PIN est incorrect."
                        newPin.length < 4 -> errorMsg = "Le nouveau code doit contenir au moins 4 caractères."
                        else -> onPinChanged(newPin)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text("Valider", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = Color.White)
            }
        }
    )
}

// =========================================================================
// 5. MOTEUR RÉSEAU EN STREAMING + TRI PAR ORDRE D'AJOUT DU SERVEUR (FILMS & SÉRIES)
// =========================================================================

fun downloadUrlToFile(urlStr: String, destFile: File) {
    val url = URL(urlStr)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20000
        readTimeout = 45000
        setRequestProperty("User-Agent", "NeoTV/8.3 (ExoPlayer)")
    }
    conn.inputStream.use { input ->
        destFile.outputStream().use { output ->
            input.copyTo(output, bufferSize = 16 * 1024)
        }
    }
}

fun getCachedOrDownloadFile(context: Context, cacheKey: String, url: String, forceRefresh: Boolean): File {
    val file = File(context.cacheDir, cacheKey)
    if (!forceRefresh && file.exists() && file.length() > 10L && (System.currentTimeMillis() - file.lastModified() < 6 * 3600 * 1000L)) {
        return file
    }
    downloadUrlToFile(url, file)
    return file
}

fun JsonReader.readNextStringSafe(): String {
    return when (peek()) {
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NULL -> {
            nextNull()
            ""
        }
        else -> {
            skipValue()
            ""
        }
    }
}

fun JsonReader.readNextIntSafe(): Int {
    return when (peek()) {
        JsonToken.NUMBER -> runCatching { nextInt() }.getOrDefault(0)
        JsonToken.STRING -> nextString().toIntOrNull() ?: 0
        JsonToken.NULL -> {
            nextNull()
            0
        }
        else -> {
            skipValue()
            0
        }
    }
}

fun JsonReader.readNextLongSafe(): Long {
    return when (peek()) {
        JsonToken.NUMBER -> runCatching { nextLong() }.getOrDefault(0L)
        JsonToken.STRING -> nextString().toLongOrNull() ?: 0L
        JsonToken.NULL -> {
            nextNull()
            0L
        }
        else -> {
            skipValue()
            0L
        }
    }
}

fun parseCategoryFileStreaming(file: File): LinkedHashMap<String, CategoryItem> {
    val map = LinkedHashMap<String, CategoryItem>()
    if (!file.exists()) return map
    try {
        JsonReader(BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8))).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return map
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                var id = ""
                var name = "Général"
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "category_id" -> id = reader.readNextStringSafe()
                        "category_name" -> name = reader.readNextStringSafe()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (id.isNotBlank()) {
                    map[id] = CategoryItem(id, name, isAdultText(name))
                }
            }
            reader.endArray()
        }
    } catch (_: Throwable) {}
    return map
}

suspend fun fetchXtreamLiveStreaming(
    context: Context,
    baseUrl: String,
    user: String,
    pass: String,
    forceRefresh: Boolean
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catFile = getCachedOrDownloadFile(context, "live_cats.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_categories", forceRefresh)
    val catMap = parseCategoryFileStreaming(catFile)

    val streamFile = getCachedOrDownloadFile(context, "live_streams.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_streams", forceRefresh)
    val list = ArrayList<MediaEntry>(4000)

    JsonReader(BufferedReader(InputStreamReader(FileInputStream(streamFile), Charsets.UTF_8))).use { reader ->
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                var id = ""
                var name = ""
                var logo = ""
                var catId = ""
                var tvArchive = 0
                var tvArchiveDur = 0
                var isAdultFlag = 0

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "stream_id" -> id = reader.readNextStringSafe()
                        "name" -> name = reader.readNextStringSafe()
                        "stream_icon" -> logo = reader.readNextStringSafe()
                        "category_id" -> catId = reader.readNextStringSafe()
                        "tv_archive" -> tvArchive = reader.readNextIntSafe()
                        "tv_archive_duration" -> tvArchiveDur = reader.readNextIntSafe()
                        "is_adult" -> isAdultFlag = reader.readNextIntSafe()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (id.isNotBlank()) {
                    val catItem = catMap[catId]
                    val catName = catItem?.name ?: "TV"
                    val isAdult = (isAdultFlag == 1) || (catItem?.isAdult == true) || isAdultText(name)
                    val url = "$cleanBase/live/$user/$pass/$id.ts"
                    list.add(MediaEntry(id, name.ifBlank { "Chaîne $id" }, catId, catName, logo, url, "LIVE", tvArchive, tvArchiveDur, isAdult = isAdult))
                }
            }
            reader.endArray()
        }
    }
    Pair(catMap.values.toList(), list)
}

suspend fun fetchXtreamVodStreaming(
    context: Context,
    baseUrl: String,
    user: String,
    pass: String,
    forceRefresh: Boolean
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catFile = getCachedOrDownloadFile(context, "vod_cats.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_vod_categories", forceRefresh)
    val catMap = parseCategoryFileStreaming(catFile)

    val vodFile = getCachedOrDownloadFile(context, "vod_streams.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_vod_streams", forceRefresh)
    val list = ArrayList<MediaEntry>(8000)

    JsonReader(BufferedReader(InputStreamReader(FileInputStream(vodFile), Charsets.UTF_8))).use { reader ->
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                var id = ""
                var name = ""
                var logo = ""
                var catId = ""
                var ext = "mp4"
                var trailer = ""
                var isAdultFlag = 0
                var addedTs = 0L

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "stream_id" -> id = reader.readNextStringSafe()
                        "name" -> name = reader.readNextStringSafe()
                        "stream_icon" -> logo = reader.readNextStringSafe()
                        "category_id" -> catId = reader.readNextStringSafe()
                        "container_extension" -> ext = reader.readNextStringSafe().ifBlank { "mp4" }
                        "youtube_trailer" -> trailer = reader.readNextStringSafe()
                        "is_adult" -> isAdultFlag = reader.readNextIntSafe()
                        "added" -> addedTs = reader.readNextLongSafe()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (id.isNotBlank()) {
                    val catItem = catMap[catId]
                    val catName = catItem?.name ?: "Cinéma"
                    val isAdult = (isAdultFlag == 1) || (catItem?.isAdult == true) || isAdultText(name)
                    val url = "$cleanBase/movie/$user/$pass/$id.$ext"
                    val effectiveAdded = if (addedTs > 0L) addedTs else (id.toLongOrNull() ?: 0L)
                    list.add(
                        MediaEntry(
                            id = "vod_$id",
                            title = name.ifBlank { "Film $id" },
                            categoryId = catId,
                            categoryName = catName,
                            logoUrl = logo,
                            streamUrl = url,
                            type = "VOD",
                            trailerId = trailer,
                            isAdult = isAdult,
                            addedTimestamp = effectiveAdded
                        )
                    )
                }
            }
            reader.endArray()
        }
    }
    list.sortByDescending { it.addedTimestamp }
    Pair(catMap.values.toList(), list)
}

suspend fun fetchXtreamSeriesStreaming(
    context: Context,
    baseUrl: String,
    user: String,
    pass: String,
    forceRefresh: Boolean
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catFile = getCachedOrDownloadFile(context, "series_cats.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_series_categories", forceRefresh)
    val catMap = parseCategoryFileStreaming(catFile)

    val serFile = getCachedOrDownloadFile(context, "series_list.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_series", forceRefresh)
    val list = ArrayList<MediaEntry>(4000)

    JsonReader(BufferedReader(InputStreamReader(FileInputStream(serFile), Charsets.UTF_8))).use { reader ->
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                var id = ""
                var name = ""
                var cover = ""
                var catId = ""
                var trailer = ""
                var lastModTs = 0L

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "series_id" -> id = reader.readNextStringSafe()
                        "name" -> name = reader.readNextStringSafe()
                        "cover" -> cover = reader.readNextStringSafe()
                        "category_id" -> catId = reader.readNextStringSafe()
                        "youtube_trailer" -> trailer = reader.readNextStringSafe()
                        "last_modified" -> lastModTs = reader.readNextLongSafe()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (id.isNotBlank()) {
                    val catItem = catMap[catId]
                    val catName = catItem?.name ?: "Série"
                    val isAdult = (catItem?.isAdult == true) || isAdultText(name)
                    val effectiveAdded = if (lastModTs > 0L) lastModTs else (id.toLongOrNull() ?: 0L)
                    list.add(
                        MediaEntry(
                            id = id,
                            title = name.ifBlank { "Série $id" },
                            categoryId = catId,
                            categoryName = catName,
                            logoUrl = cover,
                            streamUrl = "",
                            type = "SERIES",
                            trailerId = trailer,
                            isAdult = isAdult,
                            addedTimestamp = effectiveAdded
                        )
                    )
                }
            }
            reader.endArray()
        }
    }
    list.sortByDescending { it.addedTimestamp }
    Pair(catMap.values.toList(), list)
}

suspend fun fetchAccountInfo(baseUrl: String, user: String, pass: String): AccountInfo = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass")
        val userInfo = JSONObject(json).optJSONObject("user_info") ?: return@withContext AccountInfo()
        val status = userInfo.optString("status", "Actif")
        val expSec = userInfo.optString("exp_date", "").toLongOrNull()
        val expFormatted = if (expSec != null && expSec > 0) {
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(expSec * 1000L))
        } else {
            "Illimité"
        }
        val activeCons = userInfo.optString("active_cons", "0")
        val maxCons = userInfo.optString("max_connections", "1")
        AccountInfo(status, expFormatted, activeCons, maxCons)
    } catch (e: Exception) {
        AccountInfo()
    }
}

fun buildCatchupUrl(baseUrl: String, user: String, pass: String, streamId: String, startSec: Long, stopSec: Long): String {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val durationMin = ((stopSec - startSec) / 60L).coerceAtLeast(5L)
    val sdf = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
    val startFormatted = sdf.format(Date(startSec * 1000L))
    return "$cleanBase/timeshift/$user/$pass/$durationMin/$startFormatted/$streamId.ts"
}

suspend fun fetchVodInfo(baseUrl: String, user: String, pass: String, vodId: String): VodDetails = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_vod_info&vod_id=$vodId")
        val info = JSONObject(json).optJSONObject("info") ?: JSONObject()
        VodDetails(
            plot = info.optString("plot", info.optString("description", "")),
            director = info.optString("director", ""),
            cast = info.optString("cast", info.optString("actors", "")),
            genre = info.optString("genre", ""),
            releaseDate = info.optString("releasedate", ""),
            duration = info.optString("duration", ""),
            rating = info.optString("rating", ""),
            backdropUrl = info.optString("movie_image", ""),
            youtubeTrailer = info.optString("youtube_trailer", "")
        )
    } catch (e: Exception) {
        VodDetails("", "", "", "", "", "", "", "", "")
    }
}

suspend fun fetchSeriesEpisodesAndInfo(
    baseUrl: String,
    user: String,
    pass: String,
    seriesId: String
): Triple<List<EpisodeItem>, String, String> = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId")
        val root = JSONObject(json)
        val infoObj = root.optJSONObject("info") ?: JSONObject()
        val plot = infoObj.optString("plot", "")
        val trailer = infoObj.optString("youtube_trailer", "")

        val episodesObj = root.optJSONObject("episodes") ?: return@withContext Triple(emptyList(), plot, trailer)
        val result = mutableListOf<EpisodeItem>()

        val keys = episodesObj.keys()
        while (keys.hasNext()) {
            val seasonKey = keys.next()
            val epArray = episodesObj.optJSONArray(seasonKey) ?: continue
            for (i in 0 until epArray.length()) {
                val ep = epArray.getJSONObject(i)
                val epId = ep.optString("id")
                val title = ep.optString("title", "Épisode ${i + 1}")
                val ext = ep.optString("container_extension", "mp4").ifBlank { "mp4" }
                val seasonNum = ep.optInt("season", seasonKey.toIntOrNull() ?: 1)
                val url = "$cleanBase/series/$user/$pass/$epId.$ext"
                result.add(EpisodeItem(epId, title, seasonNum, url))
            }
        }
        Triple(result, plot, trailer)
    } catch (e: Exception) {
        Triple(emptyList(), "", "")
    }
}

suspend fun fetchChannelEpgList(
    baseUrl: String,
    user: String,
    pass: String,
    streamId: String
): List<EpgProgram> = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_simple_data_table&stream_id=$streamId")
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("epg_listings") ?: return@withContext emptyList()
        val list = mutableListOf<EpgProgram>()

        val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
        val dayLabelFormat = SimpleDateFormat("EEE,\nd MMM", Locale.FRANCE)
        val dateParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val title = decodeBase64IfPossible(item.optString("title", ""))
            val desc = decodeBase64IfPossible(item.optString("description", ""))

            val startStrRaw = item.optString("start", "")
            val endStrRaw = item.optString("end", "")

            val startTs = item.optString("start_timestamp", "").toLongOrNull()
                ?: runCatching { dateParser.parse(startStrRaw)?.time?.div(1000L) }.getOrNull()
                ?: 0L
            val stopTs = item.optString("stop_timestamp", "").toLongOrNull()
                ?: runCatching { dateParser.parse(endStrRaw)?.time?.div(1000L) }.getOrNull()
                ?: 0L

            val hasArchive = item.optInt("has_archive", 0) == 1

            if (title.isNotBlank() && stopTs > startTs && startTs > 0L) {
                val startDate = Date(startTs * 1000L)
                val startFormatted = timeFormat.format(startDate)
                val stopFormatted = timeFormat.format(Date(stopTs * 1000L))
                val dayKey = dayKeyFormat.format(startDate)
                val dayLabel = dayLabelFormat.format(startDate)

                list.add(
                    EpgProgram(
                        title = title,
                        description = desc,
                        startTimestamp = startTs,
                        stopTimestamp = stopTs,
                        startFormatted = startFormatted,
                        stopFormatted = stopFormatted,
                        dayKey = dayKey,
                        dayLabel = dayLabel,
                        hasArchive = hasArchive
                    )
                )
            }
        }
        list.sortedBy { it.startTimestamp }
    } catch (e: Exception) {
        emptyList()
    }
}

fun decodeBase64IfPossible(input: String): String {
    if (input.isBlank()) return ""
    return try {
        val decoded = String(Base64.decode(input, Base64.DEFAULT), Charsets.UTF_8)
        if (decoded.any { it.isLetterOrDigit() }) decoded else input
    } catch (e: Exception) {
        input
    }
}

fun httpGet(urlStr: String): String {
    val url = URL(urlStr)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 35000
        setRequestProperty("User-Agent", "NeoTV/8.3 (ExoPlayer)")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}
