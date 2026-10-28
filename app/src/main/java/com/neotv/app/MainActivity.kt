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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF151A24),
                    primary = Color(0xFF8B5CF6)
                )
            ) {
                NeoTvProApp()
            }
        }
    }
}

data class MediaEntry(
    val id: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val logoUrl: String,
    val streamUrl: String,
    val type: String, // "LIVE", "VOD", "SERIES"
    val rating: String = ""
)

data class CategoryItem(
    val id: String,
    val name: String
)

data class VodDetails(
    val plot: String,
    val director: String,
    val cast: String,
    val genre: String,
    val releaseDate: String,
    val duration: String,
    val rating: String,
    val backdropUrl: String
)

data class EpisodeItem(
    val id: String,
    val title: String,
    val season: Int,
    val streamUrl: String
)

@Composable
fun NeoTvProApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neotv_pro_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }

    var liveChannels by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var liveCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }

    var vodMovies by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var vodCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }

    var seriesList by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var seriesCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }

    var favorites by remember {
        mutableStateOf(prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet())
    }

    // Historique & Reprise de lecture (ID -> Position en millisecondes)
    var watchHistoryIds by remember {
        mutableStateOf(prefs.getStringSet("history_ids", emptySet())?.toList() ?: emptyList())
    }

    var epgCache by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var showSettings by remember { mutableStateOf(false) }

    // États de navigation avancés
    var playingMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var startPositionMs by remember { mutableStateOf(0L) }

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
        if (serverUrl.isBlank() || epgCache.containsKey(streamId)) return
        scope.launch {
            val epgTitle = fetchShortEpg(serverUrl, username, password, streamId)
            if (epgTitle.isNotBlank()) {
                epgCache = epgCache + (streamId to epgTitle)
            }
        }
    }

    fun loadAllXtreamData(forceRefresh: Boolean = false) {
        if (serverUrl.isBlank() || username.isBlank()) return
        isLoading = true
        errorMsg = null
        scope.launch {
            try {
                statusText = "Synchronisation Direct TV & Logos HD..."
                val liveRes = fetchXtreamLive(context, serverUrl, username, password, forceRefresh)
                liveCategories = liveRes.first
                liveChannels = liveRes.second

                statusText = "Synchronisation Catalogue Films (VOD)..."
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

    when {
        playingMedia != null -> {
            val currentMedia = playingMedia!!
            val currentEpg = epgCache[currentMedia.id] ?: ""
            ProVideoPlayerScreen(
                media = currentMedia,
                epgSubtitle = currentEpg,
                startPositionMs = startPositionMs,
                liveChannelList = if (currentMedia.type == "LIVE") liveChannels else emptyList(),
                onSwitchChannel = { nextChannel ->
                    loadEpgForChannel(nextChannel.id)
                    startPositionMs = 0L
                    playingMedia = nextChannel
                },
                onSaveProgress = { pos, dur ->
                    if (currentMedia.type != "LIVE") {
                        saveWatchProgress(currentMedia.id, pos, dur)
                    }
                },
                onBack = { playingMedia = null }
            )
        }
        selectedMovieForDetails != null -> {
            val movie = selectedMovieForDetails!!
            val savedPos = prefs.getLong("pos_${movie.id}", 0L)
            val savedDur = prefs.getLong("dur_${movie.id}", 1L)
            MovieDetailScreen(
                movie = movie,
                details = movieDetails,
                isLoading = isMovieDetailsLoading,
                savedPositionMs = savedPos,
                savedDurationMs = savedDur,
                isFavorite = favorites.contains(movie.id),
                onToggleFav = { toggleFavorite(movie.id) },
                onPlayFromStart = {
                    startPositionMs = 0L
                    playingMedia = movie
                },
                onResumePlay = {
                    startPositionMs = savedPos
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
            val resumeItems = remember(watchHistoryIds, vodMovies, liveChannels) {
                val allMedia = (vodMovies + liveChannels).associateBy { it.id }
                watchHistoryIds.mapNotNull { allMedia[it] }
            }

            MainNeoProScreen(
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
    liveChannels: List<MediaEntry>,
    liveCategories: List<CategoryItem>,
    vodMovies: List<MediaEntry>,
    vodCategories: List<CategoryItem>,
    seriesList: List<MediaEntry>,
    seriesCategories: List<CategoryItem>,
    resumeItems: List<MediaEntry>,
    prefs: android.content.SharedPreferences,
    favorites: Set<String>,
    epgCache: Map<String, String>,
    isLoading: Boolean,
    statusText: String,
    errorMsg: String?,
    onToggleFav: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
    onRefreshCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectMedia: (MediaEntry) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Accueil", "Direct TV", "Films", "Séries", "Favoris")

    var selectedCatId by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(selectedTab) {
        selectedCatId = "ALL"
        searchQuery = ""
    }

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
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // BARRE DU HAUT
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEO", color = Color(0xFF38BDF8), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(" TV PRO", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onRefreshCatalog,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF151A24))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualiser", tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                }

                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (liveChannels.isEmpty()) "Connecter IPTV" else "Compte Xtream",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ONGLETS DE NAVIGATION
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
                        .clip(CircleShape)
                        .background(if (isSelected) Color(0xFF8B5CF6) else Color(0xFF151A24))
                        .clickable { selectedTab = index }
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

        Spacer(modifier = Modifier.height(10.dp))

        if (isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
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
            onValueChange = { searchQuery = it },
            placeholder = { Text("Rechercher une chaîne, un film ou une série...", color = Color(0xFF64748B), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Effacer", tint = Color.Gray)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (currentCategories.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                item {
                    CategoryChip("Tout (${currentList.size})", selectedCatId == "ALL") { selectedCatId = "ALL" }
                }
                items(currentCategories) { cat ->
                    CategoryChip(cat.name, selectedCatId == cat.id) { selectedCatId = cat.id }
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
                    epgCache = epgCache,
                    onRequestEpg = onRequestEpg,
                    onSelectMedia = onSelectMedia,
                    onSelectTab = { selectedTab = it }
                )
            }
            1 -> {
                LiveChannelsList(
                    channels = currentList,
                    favorites = favorites,
                    epgCache = epgCache,
                    onToggleFav = onToggleFav,
                    onRequestEpg = onRequestEpg,
                    onPlayMedia = onSelectMedia
                )
            }
            2, 3 -> {
                PosterGrid(
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
                        channels = currentList,
                        favorites = favorites,
                        epgCache = epgCache,
                        onToggleFav = onToggleFav,
                        onRequestEpg = onRequestEpg,
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
    epgCache: Map<String, String>,
    onRequestEpg: (String) -> Unit,
    onSelectMedia: (MediaEntry) -> Unit,
    onSelectTab: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // BANNIÈRE "À L'AFFICHE" AVEC IMAGE DE FOND
        item {
            val featured = vodMovies.firstOrNull() ?: liveChannels.firstOrNull()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF151A24))
                    .border(1.dp, Color(0xFF6D28D9), RoundedCornerShape(18.dp))
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
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "À L'AFFICHE • ${liveChannels.size} CHAÎNES • ${vodMovies.size} FILMS",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = featured?.title ?: "NeoTV Pro Edition",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Zapping en plein écran • Fiches Cinéma • Reprise de lecture",
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
                            Text("Regarder", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // SECTION "REPRENDRE LA LECTURE" (Barre de progression violette)
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
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF151A24))
                                .border(1.dp, Color(0xFFA855F7), RoundedCornerShape(14.dp))
                                .clickable { onSelectMedia(item) }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = item.logoUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${(progress * 100).toInt()}% visionné", color = Color(0xFF38BDF8), fontSize = 11.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
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

        // CARROUSEL DIRECT TV AVEC BARRE DE PROGRESSION NÉON
        if (liveChannels.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("En Direct • Sélection TV", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Tout voir (${liveChannels.size}) >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(1) })
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(liveChannels.take(25)) { index, ch ->
                        LaunchedEffect(ch.id) { onRequestEpg(ch.id) }
                        val isFirst = index == 0
                        Column(
                            modifier = Modifier
                                .width(200.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF151A24))
                                .border(
                                    width = if (isFirst) 2.dp else 1.dp,
                                    color = if (isFirst) Color(0xFFA855F7) else Color(0xFF262F40),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectMedia(ch) }
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = ch.logoUrl,
                                    contentDescription = ch.title,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0B0E14)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFFDC2626))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("DIRECT", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(ch.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = epgCache[ch.id] ?: ch.categoryName,
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

        // CARROUSEL FILMS (VOD)
        if (vodMovies.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Films Récents (VOD)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
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

        // CARROUSEL SÉRIES
        if (seriesList.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Séries à découvrir", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
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

// --- FICHE DÉTAIL FILM "STYLE MYCANAL / NETFLIX" ---
@Composable
fun MovieDetailScreen(
    movie: MediaEntry,
    details: VodDetails?,
    isLoading: Boolean,
    savedPositionMs: Long,
    savedDurationMs: Long,
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
                        Spacer(modifier = Modifier.height(4.dp))
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

// --- LECTEUR VIDÉO PRO AVEC TIROIR DE ZAPPING DIRECT TV ---
@Composable
fun ProVideoPlayerScreen(
    media: MediaEntry,
    epgSubtitle: String,
    startPositionMs: Long,
    liveChannelList: List<MediaEntry>,
    onSwitchChannel: (MediaEntry) -> Unit,
    onSaveProgress: (Long, Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showZappingDrawer by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(media.streamUrl) {
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(media.streamUrl)))
        exoPlayer.prepare()
        if (startPositionMs > 0L && media.type != "LIVE") {
            exoPlayer.seekTo(startPositionMs)
        }
        exoPlayer.playWhenReady = true
    }

    // Sauvegarde périodique de la progression toutes les 5 secondes
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
        if (showZappingDrawer) showZappingDrawer = false else onBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // BARRE SUPÉRIEURE DU LECTEUR + BOUTON ZAPPING RAPIDE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                }
                Column {
                    Text(text = media.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (epgSubtitle.isNotBlank()) {
                        Text(text = "En cours : $epgSubtitle", color = Color(0xFF38BDF8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (liveChannelList.isNotEmpty()) {
                Button(
                    onClick = { showZappingDrawer = !showZappingDrawer },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.List, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (showZappingDrawer) "Fermer" else "Zapping TV", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // TIROIR DE ZAPPING EN SURIMPRESSION (Façon TiviMate)
        if (showZappingDrawer && liveChannelList.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(270.dp)
                    .padding(top = 64.dp)
                    .background(Color(0xE60B0E14))
                    .border(1.dp, Color(0xFF262F40))
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(liveChannelList) { ch ->
                        val isCurrent = ch.id == media.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) Color(0xFF8B5CF6) else Color(0xFF151A24))
                                .clickable { onSwitchChannel(ch) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.title,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black),
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
    }
}

@Composable
fun PosterCard(item: MediaEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(125.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF151A24))
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = item.logoUrl,
            contentDescription = item.title,
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
                .background(Color(0xFF1E293B)),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.padding(8.dp)) {
            Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.categoryName, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun LiveChannelsList(
    channels: List<MediaEntry>,
    favorites: Set<String>,
    epgCache: Map<String, String>,
    onToggleFav: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
    onPlayMedia: (MediaEntry) -> Unit
) {
    // AUCUNE LIMITE (.take supprimé) : Affiche 100% de vos chaînes de manière fluide
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(channels, key = { it.id }) { ch ->
            LaunchedEffect(ch.id) {
                if (ch.type == "LIVE") onRequestEpg(ch.id)
            }
            val epgNow = epgCache[ch.id]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF151A24))
                    .clickable { onPlayMedia(ch) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ch.logoUrl,
                    contentDescription = ch.title,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0B0E14))
                        .padding(4.dp),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(ch.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!epgNow.isNullOrBlank()) {
                        Text("En direct : $epgNow", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(ch.categoryName, color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1)
                    }
                }

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

@Composable
fun PosterGrid(
    items: List<MediaEntry>,
    favorites: Set<String>,
    onToggleFav: (String) -> Unit,
    onPlayMedia: (MediaEntry) -> Unit
) {
    // AUCUNE LIMITE (.take supprimé) : Affiche 100% de vos films et séries
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.id }) { item ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF151A24))
                    .clickable { onPlayMedia(item) }
            ) {
                Column {
                    AsyncImage(
                        model = item.logoUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(Color(0xFF1E293B)),
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF151A24))
                            .clickable { onPlayEpisode(ep) }
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
            .clip(CircleShape)
            .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF151A24))
            .clickable { onClick() }
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
    onSave: (String, String, String) -> Unit,
    onCancel: () -> Unit
) {
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
            Text("Configuration Xtream Codes Pro", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Synchronise 100% des Chaînes, Logos, EPG, Films, Synopsis et Séries", color = Color(0xFF38BDF8), fontSize = 13.sp)
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
                    Text("Synchroniser Tout", color = Color.White, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Annuler", color = Color.White)
                }
            }
        }
    }
}

// --- MOTEUR RÉSEAU XTREAM AVEC CACHE DISQUE RAPIDE ---

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
        val catName = catMap[catId] ?: "Direct TV"
        val url = "$cleanBase/live/$user/$pass/$id.ts"
        list.add(MediaEntry(id, name, catId, catName, logo, url, "LIVE"))
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

suspend fun fetchVodInfo(
    baseUrl: String,
    user: String,
    pass: String,
    vodId: String
): VodDetails = withContext(Dispatchers.IO) {
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
            backdropUrl = info.optString("movie_image", "")
        )
    } catch (e: Exception) {
        VodDetails("", "", "", "", "", "", "", "")
    }
}

suspend fun fetchSeriesEpisodes(
    baseUrl: String,
    user: String,
    pass: String,
    seriesId: String
): List<EpisodeItem> = withContext(Dispatchers.IO) {
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

suspend fun fetchShortEpg(
    baseUrl: String,
    user: String,
    pass: String,
    streamId: String
): String = withContext(Dispatchers.IO) {
    try {
        val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_short_epg&stream_id=$streamId&limit=1")
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("epg_listings")
        if (arr != null && arr.length() > 0) {
            val rawTitle = arr.getJSONObject(0).optString("title", "")
            decodeBase64IfPossible(rawTitle)
        } else ""
    } catch (e: Exception) {
        ""
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
        setRequestProperty("User-Agent", "NeoTVPro/3.0 (ExoPlayer)")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}
