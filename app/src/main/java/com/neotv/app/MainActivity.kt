package com.neotv.app

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

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
    val title: String,
    val subtitle: String,
    val badge: String,
    val progress: Float,
    val streamUrl: String
)

@Composable
fun NeoTvApp() {
    var activeStreamUrl by remember { mutableStateOf<String?>(null) }
    var activeTitle by remember { mutableStateOf("") }

    if (activeStreamUrl != null) {
        VideoPlayerScreen(
            streamUrl = activeStreamUrl!!,
            title = activeTitle,
            onBack = { activeStreamUrl = null }
        )
    } else {
        HomeScreen(
            onPlayStream = { title, url ->
                activeTitle = title
                activeStreamUrl = url
            }
        )
    }
}

@Composable
fun HomeScreen(onPlayStream: (String, String) -> Unit) {
    val selectedTab = remember { mutableStateOf(0) }
    val tabs = listOf("Accueil", "Direct TV", "Films", "Séries", "Favoris")

    val liveCards = listOf(
        ChannelItem(
            "Ligue des Champions",
            "Grand Match - Direct HD",
            "DIRECT • SPORT+",
            0.55f,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        ),
        ChannelItem(
            "Cinéma Premier",
            "Blockbuster Action 4K",
            "DIRECT • CINÉ+",
            0.35f,
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"
        ),
        ChannelItem(
            "Découverte Monde",
            "Planète Sauvage",
            "DIRECT • DOC TV",
            0.75f,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        ),
        ChannelItem(
            "Séries Cultes",
            "Saison 2 - Épisode 4",
            "VOD • SÉRIES",
            0.20f,
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // BARRE DE NAVIGATION SUPÉRIEURE (Style NeoTV / myCANAL)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "NEO",
                        color = Color(0xFF38BDF8),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = " TV",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light
                    )
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(tabs) { index, tab ->
                        val isSelected = selectedTab.value == index
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) Color(0xFF262F40) else Color.Transparent)
                                .clickable { selectedTab.value = index }
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
        }

        // BANNIÈRE "À L'AFFICHE / EN CE MOMENT"
        item {
            Text(
                text = "À l'affiche / En ce moment",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF1E1B4B), Color(0xFF311042), Color(0xFF0F172A))
                        )
                    )
                    .border(1.dp, Color(0xFF4C1D95), RoundedCornerShape(18.dp))
                    .padding(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "ÉVÉNEMENT EN DIRECT • ULTRA HD",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Ligue des Champions & Blockbusters",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Zapping instantané • Moteur ExoPlayer Media3 activé",
                            color = Color(0xFFCBD5E1),
                            fontSize = 14.sp
                        )
                    }

                    Button(
                        onClick = {
                            onPlayStream(
                                "Direct TV - Flux Test HD",
                                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                            )
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

        // SECTION "POUR VOUS / RECOMMANDATIONS" AVEC CARTES NÉON
        item {
            Text(
                text = "Pour Vous / En Direct",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(liveCards) { index, item ->
                    val isHighlighted = index == 0
                    Column(
                        modifier = Modifier
                            .width(230.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF151A24))
                            .border(
                                width = if (isHighlighted) 2.dp else 1.dp,
                                color = if (isHighlighted) Color(0xFFA855F7) else Color(0xFF262F40),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onPlayStream(item.title, item.streamUrl) }
                            .padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isHighlighted) Color(0xFFDC2626) else Color(0xFF2563EB))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = item.badge,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.subtitle,
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        LinearProgressIndicator(
                            progress = item.progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            color = Color(0xFFA855F7),
                            trackColor = Color(0xFF334155)
                        )
                    }
                }
            }
        }

        // APERÇU DU GUIDE TV (EPG) EN BAS D'ÉCRAN
        item {
            Text(
                text = "Guide TV • EPG Rapide",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF111622))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                liveCards.forEach { channel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A2130))
                            .clickable { onPlayStream(channel.title, channel.streamUrl) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(channel.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(channel.subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        Text("20:45 - 22:30", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerScreen(streamUrl: String, title: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
