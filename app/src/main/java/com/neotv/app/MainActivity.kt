package com.neotv.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
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
                NeoTvApp()
            }
        }
    }
}

data class MediaEntry(
    val id: String,
    val rawStreamId: String,
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

data class EpisodeItem(
    val id: String,
    val title: String,
    val season: Int,
    val streamUrl: String
)

data class VodDetailedInfo(
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val duration: String,
    val rating: String,
    val backdropUrl: String
)

data class WatchHistoryEntry(
    val id: String,
    val title: String,
    val logoUrl: String,
    val streamUrl: String,
    val type: String,
    val positionMs: Long,
    val durationMs: Long
)

@Composable
fun NeoTvApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neotv_prefs", Context.MODE_PRIVATE) }
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

    var watchHistory by remember {
        mutableStateOf(loadWatchHistory(prefs))
    }

    var epgCache by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var showSettings by remember { mutableStateOf(false) }

    // État du lecteur vidéo (Supporte le Zapping Direct & la Reprise de lecture)
    var activeMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var activeStartPositionMs by remember { mutableStateOf(0L) }
    var zappingPlaylist by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }

    // État de la fiche Film (Style Netflix / myCANAL)
    var selectedVod by remember { mutableStateOf<MediaEntry?>(null) }
    var vodDetails by remember { mutableStateOf<VodDetailedInfo?>(null) }
    var isVodDetailLoading by remember { mutableStateOf(false) }

    // État de la fiche Série
    var selectedSeries by remember { mutableStateOf<MediaEntry?>(null) }
    var seriesEpisodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var isSeriesLoading by remember { mutableStateOf(false) }

    fun toggleFavorite(id: String) {
        val next = favorites.toMutableSet()
        if (next.contains(id)) next.remove(id) else next.add(id)
        favorites = next
        prefs.edit().putStringSet("favs", next).apply()
    }

    fun saveProgress(media: MediaEntry, posMs: Long, durMs: Long) {
        if (media.type == "LIVE" || durMs <= 0L) return
        val updated = saveWatchHistoryEntry(
            prefs,
            WatchHistoryEntry(media.id, media.title, media.logoUrl, media.streamUrl, media.type, posMs, durMs)
        )
        watchHistory = updated
    }

    fun loadEpgForChannel(rawStreamId: String) {
        if (serverUrl.isBlank() || epgCache.containsKey(rawStreamId)) return
        scope.launch {
            val epgTitle = fetchShortEpg(serverUrl, username, password, rawStreamId)
            if (epgTitle.isNotBlank()) {
                epgCache = epgCache + (rawStreamId to epgTitle)
            }
        }
    }

    fun loadAllXtreamData() {
        if (serverUrl.isBlank() || username.isBlank()) return
        isLoading = true
        errorMsg = null
        scope.launch {
            try {
                statusText = "Chargement des chaînes TV & Logos..."
                val liveRes = fetchXtreamLive(serverUrl, username, password)
                liveCategories = liveRes.first
                liveChannels = liveRes.second

                statusText = "Chargement des Films (VOD)..."
                val vodRes = fetchXtreamVod(serverUrl, username, password)
                vodCategories = vodRes.first
                vodMovies = vodRes.second

                statusText = "Chargement des Séries..."
                val serRes = fetchXtreamSeries(serverUrl, username, password)
                seriesCategories = serRes.first
                seriesList = serRes.second
            } catch (e: Exception) {
                errorMsg = "Erreur : vérifiez vos identifiants (${e.localizedMessage})"
            } finally {
                isLoading = false
                statusText = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        loadAllXtreamData()
    }

    when {
        activeMedia != null -> {
            val current = activeMedia!!
            val currentEpg = if (current.type == "LIVE") epgCache[current.rawStreamId] ?: "" else ""
            VideoPlayerScreen(
                currentMedia = current,
                epgSubtitle = currentEpg,
                startPositionMs = activeStartPositionMs,
                liveZappingList = if (current.type == "LIVE") zappingPlaylist else emptyList(),
                epgCache = epgCache,
                onRequestEpg = { loadEpgForChannel(it) },
                onSwitchChannel = { newChannel ->
                    // Zapping sécurisé : remplace le flux unique sans créer de double connexion
                    loadEpgForChannel(newChannel.rawStreamId)
                    activeStartPositionMs = 0L
                    activeMedia = newChannel
                },
                onSaveProgress = { pos, dur -> saveProgress(current, pos, dur) },
                onBack = { activeMedia = null }
            )
        }
        selectedVod != null -> {
            val movie = selectedVod!!
            val savedEntry = watchHistory.find { it.id == movie.id }
            VodDetailScreen(
                movie = movie,
                details = vodDetails,
                isLoading = isVodDetailLoading,
                savedPositionMs = savedEntry?.positionMs ?: 0L,
                savedDurationMs = savedEntry?.durationMs ?: 0L,
                isFavorite = favorites.contains(movie.id),
                onToggleFav = { toggleFavorite(movie.id) },
                onPlayFromStart = {
                    activeStartPositionMs = 0L
                    activeMedia = movie
                },
                onResumePlay = { resumePos ->
                    activeStartPositionMs = resumePos
                    activeMedia = movie
                },
                onBack = { selectedVod = null }
            )
        }
        selectedSeries != null -> {
            SeriesDetailScreen(
                series = selectedSeries!!,
                episodes = seriesEpisodes,
                watchHistory = watchHistory,
                isLoading = isSeriesLoading,
                onBack = { selectedSeries = null },
                onPlayEpisode = { ep, resumeMs ->
                    activeStartPositionMs = resumeMs
                    activeMedia = MediaEntry(
                        id = "ep_${ep.id}",
                        rawStreamId = ep.id,
                        title = "${selectedSeries!!.title} - S${ep.season} ${ep.title}",
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
                    loadAllXtreamData()
                },
                onCancel = { showSettings = false }
            )
        }
        else -> {
            MainNeoScreen(
                liveChannels = liveChannels,
                liveCategories = liveCategories,
                vodMovies = vodMovies,
                vodCategories = vodCategories,
                seriesList = seriesList,
                seriesCategories = seriesCategories,
                favorites = favorites,
                watchHistory = watchHistory,
                epgCache = epgCache,
                isLoading = isLoading,
                statusText = statusText,
                errorMsg = errorMsg,
                onToggleFav = { toggleFavorite(it) },
                onRequestEpg = { loadEpgForChannel(it) },
                onOpenSettings = { showSettings = true },
                onResumeHistoryItem = { hist ->
                    activeStartPositionMs = hist.positionMs
                    activeMedia = MediaEntry(
                        id = hist.id,
                        rawStreamId = hist.id,
                        title = hist.title,
                        categoryId = "",
                        categoryName = "Reprise",
                        logoUrl = hist.logoUrl,
                        streamUrl = hist.streamUrl,
                        type = hist.type
                    )
                },
                onSelectMedia = { entry, currentListContext ->
                    when (entry.type) {
                        "SERIES" -> {
                            selectedSeries = entry
                            isSeriesLoading = true
                            scope.launch {
                                seriesEpisodes = fetchSeriesEpisodes(serverUrl, username, password, entry.rawStreamId)
                                isSeriesLoading = false
                            }
                        }
                        "VOD" -> {
                            // Ouvre la belle fiche Cinéma façon myCANAL / Netflix
                            selectedVod = entry
                            vodDetails = null
                            isVodDetailLoading = true
                            scope.launch {
                                vodDetails = fetchVodInfo(serverUrl, username, password, entry.rawStreamId)
                                isVodDetailLoading = false
                            }
                        }
                        else -> {
                            // Chaîne TV en direct : prépare la liste de zapping
                            loadEpgForChannel(entry.rawStreamId)
                            zappingPlaylist = currentListContext.ifEmpty { liveChannels }
                            activeStartPositionMs = 0L
                            activeMedia = entry
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun MainNeoScreen(
    liveChannels: List<MediaEntry>,
    liveCategories: List<CategoryItem>,
    vodMovies: List<MediaEntry>,
    vodCategories: List<CategoryItem>,
    seriesList: List<MediaEntry>,
    seriesCategories: List<CategoryItem>,
    favorites: Set<String>,
    watchHistory: List<WatchHistoryEntry>,
    epgCache: Map<String, String>,
    isLoading: Boolean,
    statusText: String,
    errorMsg: String?,
    onToggleFav: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onResumeHistoryItem: (WatchHistoryEntry) -> Unit,
    onSelectMedia: (MediaEntry, List<MediaEntry>) -> Unit
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
            0 -> liveChannels
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEO", color = Color(0xFF38BDF8), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(" TV", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light)
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
                    text = if (liveChannels.isEmpty()) "Connecter IPTV" else "Xtream Connecté",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(statusText, color = Color(0xFF38BDF8), fontSize = 13.sp)
            }
        }

        errorMsg?.let {
            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.padding(4.dp))
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Rechercher dans ${tabs[selectedTab]}...", color = Color(0xFF64748B), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8)) },
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
                    CategoryChip("Tout voir", selectedCatId == "ALL") { selectedCatId = "ALL" }
                }
                items(currentCategories) { cat ->
                    CategoryChip(cat.name, selectedCatId == cat.id) { selectedCatId = cat.id }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                HomeDashboard(
                    liveChannels = liveChannels.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    vodMovies = vodMovies.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    seriesList = seriesList.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    watchHistory = watchHistory,
                    epgCache = epgCache,
                    onRequestEpg = onRequestEpg,
                    onResumeHistoryItem = onResumeHistoryItem,
                    onSelectMedia = { item -> onSelectMedia(item, liveChannels) },
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
                    onPlayMedia = { item -> onSelectMedia(item, currentList) }
                )
            }
            2, 3 -> {
                PosterGrid(
                    items = currentList,
                    favorites = favorites,
                    onToggleFav = onToggleFav,
                    onPlayMedia = { item -> onSelectMedia(item, currentList) }
                )
            }
            4 -> {
                if (currentList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun favori. Appuyez sur ⭐ sur une chaîne ou un film !", color = Color(0xFF94A3B8))
                    }
                } else {
                    LiveChannelsList(
                        channels = currentList,
                        favorites = favorites,
                        epgCache = epgCache,
                        onToggleFav = onToggleFav,
                        onRequestEpg = onRequestEpg,
                        onPlayMedia = { item -> onSelectMedia(item, currentList) }
                    )
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
    watchHistory: List<WatchHistoryEntry>,
    epgCache: Map<String, String>,
    onRequestEpg: (String) -> Unit,
    onResumeHistoryItem: (WatchHistoryEntry) -> Unit,
    onSelectMedia: (MediaEntry) -> Unit,
    onSelectTab: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // BANNIÈRE VEDETTE
        item {
            val featured = vodMovies.firstOrNull() ?: liveChannels.firstOrNull()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF1E1B4B), Color(0xFF311042), Color(0xFF0F172A))
                        )
                    )
                    .border(1.dp, Color(0xFF6D28D9), RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "CHAÎNES TV (${liveChannels.size}) • FILMS (${vodMovies.size}) • SÉRIES (${seriesList.size})",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = featured?.title ?: "Bienvenue sur NeoTV Pro",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Zapping en plein écran, Fiches Cinéma & Reprise de lecture activés",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = { if (featured != null) onSelectMedia(featured) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Découvrir", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // NOUVEAU : SECTION "REPRENDRE LA LECTURE" (Style Netflix / myCANAL)
        if (watchHistory.isNotEmpty()) {
            item {
                Text("Reprendre la lecture", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(watchHistory) { hist ->
                        val progress = (hist.positionMs.toFloat() / hist.durationMs.coerceAtLeast(1L)).coerceIn(0.05f, 0.98f)
                        val remainingMin = ((hist.durationMs - hist.positionMs) / 60000L).coerceAtLeast(1L)
                        Column(
                            modifier = Modifier
                                .width(200.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF151A24))
                                .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(14.dp))
                                .clickable { onResumeHistoryItem(hist) }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = hist.logoUrl,
                                    contentDescription = hist.title,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0B0E14)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(hist.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Reste $remainingMin min", color = Color(0xFF38BDF8), fontSize = 11.sp)
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

        // CARROUSEL CHAÎNES EN DIRECT AVEC LOGOS
        if (liveChannels.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Direct TV • Logos & EPG", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Voir tout >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(1) })
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(liveChannels.take(20)) { ch ->
                        LaunchedEffect(ch.rawStreamId) { onRequestEpg(ch.rawStreamId) }
                        Column(
                            modifier = Modifier
                                .width(170.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF151A24))
                                .border(1.dp, Color(0xFF262F40), RoundedCornerShape(14.dp))
                                .clickable { onSelectMedia(ch) }
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.title,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0B0E14)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(ch.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = epgCache[ch.rawStreamId] ?: ch.categoryName,
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
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
                    Text("Films à l'affiche (VOD)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Voir tout >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(2) })
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(vodMovies.take(20)) { movie ->
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
                    Text("Séries Populaires", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Voir tout >", color = Color(0xFF38BDF8), fontSize = 13.sp, modifier = Modifier.clickable { onSelectTab(3) })
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(seriesList.take(20)) { serie ->
                        PosterCard(serie, onClick = { onSelectMedia(serie) })
                    }
                }
            }
        }
    }
}

// --- NOUVEL ÉCRAN : FICHE CINÉMA STYLE NETFLIX / MYCANAL ---
@Composable
fun VodDetailScreen(
    movie: MediaEntry,
    details: VodDetailedInfo?,
    isLoading: Boolean,
    savedPositionMs: Long,
    savedDurationMs: Long,
    isFavorite: Boolean,
    onToggleFav: () -> Unit,
    onPlayFromStart: () -> Unit,
    onResumePlay: (Long) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                    Text("Fiche Film", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                        .width(135.dp)
                        .height(200.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF151A24)),
                    contentScale = ContentScale.Crop
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(movie.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text(movie.categoryName, color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    if (details != null) {
                        if (details.rating.isNotBlank()) {
                            Text("⭐ Note : ${details.rating}/10", color = Color(0xFFFACC15), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (details.releaseDate.isNotBlank()) {
                            Text("Sortie : ${details.releaseDate}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        if (details.duration.isNotBlank()) {
                            Text("Durée : ${details.duration}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        if (details.genre.isNotBlank()) {
                            Text(details.genre, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // BOUTONS DE LECTURE & REPRISE AUTOMATIQUE
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (savedPositionMs > 30000L) {
                    val min = savedPositionMs / 60000L
                    Button(
                        onClick = { onResumePlay(savedPositionMs) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reprendre à $min min", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }

                Button(
                    onClick = onPlayFromStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (savedPositionMs > 30000L) Color(0xFF1E293B) else Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (savedPositionMs > 30000L) Color.White else Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (savedPositionMs > 30000L) "Recommencer depuis le début" else "Regarder le Film",
                        color = if (savedPositionMs > 30000L) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        if (isLoading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Récupération du synopsis et du casting...", color = Color(0xFF94A3B8), fontSize = 13.sp)
                }
            }
        }

        details?.let { info ->
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF151A24))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Synopsis", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = info.plot.ifBlank { "Aucun résumé disponible pour ce film." },
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    if (info.director.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Réalisateur : ${info.director}", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (info.cast.isNotBlank()) {
                        Text("Acteurs : ${info.cast}", color = Color(0xFF94A3B8), fontSize = 13.sp)
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(channels.take(300)) { ch ->
            LaunchedEffect(ch.rawStreamId) {
                if (ch.type == "LIVE") onRequestEpg(ch.rawStreamId)
            }
            val epgNow = epgCache[ch.rawStreamId]
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
                        Text("En ce moment : $epgNow", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items.take(300)) { item ->
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
    watchHistory: List<WatchHistoryEntry>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlayEpisode: (EpisodeItem, Long) -> Unit
) {
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

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF38BDF8))
            }
        } else if (episodes.isEmpty()) {
            Text("Aucun épisode trouvé pour cette série.", color = Color(0xFF94A3B8))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(episodes) { ep ->
                    val savedEp = watchHistory.find { it.id == "ep_${ep.id}" }
                    val resumeMs = savedEp?.positionMs ?: 0L
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF151A24))
                            .clickable { onPlayEpisode(ep, resumeMs) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Saison ${ep.season}", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(ep.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (resumeMs > 30000L) {
                                Text("Reprendre à ${resumeMs / 60000L} min", color = Color(0xFFA855F7), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
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
            Text("Configuration Xtream Codes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Protection anti-bannissement activée : 1 seule connexion simultanée garantie.", color = Color(0xFF38BDF8), fontSize = 13.sp)
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
                    Text("Annuler", color = Color
