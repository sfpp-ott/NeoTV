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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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

    var epgCache by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var activeStreamUrl by remember { mutableStateOf<String?>(null) }
    var activeTitle by remember { mutableStateOf("") }
    var activeStreamId by remember { mutableStateOf<String?>(null) }

    var selectedSeries by remember { mutableStateOf<MediaEntry?>(null) }
    var seriesEpisodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }
    var isSeriesLoading by remember { mutableStateOf(false) }

    fun toggleFavorite(id: String) {
        val next = favorites.toMutableSet()
        if (next.contains(id)) next.remove(id) else next.add(id)
        favorites = next
        prefs.edit().putStringSet("favs", next).apply()
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
        activeStreamUrl != null -> {
            val currentEpg = activeStreamId?.let { epgCache[it] } ?: ""
            VideoPlayerScreen(
                streamUrl = activeStreamUrl!!,
                title = activeTitle,
                epgSubtitle = currentEpg,
                onBack = { activeStreamUrl = null }
            )
        }
        selectedSeries != null -> {
            SeriesDetailScreen(
                series = selectedSeries!!,
                episodes = seriesEpisodes,
                isLoading = isSeriesLoading,
                onBack = { selectedSeries = null },
                onPlayEpisode = { epTitle, url ->
                    activeTitle = "${selectedSeries!!.title} - $epTitle"
                    activeStreamId = null
                    activeStreamUrl = url
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
                epgCache = epgCache,
                isLoading = isLoading,
                statusText = statusText,
                errorMsg = errorMsg,
                onToggleFav = { toggleFavorite(it) },
                onRequestEpg = { loadEpgForChannel(it) },
                onOpenSettings = { showSettings = true },
                onPlayMedia = { entry ->
                    if (entry.type == "SERIES") {
                        selectedSeries = entry
                        isSeriesLoading = true
                        scope.launch {
                            seriesEpisodes = fetchSeriesEpisodes(serverUrl, username, password, entry.id)
                            isSeriesLoading = false
                        }
                    } else {
                        if (entry.type == "LIVE") loadEpgForChannel(entry.id)
                        activeTitle = entry.title
                        activeStreamId = entry.id
                        activeStreamUrl = entry.streamUrl
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
    epgCache: Map<String, String>,
    isLoading: Boolean,
    statusText: String,
    errorMsg: String?,
    onToggleFav: (String) -> Unit,
    onRequestEpg: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPlayMedia: (MediaEntry) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Accueil", "Direct TV", "Films", "Séries", "Favoris")

    var selectedCatId by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    // Réinitialiser le filtre de catégorie quand on change d'onglet
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
        // BARRE DU HAUT : LOGO + BOUTON XTREAM
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

        // MENU DES 5 ONGLETS (Accueil, Direct TV, Films, Séries, Favoris)
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

        // BARRE DE RECHERCHE GLOBALE
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

        // FILTRE PAR CATÉGORIES (Bouquets TV, Genres de Films ou Séries)
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

        // CONTENU SELON L'ONGLET SÉLECTIONNÉ
        when (selectedTab) {
            0 -> {
                // ONGLET ACCUEIL : Vue combinée style myCANAL (Direct + Films + Séries)
                HomeDashboard(
                    liveChannels = liveChannels.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    vodMovies = vodMovies.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    seriesList = seriesList.filter { searchQuery.isBlank() || it.title.contains(searchQuery, true) },
                    epgCache = epgCache,
                    onRequestEpg = onRequestEpg,
                    onPlayMedia = onPlayMedia,
                    onSelectTab = { selectedTab = it }
                )
            }
            1 -> {
                // ONGLET DIRECT TV AVEC LOGOS + EPG EN TEMPS RÉEL + FAVORIS
                LiveChannelsList(
                    channels = currentList,
                    favorites = favorites,
                    epgCache = epgCache,
                    onToggleFav = onToggleFav,
                    onRequestEpg = onRequestEpg,
                    onPlayMedia = onPlayMedia
                )
            }
            2, 3 -> {
                // ONGLETS FILMS & SÉRIES : GRILLE D'AFFICHES CINÉMA (3 colonnes)
                PosterGrid(
                    items = currentList,
                    favorites = favorites,
                    onToggleFav = onToggleFav,
                    onPlayMedia = onPlayMedia
                )
            }
            4 -> {
                // ONGLET FAVORIS
                if (currentList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun favori pour le moment. Appuyez sur ⭐ à côté d'une chaîne ou d'un film !", color = Color(0xFF94A3B8))
                    }
                } else {
                    LiveChannelsList(
                        channels = currentList,
                        favorites = favorites,
                        epgCache = epgCache,
                        onToggleFav = onToggleFav,
                        onRequestEpg = onRequestEpg,
                        onPlayMedia = onPlayMedia
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
    epgCache: Map<String, String>,
    onRequestEpg: (String) -> Unit,
    onPlayMedia: (MediaEntry) -> Unit,
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
                        text = featured?.title ?: "Bienvenue sur NeoTV",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Logos HD, EPG en direct, Films & Séries activés",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = { if (featured != null) onPlayMedia(featured) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lancer maintenant", color = Color.Black, fontWeight = FontWeight.Bold)
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
                        LaunchedEffect(ch.id) { onRequestEpg(ch.id) }
                        Column(
                            modifier = Modifier
                                .width(170.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF151A24))
                                .border(1.dp, Color(0xFF262F40), RoundedCornerShape(14.dp))
                                .clickable { onPlayMedia(ch) }
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
                                text = epgCache[ch.id] ?: ch.categoryName,
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

        // CARROUSEL FILMS (VOD) AVEC AFFICHES
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
                        PosterCard(movie, onClick = { onPlayMedia(movie) })
                    }
                }
            }
        }

        // CARROUSEL SÉRIES AVEC AFFICHES
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
                        PosterCard(serie, onClick = { onPlayMedia(serie) })
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
            // Charge automatiquement l'EPG des chaînes visibles à l'écran
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
                // LOGO DE LA CHAÎNE
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

                // NOM DE LA CHAÎNE + PROGRAMME TV (EPG) EN DIRECT
                Column(modifier = Modifier.weight(1f)) {
                    Text(ch.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!epgNow.isNullOrBlank()) {
                        Text("En ce moment : $epgNow", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(ch.categoryName, color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1)
                    }
                }

                // BOUTON FAVORI ÉTOILE
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
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlayEpisode: (String, String) -> Unit
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF151A24))
                            .clickable { onPlayEpisode(ep.title, ep.streamUrl) }
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
            Text("Configuration Xtream Codes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Active automatiquement : Direct TV + Logos + EPG + Films + Séries", color = Color(0xFF38BDF8), fontSize = 13.sp)
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

// --- FONCTIONS RÉSEAU XTREAM CODES (LIVE, VOD, SÉRIES, EPG) ---

suspend fun fetchXtreamLive(
    baseUrl: String,
    user: String,
    pass: String
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catMap = fetchCategoryMap("$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_categories")

    val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_streams")
    val arr = JSONArray(json)
    val list = mutableListOf<MediaEntry>()

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
    baseUrl: String,
    user: String,
    pass: String
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catMap = fetchCategoryMap("$cleanBase/player_api.php?username=$user&password=$pass&action=get_vod_categories")

    val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_vod_streams")
    val arr = JSONArray(json)
    val list = mutableListOf<MediaEntry>()

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
    baseUrl: String,
    user: String,
    pass: String
): Pair<List<CategoryItem>, List<MediaEntry>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
    val catMap = fetchCategoryMap("$cleanBase/player_api.php?username=$user&password=$pass&action=get_series_categories")

    val json = httpGet("$cleanBase/player_api.php?username=$user&password=$pass&action=get_series")
    val arr = JSONArray(json)
    val list = mutableListOf<MediaEntry>()

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

fun fetchCategoryMap(url: String): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    return try {
        val arr = JSONArray(httpGet(url))
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
        readTimeout = 30000
        setRequestProperty("User-Agent", "NeoTV/2.0 (ExoPlayer)")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}

@Composable
fun VideoPlayerScreen(streamUrl: String, title: String, epgSubtitle: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (epgSubtitle.isNotBlank()) {
                    Text(text = "En cours : $epgSubtitle", color = Color(0xFF38BDF8), fontSize = 12.sp)
                }
            }
        }
    }
}
