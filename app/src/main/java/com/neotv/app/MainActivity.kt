package com.neotv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.view.LayoutInflater
import android.view.ViewGroup
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    primary = Color(0xFF8B5CF6)
                )
            ) {
                NeoTvApp()
            }
        }
    }
}

fun Modifier.tvFocusableClick(
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    defaultBorderWidth: Dp = 1.dp,
    defaultBorderColor: Color = Color(0xFF262F40),
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
        .background(if (isFocused) Color(0xFF28354E) else Color.Transparent)
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

data class MediaEntry(
    val id: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val logoUrl: String,
    val streamUrl: String,
    val type: String,
    val tvArchive: Int = 0,
    val tvArchiveDuration: Int = 0,
    val trailerId: String = "",
    val isAdult: Boolean = false
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
    var parentalEnabled by remember { mutableStateOf(prefs.getBoolean("parental_enabled", true)) }
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

    var epgCache by remember { mutableStateOf>>(emptyMap()) }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var selectedCatId by rememberSaveable { mutableStateOf("ALL") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    var showSettings by remember { mutableStateOf(false) }

    var playingMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var customStreamOverride by remember { mutableStateOf<String?>(null) }
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
        if (serverUrl.isBlank() || username.isBlank()) return
        isLoading = true
        errorMsg = null
        scope.launch {
            try {
                statusText = "Vérification de l'abonnement..."
                accountInfo = fetchAccountInfo(serverUrl, username, password)

                statusText = "Synchronisation TV en direct..."
                val liveRes = fetchXtreamLiveStreaming(context, serverUrl, username, password, forceRefresh)
                liveCategories = liveRes.first
                liveChannels = liveRes.second
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
            } catch (e: Throwable) {
                errorMsg = "Erreur de chargement : ${e.localizedMessage}"
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
            UltimateVideoPlayerScreen(
                media = currentMedia,
                streamUrlToPlay = customStreamOverride ?: currentMedia.streamUrl,
                isReplayMode = customStreamOverride != null,
                epgList = progs,
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
                onSwitchChannel = { nextChannel ->
                    runWithParentalCheck(nextChannel.isAdult) {
                        loadEpgForChannel(nextChannel.id)
                        customStreamOverride = null
                        startPositionMs = 0L
                        playingMedia = nextChannel
                    }
                },
                onPlayReplayProgram = { prog ->
                    val replayUrl = buildCatchupUrl(serverUrl, username, password, currentMedia.id, prog.startTimestamp, prog.stopTimestamp)
                    customStreamOverride = replayUrl
                },
                onReturnToLive = { customStreamOverride = null },
                onSaveProgress = { pos, dur ->
                    if (currentMedia.type != "LIVE") {
                        saveWatchProgress(currentMedia.id, pos, dur)
                    }
                },
                onBack = {
                    customStreamOverride = null
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
                    playingMedia = movie
                },
                onResumePlay = {
                    startPositionMs = savedPos
                    customStreamOverride = null
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
            SeriesDetailScreen(
                series = serie,
                episodes = seriesEpisodes,
                plot = seriesPlot,
                isLoading = isSeriesLoading,
                onBack = { selectedSeries = null },
                onWatchTrailer = {
                    openTrailerYoutube(context, serie.title, seriesTrailer.ifBlank { serie.trailerId })
                },
                onPlayEpisode = { ep ->
                    startPositionMs = prefs.getLong("pos_ep_${ep.id}", 0L)
                    customStreamOverride = null
                    playingMedia = MediaEntry(
                        id = "ep_${ep.id}",
                        title = "${serie.title} - S${ep.season} : ${ep.title}",
                        categoryId = "",
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
                parentalEnabled = parentalEnabled,
                currentPin = parentalPin,
                onUpdateParental = { enabled, newPin ->
                    parentalEnabled = enabled
                    parentalPin = newPin
                    prefs.edit()
                        .putBoolean("parental_enabled", enabled)
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

            val resumeItems = remember(watchHistoryIds, vodMovies, liveChannels) {
                val allMedia = (vodMovies + liveChannels).associateBy { it.id }
                watchHistoryIds.mapNotNull { allMedia[it] }
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
                        val replayUrl = buildCatchupUrl(serverUrl, username, password, ch.id, prog.startTimestamp, prog.stopTimestamp)
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
                                customStreamOverride = null
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
            val matchCat = when (selectedCatId) {
                "ALL" -> if (parentalEnabled) !item.isAdult else true
                "FAV" -> true
                else -> item.categoryId == selectedCatId
            }
            val matchSearch = searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
            matchCat && matchSearch
        }
    }

    if (showSearchDialog) {
        SearchModalDialog(
            initialQuery = searchQuery,
            onConfirmSearch = { newQuery ->
                onChangeSearch(newQuery)
                showSearchDialog = false
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
                        text = if (liveChannels.isEmpty()) "Paramètres" else "Exp: ${accountInfo.expDateFormatted}",
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
                    .height(44.dp)
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
                        text = if (searchQuery.isEmpty()) "Rechercher une chaîne, un film ou une série (Appuyez sur OK)..." else "Recherche active : $searchQuery",
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
                        CategoryChip("Tout (${currentList.size})", selectedCatId == "ALL") {
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
fun SearchModalDialog(
    initialQuery: String,
    onConfirmSearch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialQuery) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF151A24),
        title = { Text("Rechercher un programme", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Tapez un titre...", color = Color(0xFF64748B)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSearch(text.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text("Rechercher", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initialQuery.isNotEmpty()) {
                    OutlinedButton(onClick = { onConfirmSearch("") }) {
                        Text("Tout effacer", color = Color(0xFFEF4444))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Annuler", color = Color.White)
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

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvGuideScreen(
    categories: List<CategoryItem>,
    selectedCatId: String,
    onSelectCategory: (String, Boolean) -> Unit,
    channels: List<MediaEntry>,
    epgCache: Map<String, List<EpgProgram>>,
    onRequestEpg: (String) -> Unit,
    onPlayChannelFullscreen: (MediaEntry) -> Unit,
    onPlayReplayProgram: (MediaEntry, EpgProgram) -> Unit
) {
    val context = LocalContext.current
    var previewChannel by remember(channels) { mutableStateOf(channels.firstOrNull()) }
    var highlightedProgram by remember { mutableStateOf<EpgProgram?>(null) }

    val miniPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 1f
        }
    }

    LaunchedEffect(previewChannel?.streamUrl) {
        val url = previewChannel?.streamUrl
        if (!url.isNullOrBlank()) {
            miniPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            miniPlayer.prepare()
            miniPlayer.playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { miniPlayer.release() }
    }

    val nowSec = System.currentTimeMillis() / 1000L
    val timelineStartSec = remember { (nowSec / 1800L) * 1800L }
    val timelineEndSec = remember { timelineStartSec + 6 * 3600L }

    val dpPerMinute = 4f
    val slotWidthDp = (30 * dpPerMinute).dp

    val timeSlots = remember(timelineStartSec) {
        val sdf = SimpleDateFormat("HH:mm", Locale.FRANCE)
        (0..11).map { idx ->
            val slotSec = timelineStartSec + idx * 1800L
            sdf.format(Date(slotSec * 1000L))
        }
    }
    val dateHeader = remember {
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.FRANCE).format(Date())
    }

    val horizontalScrollState = rememberScrollState()

    Row(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        LazyColumn(
            modifier = Modifier
                .width(165.dp)
                .fillMaxHeight()
                .background(Color(0xFF0E131B))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                CategoryRowItem("⭐ Favoris", selectedCatId == "FAV") { onSelectCategory("FAV", false) }
            }
            item {
                CategoryRowItem("Toutes les Chaînes", selectedCatId == "ALL") { onSelectCategory("ALL", false) }
            }
            items(categories) { cat ->
                val display = if (cat.isAdult) "🔒 ${cat.name}" else cat.name
                CategoryRowItem(display, selectedCatId == cat.id) { onSelectCategory(cat.id, cat.isAdult) }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .background(Color(0xFF111622))
                    .clip(RoundedCornerShape(10.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(210.dp)
                        .fillMaxHeight()
                        .tvFocusableClick(RoundedCornerShape(8.dp), onClick = {
                            previewChannel?.let { onPlayChannelFullscreen(it) }
                        })
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                                player = miniPlayer
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
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = activeProg?.title ?: previewChannel?.title ?: "Sélectionnez une chaîne",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (activeProg != null) {
                        val totalDuration = (activeProg.stopTimestamp - activeProg.startTimestamp).coerceAtLeast(1L)
                        val elapsed = (nowSec - activeProg.startTimestamp).coerceIn(0L, totalDuration)
                        val progFraction = elapsed.toFloat() / totalDuration.toFloat()
                        val remainMin = ((activeProg.stopTimestamp - nowSec) / 60L).coerceAtLeast(0L)

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "${activeProg.startFormatted} – ${activeProg.stopFormatted}",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            LinearProgressIndicator(
                                progress = progFraction,
                                modifier = Modifier.width(70.dp).height(4.dp).clip(CircleShape),
                                color = Color(0xFF38BDF8),
                                trackColor = Color(0xFF334155)
                            )
                            if (remainMin in 1..300) {
                                Text("${remainMin} min", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                        }
                        Text(
                            text = activeProg.description.ifBlank { "En direct sur ${previewChannel?.title ?: ""}" },
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = previewChannel?.categoryName ?: "",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Cliquez une deuxième fois sur la chaîne ou sur l'aperçu vidéo pour passer en plein écran.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            maxLines = 2
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF151A24))
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateHeader,
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(210.dp)
                )

                Row(modifier = Modifier.horizontalScroll(horizontalScrollState)) {
                    timeSlots.forEach { slotLabel ->
                        Box(modifier = Modifier.width(slotWidthDp)) {
                            Text(slotLabel, color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                    LaunchedEffect(ch.id) { onRequestEpg(ch.id) }
                    val allProgs = epgCache[ch.id] ?: emptyList()
                    val visibleProgs = remember(allProgs, timelineStartSec) {
                        allProgs.filter { it.stopTimestamp > timelineStartSec && it.startTimestamp < timelineEndSec }
                    }
                    val isPlayingInMini = previewChannel?.id == ch.id

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(if (isPlayingInMini) Color(0xFF1E293B) else Color(0xFF111622)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .width(218.dp)
                                .fillMaxHeight()
                                .tvFocusableClick(
                                    RoundedCornerShape(6.dp),
                                    defaultBorderWidth = 0.dp,
                                    onFocused = {
                                        previewChannel = ch
                                        highlightedProgram = visibleProgs.firstOrNull()
                                    },
                                    onClick = {
                                        if (previewChannel?.id == ch.id) {
                                            onPlayChannelFullscreen(ch)
                                        } else {
                                            previewChannel = ch
                                            highlightedProgram = visibleProgs.firstOrNull()
                                        }
                                    }
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(28.dp)
                            )
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.title,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ch.title,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (ch.tvArchive == 1) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = "Replay",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(14.dp)
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
                                repeat(6) {
                                    Box(
                                        modifier = Modifier
                                            .width(slotWidthDp * 2)
                                            .fillMaxHeight()
                                            .padding(1.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF181F2E))
                                            .clickable {
                                                if (previewChannel?.id == ch.id) onPlayChannelFullscreen(ch)
                                                else previewChannel = ch
                                            }
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text("Pas d'information", color = Color(0xFF64748B), fontSize = 11.sp)
                                    }
                                }
                            } else {
                                visibleProgs.forEach { prog ->
                                    val effectiveStart = prog.startTimestamp.coerceAtLeast(timelineStartSec)
                                    val effectiveStop = prog.stopTimestamp.coerceAtMost(timelineEndSec)
                                    val effectiveMinutes = ((effectiveStop - effectiveStart) / 60L).coerceAtLeast(10L)
                                    val boxWidth = (effectiveMinutes * dpPerMinute).dp

                                    val isCurrent = prog.startTimestamp <= nowSec && prog.stopTimestamp > nowSec
                                    val isPast = prog.stopTimestamp <= nowSec
                                    val canReplay = isPast && (ch.tvArchive == 1 || prog.hasArchive)

                                    Box(
                                        modifier = Modifier
                                            .width(boxWidth)
                                            .fillMaxHeight()
                                            .padding(1.dp)
                                            .tvFocusableClick(
                                                RoundedCornerShape(4.dp),
                                                defaultBorderWidth = 1.dp,
                                                defaultBorderColor = if (isCurrent) Color(0xFF38BDF8) else Color(0xFF262F40),
                                                onFocused = {
                                                    previewChannel = ch
                                                    highlightedProgram = prog
                                                },
                                                onClick = {
                                                    highlightedProgram = prog
                                                    if (canReplay) {
                                                        onPlayReplayProgram(ch, prog)
                                                    } else if (previewChannel?.id == ch.id) {
                                                        onPlayChannelFullscreen(ch)
                                                    } else {
                                                        previewChannel = ch
                                                    }
                                                }
                                            )
                                            .background(if (isCurrent) Color(0xFF243046) else Color(0xFF181F2E))
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(
                                            text = prog.title,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
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
        }
    }
}

@Composable
fun CategoryRowItem(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusableClick(RoundedCornerShape(6.dp), defaultBorderWidth = 0.dp, onClick = onClick)
            .background(if (isSelected) Color(0xFFE2E8F0) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = name,
            color = if (isSelected) Color(0xFF0F172A) else Color.White,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =========================================================================
// 2. FICHE FILM : CLIP STRICT + DÉGRADÉ VERTICAL COMPLET (ZÉRO BANDE EN BAS)
// =========================================================================
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

    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(movie.streamUrl)))
            prepare()
            seekTo(480_000L)
            playWhenReady = true
            volume = 0.8f
        }
    }

    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        // CONTENEUR VIDÉO VERROUILLÉ AVEC clipToBounds() POUR EMPÊCHER TOUT DÉBORDEMENT EN BAS !
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .align(Alignment.TopEnd)
                .clip(RectangleShape)
                .clipToBounds()
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                        player = previewPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        clipChildren = true
                        clipToPadding = true
                    }
                },
                modifier = Modifier.fillMaxSize().clipToBounds()
            )

            // Dégradé horizontal de gauche à droite
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.00f to Color(0xFF0B0E14),
                            0.25f to Color(0xFF0B0E14),
                            0.45f to Color(0xE60B0E14),
                            0.65f to Color(0x880B0E14),
                            0.82f to Color(0x220B0E14),
                            1.00f to Color.Transparent
                        )
                    )
            )

            // Dégradé vertical qui devient 100% noir (#0B0E14) avant la fin de la boîte de 320.dp !
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color(0x880B0E14),
                            0.20f to Color.Transparent,
                            0.55f to Color(0x880B0E14),
                            0.78f to Color(0xEE0B0E14),
                            0.90f to Color(0xFF0B0E14),
                            1.00f to Color(0xFF0B0E14)
                        )
                    )
            )
        }

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
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.clip(CircleShape).background(Color(0x88151A24))
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                isMuted = !isMuted
                                previewPlayer.volume = if (isMuted) 0f else 1f
                            },
                            modifier = Modifier.clip(CircleShape).background(Color(0xAA151A24))
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Son extrait",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = onToggleFav,
                            modifier = Modifier.clip(CircleShape).background(Color(0xAA151A24))
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            previewPlayer.pause()
                            onPlayFromStart()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (savedPositionMs > 10000L) "Depuis le début" else "Lecture", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                    }

                    if (savedPositionMs > 10000L) {
                        val min = (savedPositionMs / 60000).toInt()
                        Button(
                            onClick = {
                                previewPlayer.pause()
                                onResumePlay()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reprendre (${min}m)", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            previewPlayer.pause()
                            onWatchTrailer()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Bande-annonce", color = Color.White, fontWeight = FontWeight.Bold)
                    }
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

// =========================================================================
// 3. FICHE SÉRIE : CLIP STRICT + DÉGRADÉ VERTICAL COMPLET (ZÉRO BANDE EN BAS)
// =========================================================================
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SeriesDetailScreen(
    series: MediaEntry,
    episodes: List<EpisodeItem>,
    plot: String,
    isLoading: Boolean,
    onBack: () -> Unit,
    onWatchTrailer: () -> Unit,
    onPlayEpisode: (EpisodeItem) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }

    LaunchedEffect(seasons) {
        if (selectedSeason == null && seasons.isNotEmpty()) {
            selectedSeason = seasons.first()
        }
    }

    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0.6f
        }
    }

    LaunchedEffect(episodes.firstOrNull()?.streamUrl) {
        val firstUrl = episodes.firstOrNull()?.streamUrl
        if (!firstUrl.isNullOrBlank()) {
            previewPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(firstUrl)))
            previewPlayer.prepare()
            previewPlayer.seekTo(180_000L)
            previewPlayer.playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .align(Alignment.TopEnd)
                .clip(RectangleShape)
                .clipToBounds()
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.texture_player_view, null) as PlayerView).apply {
                        player = previewPlayer
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        clipChildren = true
                        clipToPadding = true
                    }
                },
                modifier = Modifier.fillMaxSize().clipToBounds()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.00f to Color(0xFF0B0E14),
                            0.28f to Color(0xFF0B0E14),
                            0.48f to Color(0xCC0B0E14),
                            0.72f to Color(0x550B0E14),
                            1.00f to Color.Transparent
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color(0x880B0E14),
                            0.20f to Color.Transparent,
                            0.55f to Color(0x880B0E14),
                            0.78f to Color(0xEE0B0E14),
                            0.90f to Color(0xFF0B0E14),
                            1.00f to Color(0xFF0B0E14)
                        )
                    )
            )
        }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                    Column {
                        Text(series.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                        if (plot.isNotBlank()) {
                            Text(plot, color = Color(0xFFCBD5E1), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Button(
                    onClick = {
                        previewPlayer.pause()
                        onWatchTrailer()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Bande-annonce", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableClick(RoundedCornerShape(10.dp), onClick = {
                                    previewPlayer.pause()
                                    onPlayEpisode(ep)
                                })
                                .background(Color(0xFF151A24))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Saison ${ep.season}", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(ep.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
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
                                text = "À L'AFFICHE • ${liveChannels.size} CHAÎNES • ${vodMovies.size} FILMS • ${seriesList.size} SÉRIES",
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
                                text = "Direct TV • Guide des programmes • Films & Séries à la demande",
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onSelectTab(1) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.LiveTv, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("TV en direct", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { if (featured != null) onSelectMedia(featured) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Regarder", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (resumeItems.isNotEmpty()) {
            item {
                Text("Reprendre la lecture", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resumeItems) { item ->
                        val pos = prefs.getLong("pos_${item.id}", 0L)
                        val dur = prefs.getLong("dur_${item.id}", 1L).coerceAtLeast(1L)
                        val progress = (pos.toFloat() / dur.toFloat()).coerceIn(0.05f, 0.98f)

                        Column(
                            modifier = Modifier
                                .width(190.dp)
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
                                    Text("${(progress * 100).toInt()}% visionné", color = Color(0xFF38BDF8), fontSize = 11.sp)
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
                    Text("Films à l'affiche", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    Text("Séries", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun UltimateVideoPlayerScreen(
    media: MediaEntry,
    streamUrlToPlay: String,
    isReplayMode: Boolean,
    epgList: List<EpgProgram>,
    startPositionMs: Long,
    serverUrl: String,
    username: String,
    password: String,
    liveChannelList: List<MediaEntry>,
    onSwitchChannel: (MediaEntry) -> Unit,
    onPlayReplayProgram: (EpgProgram) -> Unit,
    onReturnToLive: () -> Unit,
    onSaveProgress: (Long, Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }

    var showZappingDrawer by remember { mutableStateOf(false) }
    var showEpgDrawer by remember { mutableStateOf(false) }
    var showTracksDialog by remember { mutableStateOf(false) }

    var resizeModeIndex by remember { mutableStateOf(0) }
    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Normal",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étiré 16:9"
    )

    var liveTimeshiftMinutes by remember { mutableStateOf(0) }
    var activeUrl by remember(streamUrlToPlay) { mutableStateOf(streamUrlToPlay) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(controlsVisible, interactionTick, showZappingDrawer, showEpgDrawer, showTracksDialog) {
        if (controlsVisible && !showZappingDrawer && !showEpgDrawer && !showTracksDialog) {
            delay(4000)
            controlsVisible = false
        }
    }

    fun wakeUpControls() {
        controlsVisible = true
        interactionTick++
    }

    LaunchedEffect(activeUrl) {
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(activeUrl)))
        exoPlayer.prepare()
        if (startPositionMs > 0L && media.type != "LIVE") {
            exoPlayer.seekTo(startPositionMs)
        }
        exoPlayer.playWhenReady = true
        wakeUpControls()
    }

    LaunchedEffect(media.id) {
        while (true) {
            delay(5000)
            if (exoPlayer.isPlaying && media.type != "LIVE") {
                onSaveProgress(exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (media.type != "LIVE") {
                onSaveProgress(exoPlayer.currentPosition, exoPlayer.duration)
            }
            exoPlayer.release()
        }
    }

    BackHandler {
        when {
            showTracksDialog -> showTracksDialog = false
            showZappingDrawer -> showZappingDrawer = false
            showEpgDrawer -> showEpgDrawer = false
            else -> onBack()
        }
    }

    fun seekLiveOrTimeshift(deltaSeconds: Int) {
        wakeUpControls()
        if (exoPlayer.isCurrentMediaItemSeekable || media.type != "LIVE" || isReplayMode) {
            val target = (exoPlayer.currentPosition + deltaSeconds * 1000L).coerceAtLeast(0L)
            exoPlayer.seekTo(target)
        } else if (media.tvArchive == 1 && serverUrl.isNotBlank()) {
            val addMin = if (deltaSeconds < 0) -1 else 1
            liveTimeshiftMinutes = (liveTimeshiftMinutes + addMin).coerceAtMost(0)
            if (liveTimeshiftMinutes < 0) {
                val startSec = (System.currentTimeMillis() / 1000L) + (liveTimeshiftMinutes * 60L)
                activeUrl = buildCatchupUrl(serverUrl, username, password, media.id, startSec, startSec + 3600L)
            } else {
                activeUrl = media.streamUrl
            }
        }
    }

    val nowSec = System.currentTimeMillis() / 1000L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
                if (controlsVisible) interactionTick++
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = resizeModes[resizeModeIndex].first
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = (visibility == android.view.View.VISIBLE)
                    })
                }
            },
            update = { view ->
                view.resizeMode = resizeModes[resizeModeIndex].first
            },
            modifier = Modifier.fillMaxSize()
        )

        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xDD000000), Color(0x99000000), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                        }
                        Column {
                            Text(text = media.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val currentEpg = epgList.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec } ?: epgList.firstOrNull()
                            if (currentEpg != null) {
                                Text(
                                    text = "${currentEpg.startFormatted}-${currentEpg.stopFormatted} : ${currentEpg.title}",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallControlChip(
                            label = "📐 ${resizeModes[resizeModeIndex].second}",
                            onClick = {
                                wakeUpControls()
                                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                            }
                        )
                        SmallControlChip(
                            label = "💬 Audio / ST",
                            onClick = {
                                wakeUpControls()
                                showTracksDialog = true
                            }
                        )
                        if (media.type == "LIVE") {
                            SmallControlChip(
                                label = "📅 Guide & Replay",
                                isActive = showEpgDrawer,
                                onClick = {
                                    wakeUpControls()
                                    showZappingDrawer = false
                                    showEpgDrawer = !showEpgDrawer
                                }
                            )
                        }
                        if (liveChannelList.isNotEmpty()) {
                            SmallControlChip(
                                label = if (showZappingDrawer) "Fermer" else "📺 Chaînes",
                                isActive = showZappingDrawer,
                                onClick = {
                                    wakeUpControls()
                                    showEpgDrawer = false
                                    showZappingDrawer = !showZappingDrawer
                                }
                            )
                        }
                    }
                }

                if (media.type == "LIVE") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 48.dp)
                    ) {
                        SmallControlChip(label = "⏪ -1 min", onClick = { seekLiveOrTimeshift(-60) })
                        SmallControlChip(label = "⏪ -15s", onClick = { seekLiveOrTimeshift(-15) })
                        SmallControlChip(label = "⏩ +15s", onClick = { seekLiveOrTimeshift(15) })
                        if (isReplayMode || liveTimeshiftMinutes < 0) {
                            SmallControlChip(
                                label = "🔴 Direct",
                                isActive = true,
                                onClick = {
                                    liveTimeshiftMinutes = 0
                                    activeUrl = media.streamUrl
                                    onReturnToLive()
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showZappingDrawer && liveChannelList.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
                    .padding(top = 80.dp)
                    .background(Color(0xEB0B0E14))
                    .border(1.dp, Color(0xFF262F40))
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(liveChannelList, key = { it.id }) { ch ->
                        val isCurrent = ch.id == media.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableClick(RoundedCornerShape(8.dp), onClick = { onSwitchChannel(ch) })
                                .background(if (isCurrent) Color(0xFF8B5CF6) else Color(0xFF151A24))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.title,
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(ch.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (showEpgDrawer) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(330.dp)
                    .align(Alignment.CenterEnd)
                    .padding(top = 80.dp)
                    .background(Color(0xEB0B0E14))
                    .border(1.dp, Color(0xFF262F40))
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text("Guide TV & Replay", color = Color(0xFF38BDF8), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    items(epgList) { prog ->
                        val isPast = prog.stopTimestamp in 1 until nowSec
                        val canReplay = isPast && (media.tvArchive == 1 || prog.hasArchive)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusableClick(
                                    RoundedCornerShape(8.dp),
                                    onClick = {
                                        if (canReplay) {
                                            showEpgDrawer = false
                                            onPlayReplayProgram(prog)
                                        }
                                    }
                                )
                                .background(Color(0xFF151A24))
                                .padding(10.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("${prog.startFormatted} - ${prog.stopFormatted}", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                if (canReplay) {
                                    Text("⏪ REPLAY", color = Color(0xFFA855F7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(prog.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (prog.description.isNotBlank()) {
                                Text(prog.description, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (showTracksDialog) {
            AudioAndSubtitlesDialog(exoPlayer = exoPlayer, onDismiss = { showTracksDialog = false })
        }
    }
}

@Composable
fun SmallControlChip(label: String, isActive: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .tvFocusableClick(CircleShape, onClick = onClick)
            .background(if (isActive) Color(0xFF8B5CF6) else Color(0xCC1E293B))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

@Composable
fun IptvSetupScreen(
    initialServer: String,
    initialUser: String,
    initialPass: String,
    accountInfo: AccountInfo,
    parentalEnabled: Boolean,
    currentPin: String,
    onUpdateParental: (Boolean, String) -> Unit,
    onSave: (String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }
    var server by remember { mutableStateOf(initialServer) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var pinChangeMessage by remember { mutableStateOf<String?>(null) }

    if (showChangePinDialog) {
        ChangeParentalPinDialog(
            currentPin = currentPin,
            onPinChanged = { newPin ->
                onUpdateParental(parentalEnabled, newPin)
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
            Text("Paramètres, Abonnement & Contrôle Parental", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔒 Contrôle Parental (Contenus Adultes 18+)", color = Color(0xFFA855F7), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Verrouille les catégories et contenus adultes par mot de passe (par défaut : 0000)", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    Switch(
                        checked = parentalEnabled,
                        onCheckedChange = { newState ->
                            onUpdateParental(newState, currentPin)
                        }
                    )
                }

                pinChangeMessage?.let {
                    Text(it, color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { showChangePinDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262F40)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFACC15), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Modifier le code PIN / Mot de passe parental", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        item {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("URL du serveur (ex: http://monserveur.com:8080)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Utilisateur (Username)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Mot de passe (Password)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSave(server, user, pass) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Enregistrer & Synchroniser", color = Color.White, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Retour", color = Color.White)
                }
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

fun downloadUrlToFile(urlStr: String, destFile: File) {
    val url = URL(urlStr)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20000
        readTimeout = 45000
        setRequestProperty("User-Agent", "NeoTV/6.2 (ExoPlayer)")
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

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "stream_id" -> id = reader.readNextStringSafe()
                        "name" -> name = reader.readNextStringSafe()
                        "stream_icon" -> logo = reader.readNextStringSafe()
                        "category_id" -> catId = reader.readNextStringSafe()
                        "container_extension" -> ext = reader.readNextStringSafe().ifBlank { "mp4" }
                        "youtube_trailer" -> trailer = reader.readNextStringSafe()
                        "is_adult" -> isAdultFlag = reader.readNextIntSafe()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (id.isNotBlank()) {
                    val catItem = catMap[catId]
                    val catName = catItem?.name ?: "Cinéma"
                    val isAdult = (isAdultFlag == 1) || (catItem?.isAdult == true) || isAdultText(name)
                    val url = "$cleanBase/movie/$user/$pass/$id.$ext"
                    list.add(MediaEntry("vod_$id", name.ifBlank { "Film $id" }, catId, catName, logo, url, "VOD", trailerId = trailer, isAdult = isAdult))
                }
            }
            reader.endArray()
        }
    }
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

                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "series_id" -> id = reader.readNextStringSafe()
                        "name" -> name = reader.readNextStringSafe()
                        "cover" -> cover = reader.readNextStringSafe()
                        "category_id" -> catId = reader.readNextStringSafe()
                        "youtube_trailer" -> trailer = reader.readNextStringSafe()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (id.isNotBlank()) {
                    val catItem = catMap[catId]
                    val catName = catItem?.name ?: "Série"
                    val isAdult = (catItem?.isAdult == true) || isAdultText(name)
                    list.add(MediaEntry(id, name.ifBlank { "Série $id" }, catId, catName, cover, "", "SERIES", trailerId = trailer, isAdult = isAdult))
                }
            }
            reader.endArray()
        }
    }
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

            val startFormatted = if (startTs > 0) timeFormat.format(Date(startTs * 1000L)) else ""
            val stopFormatted = if (stopTs > 0) timeFormat.format(Date(stopTs * 1000L)) else ""

            if (title.isNotBlank() && stopTs > startTs) {
                list.add(EpgProgram(title, desc, startTs, stopTs, startStr = startFormatted, stopFormatted = stopFormatted, hasArchive = hasArchive))
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
        setRequestProperty("User-Agent", "NeoTV/6.2 (ExoPlayer)")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}
