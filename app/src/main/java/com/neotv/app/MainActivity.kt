package com.neotv.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MASQUER LA BARRE DES TÂCHES / STATUT EN HAUT DU TÉLÉPHONE (Mode Immersif Total)
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
                NeoTvUltimateApp()
            }
        }
    }
}

// MODIFICATEUR SPÉCIAL ANDROID TV & TACTILE : Affiche un contour lumineux Cyan/Blanc très visible à la télécommande !
fun Modifier.tvFocusableClick(
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
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
        .background(if (isFocused) Color(0xFF232D42) else Color.Transparent)
        .onFocusChanged {
            isFocused = it.isFocused
            if (it.isFocused) onFocused()
        }
        .focusable()
        .clickable { onClick() }
}

data class MediaEntry(
    val id: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val logoUrl: String,
    val streamUrl: String,
    val type: String, // "LIVE", "VOD", "SERIES"
    val tvArchive: Int = 0, // 1 si la chaîne supporte le Replay / Retour en arrière
    val tvArchiveDuration: Int = 0
)

data class CategoryItem(
    val id: String,
    val name: String
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
    val rating: String
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

@Composable
fun NeoTvUltimateApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neotv_pro_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }

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

    var epgNowCache by remember { mutableStateOf<Map<String, List<EpgProgram>>>(emptyMap()) }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // MÉMORISATION DE LA NAVIGATION (Pour que le bouton Retour ne ramène JAMAIS à l'accueil par erreur !)
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var selectedCatId by rememberSaveable { mutableStateOf("ALL") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val liveListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    var showSettings by remember { mutableStateOf(false) }

    // États des écrans enfants
    var playingMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var customStreamOverride by remember { mutableStateOf<String?>(null) }
    var startPositionMs by remember { mutableStateOf(0L) }

    var channelForFullEpg by remember { mutableStateOf<MediaEntry?>(null) }
    var fullEpgPrograms by remember { mutableStateOf<List<EpgProgram>>(emptyList()) }
    var isFullEpgLoading by remember { mutableStateOf(false) }

    var selectedMovieForDetails by remember { mutableStateOf<MediaEntry?>(null) }
    var movieDetails by remember { mutableStateOf<VodDetails?>(null) }
    var isMovieDetailsLoading by remember { mutableStateOf(false) }

    var selectedSeries by remember { mutableStateOf<MediaEntry?>(null) }
    var seriesEpisodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var isSeriesLoading by remember { mutableStateOf(false) }

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
        if (serverUrl.isBlank() || epgNowCache.containsKey(streamId)) return
        scope.launch {
            val progs = fetchChannelEpgList(serverUrl, username, password, streamId, limit = 6)
            if (progs.isNotEmpty()) {
                epgNowCache = epgNowCache + (streamId to progs)
            }
        }
    }

    fun openFullEpgModal(channel: MediaEntry) {
        channelForFullEpg = channel
        isFullEpgLoading = true
        scope.launch {
            fullEpgPrograms = fetchChannelEpgList(serverUrl, username, password, channel.id, limit = 50)
            isFullEpgLoading = false
        }
    }

    fun loadAllXtreamData(forceRefresh: Boolean = false) {
        if (serverUrl.isBlank() || username.isBlank()) return
        isLoading = true
        errorMsg = null
        scope.launch {
            try {
                statusText = "Vérification de l'abonnement Xtream..."
                accountInfo = fetchAccountInfo(serverUrl, username, password)

                statusText = "Synchronisation Direct TV & Replay..."
                val liveRes = fetchXtreamLive(context, serverUrl, username, password, forceRefresh)
                liveCategories = liveRes.first
                liveChannels = liveRes.second

                statusText = "Synchronisation Catalogue Films..."
                val vodRes = fetchXtreamVod(context, serverUrl, username, password, forceRefresh)
                vodCategories = vodRes.first
                vodMovies = vodRes.second

                statusText = "Synchronisation Catalogue Séries..."
                val serRes = fetchXtreamSeries(context, serverUrl, username, password, forceRefresh)
                seriesCategories = serRes.first
                seriesList = serRes.second
            } catch (e: Exception) {
                errorMsg = "Erreur réseau : vérifiez vos identifiants (${e.localizedMessage})"
            } finally {
                isLoading = false
                statusText = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        loadAllXtreamData(forceRefresh = false)
    }

    // GESTION DES ÉCRANS AVEC PILE DE RETOUR PROPRE (Étape par étape)
    when {
        playingMedia != null -> {
            val currentMedia = playingMedia!!
            val progs = epgNowCache[currentMedia.id] ?: emptyList()
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
                    liveChannels.filter { selectedCatId == "ALL" || it.categoryId == selectedCatId }
                } else emptyList(),
                onSwitchChannel = { nextChannel ->
                    loadEpgForChannel(nextChannel.id)
                    customStreamOverride = null
                    startPositionMs = 0L
                    playingMedia = nextChannel
                },
                onPlayReplayProgram = { prog ->
                    val replayUrl = buildCatchupUrl(serverUrl, username, password, currentMedia.id, prog.startTimestamp, prog.stopTimestamp)
                    customStreamOverride = replayUrl
                },
                onReturnToLive = {
                    customStreamOverride = null
                },
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
        channelForFullEpg != null -> {
            val ch = channelForFullEpg!!
            FullEpgAndReplayScreen(
                channel = ch,
                programs = fullEpgPrograms,
                isLoading = isFullEpgLoading,
                onPlayLive = {
                    channelForFullEpg = null
                    customStreamOverride = null
                    playingMedia = ch
                },
                onPlayReplay = { prog ->
                    val replayUrl = buildCatchupUrl(serverUrl, username, password, ch.id, prog.startTimestamp, prog.stopTimestamp)
                    channelForFullEpg = null
                    customStreamOverride = replayUrl
                    playingMedia = ch
                },
                onBack = { channelForFullEpg = null }
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
                onBack = { selectedMovieForDetails = null }
            )
        }
        selectedSeries != null -> {
            SeriesDetailScreen(
                series = selectedSeries!!,
                episodes = seriesEpisodes,
                isLoading = isSeriesLoading,
                onBack = { selectedSeries = null },
                onPlayEpisode = { ep ->
                    startPositionMs = prefs.getLong("pos_ep_${ep.id}", 0L)
                    customStreamOverride = null
                    playingMedia = MediaEntry(
                        id = "ep_${ep.id}",
                        title = "${selectedSeries!!.title} - S${ep.season} : ${ep.title}",
                        categoryId = "",
                        categoryName = "Série",
                        logoUrl = selectedSeries!!.logoUrl,
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
            // Si on est dans un onglet autre qu'Accueil (ex: Direct TV, Films, Séries), le bouton Retour de la télécommande ramène d'abord à l'Accueil au lieu de fermer l'application
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

            MainNeoProScreen(
                selectedTab = selectedTab,
                onChangeTab = { newTab ->
                    if (newTab != selectedTab) {
                        selectedTab = newTab
                        selectedCatId = "ALL"
                        searchQuery = ""
                    }
                },
                selectedCatId = selectedCatId,
                onChangeCategory = { selectedCatId = it },
                searchQuery = searchQuery,
                onChangeSearch = { searchQuery = it },
                liveListState = liveListState,
                gridState = gridState,
                accountInfo = accountInfo,
                liveChannels = liveChannels,
                liveCategories = liveCategories,
                vodMovies = vodMovies,
                vodCategories = vodCategories,
                seriesList = seriesList,
                seriesCategories = seriesCategories,
                resumeItems = resumeItems,
                prefs = prefs,
                favorites = favorites,
                epgNowCache = epgNowCache,
                isLoading = isLoading,
                statusText = statusText,
                errorMsg = errorMsg,
                onToggleFav = { toggleFavorite(it) },
                onRequestEpg = { loadEpgForChannel(it) },
                onOpenFullEpg = { openFullEpgModal(it) },
                onRefreshCatalog = { loadAllXtreamData(forceRefresh = true) },
                onOpenSettings = { showSettings = true },
                onSelectMedia = { entry ->
                    when (entry.type) {
                        "SERIES" -> {
                            selectedSeries = entry
                            isSeriesLoading = true
                            scope.launch {
                                seriesEpisodes = fetchSeriesEpisodes(serverUrl, username, password, entry.id)
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
            )
        }
    }
}

@Composable
fun MainNeoProScreen(
    selectedTab: Int,
    onChangeTab: (Int) -> Unit,
    selectedCatId: String,
    onChangeCategory: (String) -> Unit,
    searchQuery: String,
    onChangeSearch: (String) -> Unit,
    liveListState: LazyListState,
    gridState: LazyGridState,
    accountInfo: AccountInfo,
    liveChannels: List<MediaEntry>,
    liveCategories: List<CategoryItem>,
    vodMovies: List<MediaEntry>,
    vodCategories: List<CategoryItem>,
    seriesList: List<MediaEntry>,
    seriesCategories: List<CategoryItem>,
    resumeItems: List<MediaEntry>,
    prefs: android.content.SharedPreferences,
    favorites: Set<String>,
    epgNowCache: Map<String, List<EpgProgram>>,
    isLoading: Boolean,
    statusText: String,
    errorMsg: String?,
    onToggleFav: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
    onOpenFullEpg: (MediaEntry) -> Unit,
    onRefreshCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectMedia: (MediaEntry) -> Unit
) {
    val tabs = listOf("Accueil", "Direct TV", "Films", "Séries", "Favoris")

    val currentCategories = when (selectedTab) {
        1 -> liveCategories
        2 -> vodCategories
        3 -> seriesCategories
        else -> emptyList()
    }

    val currentList = remember(selectedTab, liveChannels, vodMovies, seriesList, favorites, selectedCatId, searchQuery) {
        val base = when (selectedTab) {
            1 -> liveChannels
            2 -> vodMovies
            3 -> seriesList
            4 -> (liveChannels + vodMovies + seriesList).filter { favorites.contains(it.id) }
            else -> liveChannels
        }
        base.filter { item ->
            val matchCat = selectedCatId == "ALL" || item.categoryId == selectedCatId
            val matchSearch = searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
            matchCat && matchSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // BARRE SUPÉRIEURE AVEC STATUT ABONNEMENT
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEO", color = Color(0xFF38BDF8), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(" TV 4.0", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .tvFocusableClick(CircleShape, onClick = onRefreshCatalog)
                        .background(Color(0xFF151A24))
                        .padding(8.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualiser", tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                }

                Box(
                    modifier = Modifier
                        .tvFocusableClick(CircleShape, onClick = onOpenSettings)
                        .background(Color(0xFF8B5CF6))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (liveChannels.isEmpty()) "Connecter IPTV" else "Exp: ${accountInfo.expDateFormatted}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // MENU DES ONGLETS COMPATIBLE TÉLÉCOMMANDE TV
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(tabs) { index, tab ->
                val isSelected = selectedTab == index
                val countBadge = when (index) {
                    1 -> if (liveChannels.isNotEmpty()) " (${liveChannels.size})" else ""
                    2 -> if (vodMovies.isNotEmpty()) " (${vodMovies.size})" else ""
                    3 -> if (seriesList.isNotEmpty()) " (${seriesList.size})" else ""
                    4 -> if (favorites.isNotEmpty()) " (${favorites.size})" else ""
                    else -> ""
                }
                Box(
                    modifier = Modifier
                        .tvFocusableClick(CircleShape, onClick = { onChangeTab(index) })
                        .background(if (isSelected) Color(0xFF8B5CF6) else Color(0xFF151A24))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "$tab$countBadge",
                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(statusText, color = Color(0xFF38BDF8), fontSize = 12.sp)
            }
        }

        errorMsg?.let {
            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.padding(4.dp))
        }

        // BARRE DE RECHERCHE
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onChangeSearch,
            placeholder = { Text("Rechercher une chaîne, un film ou une série...", color = Color(0xFF64748B), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onChangeSearch("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Effacer", tint = Color.Gray)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // BARRE DES CATÉGORIES
        if (currentCategories.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                item {
                    CategoryChip("Tout (${currentList.size})", selectedCatId == "ALL") { onChangeCategory("ALL") }
                }
                items(currentCategories) { cat ->
                    CategoryChip(cat.name, selectedCatId == cat.id) { onChangeCategory(cat.id) }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                HomeProDashboard(
                    liveChannels = liveChannels.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    vodMovies = vodMovies.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    seriesList = seriesList.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    resumeItems = resumeItems,
                    prefs = prefs,
                    epgNowCache = epgNowCache,
                    onRequestEpg = onRequestEpg,
                    onSelectMedia = onSelectMedia,
                    onSelectTab = onChangeTab
                )
            }
            1 -> {
                LiveChannelsList(
                    listState = liveListState,
                    channels = currentList,
                    favorites = favorites,
                    epgNowCache = epgNowCache,
                    onToggleFav = onToggleFav,
                    onRequestEpg = onRequestEpg,
                    onOpenFullEpg = onOpenFullEpg,
                    onPlayMedia = onSelectMedia
                )
            }
            2, 3 -> {
                PosterGrid(
                    gridState = gridState,
                    items = currentList,
                    favorites = favorites,
                    onToggleFav = onToggleFav,
                    onPlayMedia = onSelectMedia
                )
            }
            4 -> {
                if (currentList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun favori. Appuyez sur ⭐ sur vos chaînes ou films préférés !", color = Color(0xFF94A3B8))
                    }
                } else {
                    LiveChannelsList(
                        listState = liveListState,
                        channels = currentList,
                        favorites = favorites,
                        epgNowCache = epgNowCache,
                        onToggleFav = onToggleFav,
                        onRequestEpg = onRequestEpg,
                        onOpenFullEpg = onOpenFullEpg,
                        onPlayMedia = onSelectMedia
                    )
                }
            }
        }
    }
}

@Composable
fun HomeProDashboard(
    liveChannels: List<MediaEntry>,
    vodMovies: List<MediaEntry>,
    seriesList: List<MediaEntry>,
    resumeItems: List<MediaEntry>,
    prefs: android.content.SharedPreferences,
    epgNowCache: Map<String, List<EpgProgram>>,
    onRequestEpg: (String) -> Unit,
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
                    .height(195.dp)
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
                                text = "À L'AFFICHE • ${liveChannels.size} CHAÎNES • ${vodMovies.size} FILMS",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = featured?.title ?: "NeoTV 4.0 Ultimate",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Replay TV • Contrôle du Direct • Audio/Sous-titres • Ratio Écran",
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = { if (featured != null) onSelectMedia(featured) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Regarder maintenant", color = Color.Black, fontWeight = FontWeight.Bold)
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

        if (liveChannels.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Direct TV & Replay", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Tout voir (${liveChannels.size}) >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(1) })
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(liveChannels.take(25)) { ch ->
                        LaunchedEffect(ch.id) { onRequestEpg(ch.id) }
                        val progs = epgNowCache[ch.id]
                        Column(
                            modifier = Modifier
                                .width(205.dp)
                                .tvFocusableClick(RoundedCornerShape(14.dp), onClick = { onSelectMedia(ch) })
                                .background(Color(0xFF151A24))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                AsyncImage(
                                    model = ch.logoUrl,
                                    contentDescription = ch.title,
                                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0E14)),
                                    contentScale = ContentScale.Fit
                                )
                                if (ch.tvArchive == 1) {
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF7C3AED)).padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("⏪ REPLAY", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(ch.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = progs?.firstOrNull()?.title ?: ch.categoryName,
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                    Text("Films à l'affiche (VOD)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Tout voir (${vodMovies.size}) >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(2) })
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
                    Text("Séries Complètes", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Tout voir (${seriesList.size}) >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(3) })
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

// --- LISTE DES CHAÎNES AVEC MULTI-HORAIRES EPG (En cours + Suivant) + BOUTON GRILLE/REPLAY ---
@Composable
fun LiveChannelsList(
    listState: LazyListState,
    channels: List<MediaEntry>,
    favorites: Set<String>,
    epgNowCache: Map<String, List<EpgProgram>>,
    onToggleFav: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
    onOpenFullEpg: (MediaEntry) -> Unit,
    onPlayMedia: (MediaEntry) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(channels, key = { it.id }) { ch ->
            LaunchedEffect(ch.id) {
                if (ch.type == "LIVE") onRequestEpg(ch.id)
            }
            val progs = epgNowCache[ch.id] ?: emptyList()
            val nowProg = progs.getOrNull(0)
            val nextProg = progs.getOrNull(1)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusableClick(RoundedCornerShape(12.dp), onClick = { onPlayMedia(ch) })
                    .background(Color(0xFF151A24))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ch.logoUrl,
                    contentDescription = ch.title,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0B0E14))
                        .padding(4.dp),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ch.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (ch.tvArchive == 1) {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF7C3AED)).padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text("REPLAY", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (nowProg != null) {
                        Text(
                            text = "▶ ${nowProg.startFormatted}-${nowProg.stopFormatted} : ${nowProg.title}",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(ch.categoryName, color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1)
                    }

                    if (nextProg != null) {
                        Text(
                            text = "Suivant (${nextProg.startFormatted}) : ${nextProg.title}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // BOUTON GUIDE MULTI-HEURES & REPLAY
                IconButton(onClick = { onOpenFullEpg(ch) }) {
                    Icon(Icons.Default.DateRange, contentDescription = "Grille EPG & Replay", tint = Color(0xFF38BDF8))
                }

                // BOUTON FAVORI
                IconButton(onClick = { onToggleFav(ch.id) }) {
                    val isFav = favorites.contains(ch.id)
                    Icon(
                        imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favori",
                        tint = if (isFav) Color(0xFFFACC15) else Color(0xFF64748B)
                    )
                }
            }
        }
    }
}

// --- ÉCRAN GUIDE EPG SUR PLUSIEURS HEURES + LANCEMENT REPLAY (CATCH-UP) ---
@Composable
fun FullEpgAndReplayScreen(
    channel: MediaEntry,
    programs: List<EpgProgram>,
    isLoading: Boolean,
    onPlayLive: () -> Unit,
    onPlayReplay: (EpgProgram) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val nowSec = System.currentTimeMillis() / 1000L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                }
                Column {
                    Text(channel.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (channel.tvArchive == 1) "Programmation EPG & Replay disponible (cliquez sur une émission passée)" else "Programmation EPG sur plusieurs heures",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp
                    )
                }
            }

            Button(
                onClick = onPlayLive,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Voir le Direct", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF38BDF8))
            }
        } else if (programs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun programme détaillé envoyé par le fournisseur pour cette chaîne.", color = Color(0xFF94A3B8))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(programs) { prog ->
                    val isPast = prog.stopTimestamp in 1 until nowSec
                    val isCurrent = prog.startTimestamp <= nowSec && prog.stopTimestamp >= nowSec
                    val canReplay = isPast && (channel.tvArchive == 1 || prog.hasArchive)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusableClick(
                                RoundedCornerShape(12.dp),
                                onClick = {
                                    if (isCurrent) onPlayLive()
                                    else if (canReplay) onPlayReplay(prog)
                                }
                            )
                            .background(if (isCurrent) Color(0xFF1E1B4B) else Color(0xFF151A24))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "${prog.startFormatted} - ${prog.stopFormatted}",
                                    color = if (isCurrent) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isCurrent) {
                                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFDC2626)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text("EN DIRECT", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else if (canReplay) {
                                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF7C3AED)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text("⏪ REVOIR EN REPLAY", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(prog.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            if (prog.description.isNotBlank()) {
                                Text(prog.description, color = Color(0xFFCBD5E1), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        if (isCurrent || canReplay) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Lire", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// --- LECTEUR VIDÉO 4.0 : AUTO-HIDE 4s + FORMAT ÉCRAN + AUDIO/SOUS-TITRES + TIMESHIFT DIRECT + EPG MULTI-HEURES ---
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

    // États d'affichage avec masquage automatique au bout de 4 secondes !
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }

    var showZappingDrawer by remember { mutableStateOf(false) }
    var showEpgDrawer by remember { mutableStateOf(false) }
    var showTracksDialog by remember { mutableStateOf(false) }

    // Format d'écran (Ratio) : 0 = Normal, 1 = Zoom Plein Écran, 2 = Étiré 16:9
    var resizeModeIndex by remember { mutableStateOf(0) }
    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Normal",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étiré 16:9"
    )

    // Décalage Timeshift en minutes pour reculer sur le Direct
    var liveTimeshiftMinutes by remember { mutableStateOf(0) }
    var activeUrl by remember(streamUrlToPlay) { mutableStateOf(streamUrlToPlay) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    // Masquer automatiquement la barre supérieure après 4 secondes si aucun tiroir n'est ouvert
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

    // Fonction pour reculer sur le direct (DVR / Timeshift)
    fun seekLiveOrTimeshift(deltaSeconds: Int) {
        wakeUpControls()
        if (exoPlayer.isCurrentMediaItemSeekable || media.type != "LIVE" || isReplayMode) {
            val target = (exoPlayer.currentPosition + deltaSeconds * 1000L).coerceAtLeast(0L)
            exoPlayer.seekTo(target)
        } else if (media.tvArchive == 1 && serverUrl.isNotBlank()) {
            // Si le flux live TS n'a pas de mémoire tampon locale, on utilise le serveur Timeshift Xtream !
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

        // BARRE SUPÉRIEURE QUI DISPARAÎT AUTOMATIQUEMENT AU BOUT DE 4 SECONDES !
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text = media.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isReplayMode || liveTimeshiftMinutes < 0) {
                                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF7C3AED)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text(if (liveTimeshiftMinutes < 0) "TIMESHIFT ${liveTimeshiftMinutes}m" else "REPLAY", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            val currentEpg = epgList.firstOrNull()
                            if (currentEpg != null) {
                                Text(
                                    text = "En cours (${currentEpg.startFormatted}-${currentEpg.stopFormatted}) : ${currentEpg.title}",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // BOUTONS D'ACTION PRO : FORMAT ÉCRAN, AUDIO/SOUS-TITRES, EPG/REPLAY, ZAPPING
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // 1. Bouton Format d'écran (Ratio)
                        SmallControlChip(
                            label = "📐 ${resizeModes[resizeModeIndex].second}",
                            onClick = {
                                wakeUpControls()
                                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                            }
                        )

                        // 2. Bouton Audio & Sous-titres
                        SmallControlChip(
                            label = "💬 Audio / ST",
                            onClick = {
                                wakeUpControls()
                                showTracksDialog = true
                            }
                        )

                        // 3. Bouton EPG Multi-heures & Replay (Sur les chaînes Live)
                        if (media.type == "LIVE") {
                            SmallControlChip(
                                label = "📅 EPG & Replay",
                                isActive = showEpgDrawer,
                                onClick = {
                                    wakeUpControls()
                                    showZappingDrawer = false
                                    showEpgDrawer = !showEpgDrawer
                                }
                            )
                        }

                        // 4. Bouton Tiroir Zapping TV
                        if (liveChannelList.isNotEmpty()) {
                            SmallControlChip(
                                label = if (showZappingDrawer) "Fermer" else "📺 Zapping",
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

                // BARRE DE CONTRÔLE DU DIRECT (Retour en arrière sur le Direct / Timeshift)
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
                                label = "🔴 Revenir au Direct",
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

        // TIROIR DE ZAPPING EN SURIMPRESSION (Côté gauche)
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
                            Text(
                                text = ch.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // TIROIR EPG MULTI-HEURES & REPLAY EN SURIMPRESSION (Côté droit)
        if (showEpgDrawer) {
            val nowSec = System.currentTimeMillis() / 1000L
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
                        Text("Programmation & Replay", color = Color(0xFF38BDF8), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    if (epgList.isEmpty()) {
                        item {
                            Text("Aucun guide détaillé disponible pour cette chaîne.", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                    } else {
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
        }

        // FENÊTRE DE SÉLECTION DES PISTES AUDIO & SOUS-TITRES
        if (showTracksDialog) {
            AudioAndSubtitlesDialog(
                exoPlayer = exoPlayer,
                onDismiss = { showTracksDialog = false }
            )
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

// --- SÉLECTEUR DE LANGUES AUDIO & SOUS-TITRES INTÉGRÉ ---
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
                item {
                    Text("🔊 Langues Audio (${audioOptions.size})", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                if (audioOptions.isEmpty()) {
                    item { Text("Piste audio par défaut uniquement", color = Color(0xFF94A3B8), fontSize = 12.sp) }
                } else {
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
            TextButton(onClick = onDismiss) {
                Text("Fermer", color = Color(0xFF38BDF8))
            }
        }
    )
}

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
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                }
                IconButton(onClick = onToggleFav) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favori",
                        tint = if (isFavorite) Color(0xFFFACC15) else Color.White
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AsyncImage(
                    model = movie.logoUrl,
                    contentDescription = movie.title,
                    modifier = Modifier
                        .width(130.dp)
                        .height(195.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF151A24)),
                    contentScale = ContentScale.Crop
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(movie.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text(details?.genre?.ifBlank { movie.categoryName } ?: movie.categoryName, color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    if (!details?.rating.isNullOrBlank()) {
                        Text("⭐ Note : ${details?.rating}/10", color = Color(0xFFFACC15), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (!details?.duration.isNullOrBlank()) {
                        Text("⏱ Durée : ${details?.duration}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    if (!details?.releaseDate.isNullOrBlank()) {
                        Text("📅 Sortie : ${details?.releaseDate}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPlayFromStart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (savedPositionMs > 10000L) "Depuis le début" else "Regarder le film", color = Color.White, fontWeight = FontWeight.Bold)
                }

                if (savedPositionMs > 10000L) {
                    val min = (savedPositionMs / 60000).toInt()
                    Button(
                        onClick = onResumePlay,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reprendre (${min} min)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Chargement du synopsis et du casting...", color = Color(0xFF94A3B8), fontSize = 13.sp)
                }
            }
        } else if (details != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF151A24))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Synopsis / Résumé", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = details.plot.ifBlank { "Aucun résumé fourni par le serveur Xtream pour ce film." },
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp
                    )
                    if (details.cast.isNotBlank()) {
                        Text("Acteurs : ${details.cast}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    if (details.director.isNotBlank()) {
                        Text("Réalisateur : ${details.director}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        }
    }
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
fun SeriesDetailScreen(
    series: MediaEntry,
    episodes: List<EpisodeItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlayEpisode: (EpisodeItem) -> Unit
) {
    BackHandler { onBack() }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }

    LaunchedEffect(seasons) {
        if (selectedSeason == null && seasons.isNotEmpty()) {
            selectedSeason = seasons.first()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
            }
            Text(series.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (seasons.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { s ->
                    CategoryChip("Saison $s", selectedSeason == s) { selectedSeason = s }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
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
                            .tvFocusableClick(RoundedCornerShape(10.dp), onClick = { onPlayEpisode(ep) })
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

// --- ÉCRAN DE CONFIGURATION XTREAM CODES AVEC DATE D'EXPIRATION & STATUT ---
@Composable
fun IptvSetupScreen(
    initialServer: String,
    initialUser: String,
    initialPass: String,
    accountInfo: AccountInfo,
    onSave: (String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler { onCancel() }
    var server by remember { mutableStateOf(initialServer) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Paramètres Xtream Codes & Abonnement", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        // CARTE D'INFORMATIONS D'ABONNEMENT (Expiration, Statut, Connexions)
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

// --- MOTEUR RÉSEAU XTREAM CODES (ABONNEMENT, REPLAY, EPG MULTI-HEURES, CACHE) ---

suspend fun fetchAccountInfo(baseUrl: String, user: String, pass: String): AccountInfo = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass")
        val userInfo = JSONObject(json).optJSONObject("user_info") ?: return@withContext AccountInfo()
        val status = userInfo.optString("status", "Actif")
        val expSec = userInfo.optString("exp_date", "").toLongOrNull()
        val expFormatted = if (expSec != null && expSec > 0) {
            SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRANCE).format(Date(expSec * 1000L))
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

fun fetchWithDiskCache(context: Context, cacheKey: String, url: String, forceRefresh: Boolean): String {
    val file = File(context.cacheDir, cacheKey)
    if (!forceRefresh && file.exists() && (System.currentTimeMillis() - file.lastModified() < 6 * 3600 * 1000L)) {
        return file.readText()
    }
    val fresh = httpGet(url)
    if (fresh.startsWith("[") || fresh.startsWith("{")) {
        file.writeText(fresh)
    }
    return fresh
}

suspend fun fetchXtreamLive(
    context: Context,
    baseUrl: String,
    user: String,
    pass: String,
    forceRefresh: Boolean
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catJson = fetchWithDiskCache(context, "live_cats.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_categories", forceRefresh)
    val catMap = parseCategoryMap(catJson)

    val streamJson = fetchWithDiskCache(context, "live_streams.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_streams", forceRefresh)
    val arr = JSONArray(streamJson)
    val list = ArrayList<MediaEntry>(arr.length())

    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("stream_id")
        val name = obj.optString("name", "Chaîne $id")
        val logo = obj.optString("stream_icon", "")
        val catId = obj.optString("category_id")
        val tvArchive = obj.optInt("tv_archive", 0)
        val tvArchiveDur = obj.optInt("tv_archive_duration", 0)
        val catName = catMap[catId] ?: "Direct TV"
        val url = "$cleanBase/live/$user/$pass/$id.ts"
        list.add(MediaEntry(id, name, catId, catName, logo, url, "LIVE", tvArchive, tvArchiveDur))
    }
    Pair(catMap.map { CategoryItem(it.key, it.value) }, list)
}

suspend fun fetchXtreamVod(
    context: Context,
    baseUrl: String,
    user: String,
    pass: String,
    forceRefresh: Boolean
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catJson = fetchWithDiskCache(context, "vod_cats.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_vod_categories", forceRefresh)
    val catMap = parseCategoryMap(catJson)

    val vodJson = fetchWithDiskCache(context, "vod_streams.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_vod_streams", forceRefresh)
    val arr = JSONArray(vodJson)
    val list = ArrayList<MediaEntry>(arr.length())

    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("stream_id")
        val name = obj.optString("name", "Film $id")
        val logo = obj.optString("stream_icon", "")
        val catId = obj.optString("category_id")
        val ext = obj.optString("container_extension", "mp4").ifBlank { "mp4" }
        val catName = catMap[catId] ?: "Cinéma"
        val url = "$cleanBase/movie/$user/$pass/$id.$ext"
        list.add(MediaEntry("vod_$id", name, catId, catName, logo, url, "VOD"))
    }
    Pair(catMap.map { CategoryItem(it.key, it.value) }, list)
}

suspend fun fetchXtreamSeries(
    context: Context,
    baseUrl: String,
    user: String,
    pass: String,
    forceRefresh: Boolean
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catJson = fetchWithDiskCache(context, "series_cats.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_series_categories", forceRefresh)
    val catMap = parseCategoryMap(catJson)

    val serJson = fetchWithDiskCache(context, "series_list.json", "$cleanBase/player_api.php?username=$user&password=$pass&action=get_series", forceRefresh)
    val arr = JSONArray(serJson)
    val list = ArrayList<MediaEntry>(arr.length())

    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("series_id")
        val name = obj.optString("name", "Série $id")
        val cover = obj.optString("cover", "")
        val catId = obj.optString("category_id")
        val catName = catMap[catId] ?: "Série"
        list.add(MediaEntry(id, name, catId, catName, cover, "", "SERIES"))
    }
    Pair(catMap.map { CategoryItem(it.key, it.value) }, list)
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
            rating = info.optString("rating", "")
        )
    } catch (e: Exception) {
        VodDetails("", "", "", "", "", "", "")
    }
}

suspend fun fetchSeriesEpisodes(baseUrl: String, user: String, pass: String, seriesId: String): List<EpisodeItem> = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId")
        val root = JSONObject(json)
        val episodesObj = root.optJSONObject("episodes") ?: return@withContext emptyList()
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
        result
    } catch (e: Exception) {
        emptyList()
    }
}

suspend fun fetchChannelEpgList(
    baseUrl: String,
    user: String,
    pass: String,
    streamId: String,
    limit: Int = 10
): List<EpgProgram> = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val action = if (limit > 10) "get_simple_data_table" else "get_short_epg"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=$action&stream_id=$streamId&limit=$limit")
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("epg_listings") ?: return@withContext emptyList()
        val list = mutableListOf<EpgProgram>()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val title = decodeBase64IfPossible(item.optString("title", ""))
            val desc = decodeBase64IfPossible(item.optString("description", ""))
            val startTs = item.optString("start_timestamp", "0").toLongOrNull() ?: 0L
            val stopTs = item.optString("stop_timestamp", "0").toLongOrNull() ?: 0L
            val hasArchive = item.optInt("has_archive", 0) == 1

            val startStr = if (startTs > 0) timeFormat.format(Date(startTs * 1000L)) else ""
            val stopStr = if (stopTs > 0) timeFormat.format(Date(stopTs * 1000L)) else ""

            if (title.isNotBlank()) {
                list.add(EpgProgram(title, desc, startTs, stopTs, startStr, stopStr, hasArchive))
            }
        }
        list
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

fun parseCategoryMap(jsonStr: String): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    return try {
        val arr = JSONArray(jsonStr)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            map[obj.optString("category_id")] = obj.optString("category_name", "Général")
        }
        map
    } catch (e: Exception) {
        map
    }
}

fun httpGet(urlStr: String): String {
    val url = URL(urlStr)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 35000
        setRequestProperty("User-Agent", "NeoTVUltimate/4.0 (ExoPlayer)")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}
