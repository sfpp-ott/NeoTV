package com.neotv.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

data class ChannelItem(
    val id: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val streamUrl: String
)

data class CategoryItem(
    val id: String,
    val name: String
)

@Composable
fun NeoTvApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("neotv_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(prefs.getString("server", "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var m3uUrl by remember { mutableStateOf(prefs.getString("m3u", "") ?: "") }

    var channels by remember { mutableStateOf<List<ChannelItem>>(emptyList()) }
    var categories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var activeStreamUrl by remember { mutableStateOf<String?>(null) }
    var activeTitle by remember { mutableStateOf("") }

    fun loadIptvData() {
        if (serverUrl.isBlank() && m3uUrl.isBlank()) return
        isLoading = true
        errorMsg = null
        scope.launch {
            try {
                if (serverUrl.isNotBlank() && username.isNotBlank()) {
                    val result = fetchXtreamChannels(serverUrl, username, password)
                    categories = result.first
                    channels = result.second
                } else if (m3uUrl.isNotBlank()) {
                    val result = fetchM3uChannels(m3uUrl)
                    categories = result.first
                    channels = result.second
                }
            } catch (e: Exception) {
                errorMsg = "Erreur de connexion : vérifiez vos identifiants (${e.localizedMessage})"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadIptvData()
    }

    when {
        activeStreamUrl != null -> {
            VideoPlayerScreen(
                streamUrl = activeStreamUrl!!,
                title = activeTitle,
                onBack = { activeStreamUrl = null }
            )
        }
        showSettings -> {
            IptvSetupScreen(
                initialServer = serverUrl,
                initialUser = username,
                initialPass = password,
                initialM3u = m3uUrl,
                onSave = { srv, usr, pwd, m3u ->
                    serverUrl = srv.trim().removeSuffix("/")
                    username = usr.trim()
                    password = pwd.trim()
                    m3uUrl = m3u.trim()
                    prefs.edit()
                        .putString("server", serverUrl)
                        .putString("user", username)
                        .putString("pass", password)
                        .putString("m3u", m3uUrl)
                        .apply()
                    showSettings = false
                    loadIptvData()
                },
                onCancel = { showSettings = false }
            )
        }
        else -> {
            HomeScreen(
                channels = channels,
                categories = categories,
                isLoading = isLoading,
                errorMsg = errorMsg,
                onOpenSettings = { showSettings = true },
                onPlayStream = { title, url ->
                    activeTitle = title
                    activeStreamUrl = url
                }
            )
        }
    }
}

@Composable
fun HomeScreen(
    channels: List<ChannelItem>,
    categories: List<CategoryItem>,
    isLoading: Boolean,
    errorMsg: String?,
    onOpenSettings: () -> Unit,
    onPlayStream: (String, String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Accueil", "Direct TV", "Films", "Séries")
    var selectedCategoryId by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    val demoChannels = listOf(
        ChannelItem("1", "Ligue des Champions (Démo)", "SPORT", "DIRECT • SPORT+", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
        ChannelItem("2", "Cinéma Premier 4K (Démo)", "CINE", "DIRECT • CINÉ+", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8")
    )

    val displayedChannels = remember(channels, selectedCategoryId, searchQuery) {
        val source = if (channels.isEmpty()) demoChannels else channels
        source.filter { ch ->
            val matchCat = selectedCategoryId == "ALL" || ch.categoryId == selectedCategoryId
            val matchSearch = searchQuery.isBlank() || ch.title.contains(searchQuery, ignoreCase = true)
            matchCat && matchSearch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
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
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (channels.isEmpty()) "Connecter IPTV" else "IPTV (${channels.size})", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(tabs) { index, tab ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFF262F40) else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = tab,
                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF1E1B4B), Color(0xFF311042), Color(0xFF0F172A))
                        )
                    )
                    .border(1.dp, Color(0xFF4C1D95), RoundedCornerShape(18.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (channels.isEmpty()) "MODE DÉMO • CONNECTEZ VOTRE IPTV EN HAUT À DROITE" else "CONNECTÉ • ${channels.size} CHAÎNES EN DIRECT DISPONIBLES",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = displayedChannels.firstOrNull()?.title ?: "Ligue des Champions & Blockbusters",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Zapping instantané • Moteur ExoPlayer Media3 activé",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val first = displayedChannels.firstOrNull()
                            if (first != null) onPlayStream(first.title, first.streamUrl)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Regarder le Direct", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Chargement ultra-rapide de vos chaînes IPTV...", color = Color.White)
                }
            }
        }

        errorMsg?.let { msg ->
            item {
                Text(msg, color = Color(0xFFEF4444), fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Rechercher une chaîne (ex: Canal, RMC, TF1, BeIN...)", color = Color(0xFF64748B), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        if (categories.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        CategoryChip("Toutes (${channels.size})", selectedCategoryId == "ALL") {
                            selectedCategoryId = "ALL"
                        }
                    }
                    items(categories) { cat ->
                        CategoryChip(cat.name, selectedCategoryId == cat.id) {
                            selectedCategoryId = cat.id
                        }
                    }
                }
            }
        }

        item {
            Text("En Direct • Sélection Rapide", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(displayedChannels.take(15)) { index, item ->
                    val isHighlighted = index == 0
                    Column(
                        modifier = Modifier
                            .width(210.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF151A24))
                            .border(
                                width = if (isHighlighted) 2.dp else 1.dp,
                                color = if (isHighlighted) Color(0xFFA855F7) else Color(0xFF262F40),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onPlayStream(item.title, item.streamUrl) }
                            .padding(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isHighlighted) Color(0xFFDC2626) else Color(0xFF2563EB))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(item.categoryName.take(18), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Flux Direct HD", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Text("Grille TV • ${displayedChannels.size} chaînes", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        items(displayedChannels.take(200)) { channel ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF151A24))
                    .clickable { onPlayStream(channel.title, channel.streamUrl) }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(channel.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(channel.categoryName, color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF38BDF8))
            }
        }
    }
}

@Composable
fun CategoryChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isSelected) Color(0xFF8B5CF6) else Color(0xFF1A2130))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun IptvSetupScreen(
    initialServer: String,
    initialUser: String,
    initialPass: String,
    initialM3u: String,
    onSave: (String, String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    var server by remember { mutableStateOf(initialServer) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    var m3u by remember { mutableStateOf(initialM3u) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Connexion IPTV (Xtream Codes ou M3U)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Vos identifiants restent 100 % privés sur votre appareil.", color = Color(0xFF94A3B8), fontSize = 13.sp)
        }

        item {
            Text("Option 1 : Connexion Xtream Codes (Recommandé)", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("URL du serveur (ex: http://monserveur.com:8080)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Nom d'utilisateur (Username)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
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
            Spacer(modifier = Modifier.height(8.dp))
            Text("Option 2 : Ou Lien M3U direct", color = Color(0xFFA855F7), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = m3u,
                onValueChange = { m3u = it },
                label = { Text("Lien M3U (ex: http://.../get.php?...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSave(server, user, pass, m3u) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Enregistrer & Charger", color = Color.White, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Annuler", color = Color.White)
                }
            }
        }
    }
}

suspend fun fetchXtreamChannels(
    baseUrl: String,
    user: String,
    pass: String
): Pair<List<CategoryItem>, List<ChannelItem>> = withContext(Dispatchers.IO) {
    val cleanBase = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"

    val catUrl = "$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_categories"
    val catJson = httpGet(catUrl)
    val catArray = JSONArray(catJson)
    val categories = mutableListOf<CategoryItem>()
    val catMap = mutableMapOf<String, String>()

    for (i in 0 until catArray.length()) {
        val obj = catArray.getJSONObject(i)
        val id = obj.optString("category_id")
        val name = obj.optString("category_name", "Général")
        categories.add(CategoryItem(id, name))
        catMap[id] = name
    }

    val streamUrl = "$cleanBase/player_api.php?username=$user&password=$pass&action=get_live_streams"
    val streamJson = httpGet(streamUrl)
    val streamArray = JSONArray(streamJson)
    val channels = mutableListOf<ChannelItem>()

    for (i in 0 until streamArray.length()) {
        val obj = streamArray.getJSONObject(i)
        val streamId = obj.optString("stream_id")
        val name = obj.optString("name", "Chaîne $streamId")
        val catId = obj.optString("category_id")
        val catName = catMap[catId] ?: "Direct TV"
        val playUrl = "$cleanBase/live/$user/$pass/$streamId.ts"
        channels.add(ChannelItem(streamId, name, catId, catName, playUrl))
    }

    Pair(categories, channels)
}

suspend fun fetchM3uChannels(m3uUrl: String): Pair<List<CategoryItem>, List<ChannelItem>> = withContext(Dispatchers.IO) {
    val content = httpGet(m3uUrl)
    val lines = content.lines()
    val channels = mutableListOf<ChannelItem>()
    val catSet = linkedSetOf<String>()

    var currentTitle = "Chaîne TV"
    var currentGroup = "Direct TV"
    var index = 0

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXTINF:")) {
            currentTitle = trimmed.substringAfterLast(",", "Chaîne TV").trim()
            val groupMatch = Regex("""group-title="([^"]*)"""").find(trimmed)
            currentGroup = groupMatch?.groupValues?.getOrNull(1)?.ifBlank { "Général" } ?: "Général"
        } else if (trimmed.startsWith("http")) {
            index++
            catSet.add(currentGroup)
            channels.add(ChannelItem(index.toString(), currentTitle, currentGroup, currentGroup, trimmed))
        }
    }

    val categories = catSet.map { CategoryItem(it, it) }
    Pair(categories, channels)
}

fun httpGet(urlStr: String): String {
    val url = URL(urlStr)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 25000
        setRequestProperty("User-Agent", "NeoTV/1.0 (ExoPlayer)")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}

@Composable
fun VideoPlayerScreen(streamUrl: String, title: String, onBack: () -> Unit) {
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Retour", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
