package com.ashplay

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.TrafficStats
import android.os.Bundle
import android.os.Process
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.roundToInt

private const val API_URL = "http://192.168.168.10/ashplay-api.php"

private val Bg = Color(0xFF090C13)
private val Panel = Color(0xFF171C27)
private val Orange = Color(0xFFFF7900)
private val Muted = Color(0xFF9AA6B8)
private val Green = Color(0xFF27E66B)

// বাংলা থেকে বাংলিশে রূপান্তরের অক্ষর তালিকা
private val bengaliToLatin = mapOf(
    'অ' to "o", 'আ' to "a", 'ই' to "i", 'ঈ' to "i",
    'উ' to "u", 'ঊ' to "u", 'ঋ' to "ri", 'এ' to "e",
    'ঐ' to "oi", 'ও' to "o", 'ঔ' to "ou",
    'ক' to "k", 'খ' to "kh", 'গ' to "g", 'ঘ' to "gh",
    'ঙ' to "ng", 'চ' to "ch", 'ছ' to "chh", 'জ' to "j",
    'ঝ' to "jh", 'ঞ' to "n", 'ট' to "t", 'ঠ' to "th",
    'ড' to "d", 'ঢ' to "dh", 'ণ' to "n", 'ত' to "t",
    'থ' to "th", 'দ' to "d", 'ধ' to "dh", 'ন' to "n",
    'প' to "p", 'ফ' to "f", 'ব' to "b", 'ভ' to "bh",
    'ম' to "m", 'য' to "j", 'র' to "r", 'ল' to "l",
    'শ' to "sh", 'ষ' to "sh", 'স' to "s", 'হ' to "h",
    'ড়' to "r", 'ঢ়' to "rh", 'য়' to "y",
    'া' to "a", 'ি' to "i", 'ী' to "i", 'ু' to "u",
    'ূ' to "u", 'ৃ' to "ri", 'ে' to "e", 'ৈ' to "oi",
    'ো' to "o", 'ৌ' to "ou", 'ং' to "ng", 'ঃ' to "h",
    'ঁ' to "n", '্' to "", 'ৎ' to "t"
)

private fun normalizeForSearch(text: String): String {
    return buildString {
        text.lowercase().forEach { char ->
            append(bengaliToLatin[char] ?: char)
        }
    }
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

data class VideoItem(
    val name: String,
    val url: String,
    val path: String,
    val thumbnailUrl: String = ""
)

data class Category(
    val name: String,
    val videos: List<VideoItem>
)

data class FeedItem(
    val category: String,
    val video: VideoItem
)

enum class BottomTab {
    HOME, FRIENDS, PROFILE
}

// ==========================================
// THUMBNAIL FUNCTIONS
// ==========================================

private fun downloadServerThumbnail(url: String): Bitmap? {
    if (url.isBlank()) return null

    var connection: HttpURLConnection? = null

    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            requestMethod = "GET"
        }

        if (connection.responseCode == 200) {
            connection.inputStream.use {
                BitmapFactory.decodeStream(it)
            }
        } else {
            null
        }

    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun createVideoThumbnail(videoUrl: String): Bitmap? {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(videoUrl, HashMap())

        retriever.getFrameAtTime(
            1_000_000,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        )
    } catch (_: Exception) {
        null
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

private fun uploadThumbnail(
    apiUrl: String,
    videoPath: String,
    bitmap: Bitmap
): Boolean {

    var connection: HttpURLConnection? = null

    return try {

        val output = ByteArrayOutputStream()

        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            82,
            output
        )

        val imageBytes = output.toByteArray()
        val boundary = "AshPlayBoundary${UUID.randomUUID()}"

        connection = (URL("$apiUrl?action=thumbnail_upload")
            .openConnection() as HttpURLConnection).apply {

            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000

            setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )
        }

        DataOutputStream(connection.outputStream).use { data ->

            data.writeBytes("--$boundary\r\n")
            data.writeBytes(
                "Content-Disposition: form-data; name=\"path\"\r\n\r\n"
            )
            data.write(videoPath.toByteArray(Charsets.UTF_8))
            data.writeBytes("\r\n")

            data.writeBytes("--$boundary\r\n")
            data.writeBytes(
                "Content-Disposition: form-data; name=\"thumbnail\"; filename=\"thumbnail.jpg\"\r\n"
            )
            data.writeBytes("Content-Type: image/jpeg\r\n\r\n")

            data.write(imageBytes)

            data.writeBytes("\r\n--$boundary--\r\n")
            data.flush()
        }

        connection.responseCode in 200..299

    } catch (_: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

private suspend fun getOrCreateThumbnail(
    video: VideoItem
): Bitmap? = withContext(Dispatchers.IO) {

    val serverBitmap = downloadServerThumbnail(video.thumbnailUrl)

    if (serverBitmap != null) {
        return@withContext serverBitmap
    }

    val newBitmap = createVideoThumbnail(video.url)
        ?: return@withContext null

    uploadThumbnail(
        API_URL,
        video.path,
        newBitmap
    )

    newBitmap
}

// ==========================================
// MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor =
            android.graphics.Color.rgb(9, 12, 19)

        window.navigationBarColor =
            android.graphics.Color.rgb(9, 12, 19)

        setContent {
            AshPlayApp()
        }
    }
}

// ==========================================
// MAIN APP
// ==========================================

@Composable
private fun AshPlayApp() {

    var categories by remember {
        mutableStateOf<List<Category>>(emptyList())
    }

    var loading by remember {
        mutableStateOf(true)
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }

    var query by remember {
        mutableStateOf("")
    }

    var showSearchScreen by remember {
        mutableStateOf(false)
    }

    val context = LocalContext.current
    val searchPrefs = remember {
        context.getSharedPreferences("ashplay_search_history", android.content.Context.MODE_PRIVATE)
    }
    var searchHistory by remember {
        mutableStateOf(
            try {
                val saved = org.json.JSONArray(searchPrefs.getString("items_json", "[]"))
                List(saved.length()) { index -> saved.optString(index) }.filter { it.isNotBlank() }
            } catch (_: Exception) { emptyList() }
        )
    }

    var onlineCount by remember {
        mutableIntStateOf(0)
    }

    var txBytes by remember {
        mutableLongStateOf(0L)
    }

    var selectedVideo by remember {
        mutableStateOf<VideoItem?>(null)
    }

    var selectedTab by remember {
        mutableStateOf(BottomTab.HOME)
    }

    val scope = rememberCoroutineScope()

    val feed = remember(categories) {
        categories.flatMap { cat ->
            cat.videos.map { video ->
                FeedItem(cat.name, video)
            }
        }.shuffled()
    }

    val allVideos = remember(feed) {
        feed.map { it.video }
    }

    suspend fun request(url: String): String =
        withContext(Dispatchers.IO) {

            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }

            try {
                val stream =
                    if (c.responseCode in 200..299)
                        c.inputStream
                    else
                        c.errorStream

                stream.bufferedReader().use {
                    it.readText()
                }

            } finally {
                c.disconnect()
            }
        }

    suspend fun loadLibrary() {

        loading = true
        error = null

        try {
            val json = JSONObject(request(API_URL))

            check(json.optBoolean("success")) {
                json.optString("error", "API response error")
            }

            val arr = json.getJSONArray("categories")
            val result = mutableListOf<Category>()

            for (i in 0 until arr.length()) {

                val c = arr.getJSONObject(i)
                val vArr = c.getJSONArray("videos")
                val vids = mutableListOf<VideoItem>()

                for (j in 0 until vArr.length()) {

                    val v = vArr.getJSONObject(j)

                    vids += VideoItem(
                        name = v.optString("name"),
                        url = v.optString("url"),
                        path = v.optString("path"),
                        thumbnailUrl = v.optString("thumbnail_url")
                    )
                }

                if (vids.isNotEmpty()) {
                    result += Category(
                        c.optString("name"),
                        vids
                    )
                }
            }

            categories = result

            onlineCount = json.optInt(
                "online_viewers",
                onlineCount
            )

        } catch (e: Exception) {

            error =
                "সার্ভারে সংযোগ করা যায়নি। ফোন ও কম্পিউটার একই Wi-Fi-তে আছে কি না এবং XAMPP Apache চালু আছে কি না দেখুন.\n${e.message ?: ""}"

        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {

        loadLibrary()

        var previous =
            TrafficStats.getUidRxBytes(Process.myUid())

        while (isActive) {

            delay(2000)

            val current =
                TrafficStats.getUidRxBytes(Process.myUid())

            txBytes =
                if (previous >= 0 && current >= previous)
                    current - previous
                else
                    0L

            previous = current
        }
    }

    LaunchedEffect(Unit) {

        while (isActive) {

            try {
                onlineCount = JSONObject(
                    request("$API_URL?action=count")
                ).optInt("online_viewers", onlineCount)

            } catch (_: Exception) {
            }

            delay(10000)
        }
    }

    LaunchedEffect(selectedVideo?.url) {

        val video = selectedVideo ?: return@LaunchedEffect

        val session = UUID.randomUUID().toString()

        try {
            while (isActive) {

                request(
                    "$API_URL?action=heartbeat&session_id=$session"
                )

                delay(15000)
            }

        } finally {

            try {
                request(
                    "$API_URL?action=leave&session_id=$session"
                )
            } catch (_: Exception) {
            }
        }
    }

    fun saveSearchHistory(value: String) {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return
        val updated = (listOf(cleaned) + searchHistory.filterNot { it.equals(cleaned, ignoreCase = true) })
            .take(30)
        searchHistory = updated
        searchPrefs.edit().putString("items_json", org.json.JSONArray(updated).toString()).apply()
    }

    fun applySearch(value: String) {
        query = value
        if (value.isNotBlank()) saveSearchHistory(value)
        selectedTab = BottomTab.HOME
        showSearchScreen = false
    }

    BackHandler(enabled = showSearchScreen) {
        showSearchScreen = false
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange,
            background = Bg,
            surface = Panel
        )
    ) {

        Surface(
            Modifier.fillMaxSize(),
            color = Bg
        ) {

            if (selectedVideo != null) {

                val currentIndex = allVideos.indexOfFirst {
                    it.path == selectedVideo!!.path
                }.coerceAtLeast(0)

                VideoPlayerScreen(
                    video = selectedVideo!!,
                    videos = allVideos,
                    currentIndex = currentIndex,
                    onVideoChange = { index ->
                        if (index in allVideos.indices) {
                            selectedVideo = allVideos[index]
                        }
                    },
                    onBack = {
                        selectedVideo = null
                    }
                )

            } else if (showSearchScreen) {

                SearchScreen(
                    initialQuery = query,
                    history = searchHistory,
                    onBack = { showSearchScreen = false },
                    onSearch = { applySearch(it) },
                    onClearHistory = {
                        searchHistory = emptyList()
                        searchPrefs.edit().remove("items_json").apply()
                    }
                )

            } else {

                Column(Modifier.fillMaxSize()) {

                    AshPlayHeader(
                        txBytes,
                        onlineCount
                    )

                    SearchBar(
                        query = query,
                        onClick = { showSearchScreen = true }
                    )

                    when {

                        loading -> {

                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Orange
                                )
                            }
                        }

                        error != null -> {

                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {

                                Text(
                                    error!!,
                                    color = Color(0xFFFFB4AB)
                                )

                                Spacer(Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        scope.launch {
                                            loadLibrary()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Orange
                                    )
                                ) {
                                    Text("আবার চেষ্টা করুন")
                                }
                            }
                        }

                        selectedTab == BottomTab.HOME -> {

                            val searchQuery =
                                normalizeForSearch(query)

                            val filtered = feed.filter { item ->

                                searchQuery.isBlank() ||

                                normalizeForSearch(
                                    item.video.name.substringBeforeLast('.')
                                ).contains(searchQuery) ||

                                normalizeForSearch(
                                    item.category
                                ).contains(searchQuery)
                            }

                            if (filtered.isEmpty()) {

                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {

                                    Text(
                                        if (query.isBlank())
                                            "কোনো ভিডিও পাওয়া যায়নি"
                                        else
                                            "সার্চে কোনো ভিডিও পাওয়া যায়নি",
                                        color = Muted
                                    )
                                }

                            } else {

                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = 8.dp,
                                        bottom = 12.dp
                                    ),
                                    verticalArrangement =
                                        Arrangement.spacedBy(12.dp)
                                ) {

                                    items(
                                        filtered,
                                        key = { it.video.path }
                                    ) { item ->

                                        VideoFeedCard(item) {
                                            selectedVideo = item.video
                                        }
                                    }
                                }
                            }
                        }

                        selectedTab == BottomTab.FRIENDS -> {

                            EmptyTab(
                                Modifier.weight(1f),
                                "Friends",
                                "বন্ধুদের ফিচার পরবর্তী ধাপে যুক্ত হবে"
                            )
                        }

                        else -> {

                            EmptyTab(
                                Modifier.weight(1f),
                                "Profile",
                                "প্রোফাইল ফিচার পরবর্তী ধাপে যুক্ত হবে"
                            )
                        }
                    }

                    BottomNavigation(selectedTab) {
                        selectedTab = it
                    }
                }
            }
        }
    }
}

// ==========================================
// HEADER
// ==========================================

@Composable
private fun AshPlayHeader(
    txBytes: Long,
    onlineCount: Int
) {

    val txText =
        if (txBytes >= 1024L * 1024L)
            String.format(
                "%.1f MB",
                txBytes / (1024.0 * 1024.0)
            )
        else
            String.format(
                "%.0f KB",
                txBytes / 1024.0
            )

    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {

        Row(
            Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                "TX",
                color = Orange,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Spacer(Modifier.width(5.dp))

            Text(
                txText,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {

            Image(
                painterResource(R.drawable.ashplay_logo),
                "AshPlay logo",
                Modifier.size(42.dp)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                "AshPlay",
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Row(
            Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                Modifier
                    .size(10.dp)
                    .background(Green, CircleShape)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                onlineCount.toString(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ==========================================
// SEARCH BAR + DEDICATED SEARCH SCREEN
// ==========================================

@Composable
private fun SearchBar(
    query: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 12.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = "Search",
            tint = Color(0xFFB8C3D4),
            modifier = Modifier.size(25.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = query.ifBlank { "Search videos..." },
            color = if (query.isBlank()) Color(0xFF8E9AAF) else Color.White,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SearchScreen(
    initialQuery: String,
    history: List<String>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    var text by remember { mutableStateOf(initialQuery) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(180)
        try { focusRequester.requestFocus() } catch (_: Exception) { }
        keyboard?.show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { keyboard?.hide(); onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(54.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF242A36))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 17.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { keyboard?.hide(); onSearch(text) }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text("Search videos...", color = Color(0xFF8E9AAF), fontSize = 17.sp)
                            }
                            inner()
                        }
                    }
                )
                if (text.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = { text = ""; focusRequester.requestFocus(); keyboard?.show() },
                        shape = CircleShape,
                        color = Color(0xFF343D50),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White, modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
        }

        if (text.isBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent searches", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    Text(
                        "Clear",
                        color = Orange,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onClearHistory).padding(6.dp)
                    )
                }
            }

            if (history.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    Text("এখনো কোনো সার্চ করা হয়নি", color = Muted, modifier = Modifier.padding(top = 34.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(history, key = { it }) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { keyboard?.hide(); onSearch(item) }
                                .padding(horizontal = 22.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFFB8C3D4), modifier = Modifier.size(25.dp))
                            Spacer(Modifier.width(22.dp))
                            Text(
                                item,
                                color = Color.White,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.ArrowBack, contentDescription = "Use search", tint = Color(0xFFB8C3D4), modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { keyboard?.hide(); onSearch(text) }
                    .padding(horizontal = 22.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(20.dp))
                Text(text, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ==========================================
// VIDEO FEED CARD
// ==========================================

@Composable
private fun VideoFeedCard(
    item: FeedItem,
    onClick: () -> Unit
) {

    var bitmap by remember(item.video.path) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(item.video.path) {
        bitmap = getOrCreateThumbnail(item.video)
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = Panel
        )
    ) {

        Column {

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF27354A),
                                Color(0xFF111722)
                            )
                        )
                    )
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {

                if (bitmap != null) {

                    Image(
                        bitmap!!.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                } else {

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        Text(
                            "AshPlay",
                            color = Color.White.copy(alpha = .7f),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            item.category,
                            color = Color(0xFFFFC18B),
                            fontSize = 13.sp
                        )
                    }
                }

                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        "▶",
                        color = Color.White,
                        fontSize = 27.sp
                    )
                }

                Surface(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(9.dp),
                    color = Color(0xDD080A0F),
                    shape = RoundedCornerShape(5.dp)
                ) {

                    Text(
                        "PLAY",
                        Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        ),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 13.dp,
                        end = 8.dp,
                        top = 10.dp,
                        bottom = 11.dp
                    ),
                verticalAlignment = Alignment.Top
            ) {

                Box(
                    Modifier
                        .size(43.dp)
                        .clip(CircleShape)
                        .background(Orange),
                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        item.category.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                Spacer(Modifier.width(11.dp))

                Column(Modifier.weight(1f)) {

                    Text(
                        item.video.name.substringBeforeLast('.'),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(7.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {

                        Surface(
                            color = Color(0xFF303D50),
                            shape = RoundedCornerShape(6.dp)
                        ) {

                            Text(
                                item.category,
                                Modifier.padding(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                ),
                                color = Color(0xFFD9E4F5),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Text(
                            "AshPlay Library",
                            color = Muted,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }

                Icon(
                    Icons.Default.MoreVert,
                    null,
                    tint = Color(0xFFD6DEEA),
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(22.dp)
                )
            }
        }
    }
}

// ==========================================
// BOTTOM NAVIGATION
// ==========================================

@Composable
private fun BottomNavigation(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit
) {

    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(Color(0xFF0D111A)),
        verticalAlignment = Alignment.CenterVertically
    ) {

        BottomItem(
            Modifier.weight(1f),
            "Home",
            Icons.Default.Home,
            selected == BottomTab.HOME
        ) {
            onSelect(BottomTab.HOME)
        }

        BottomItem(
            Modifier.weight(1f),
            "Friends",
            Icons.Default.People,
            selected == BottomTab.FRIENDS
        ) {
            onSelect(BottomTab.FRIENDS)
        }

        BottomItem(
            Modifier.weight(1f),
            "Profile",
            Icons.Default.Person,
            selected == BottomTab.PROFILE
        ) {
            onSelect(BottomTab.PROFILE)
        }
    }
}

@Composable
private fun BottomItem(
    modifier: Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {

    val color =
        if (active) Orange
        else Color(0xFFD6DEEA)

    Column(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Icon(
            icon,
            null,
            tint = color,
            modifier = Modifier.size(25.dp)
        )

        Spacer(Modifier.height(3.dp))

        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight =
                if (active) FontWeight.Bold
                else FontWeight.Medium
        )
    }
}

// ==========================================
// EMPTY TAB
// ==========================================

@Composable
private fun EmptyTab(
    modifier: Modifier,
    title: String,
    message: String
) {

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            message,
            color = Muted,
            fontSize = 14.sp
        )
    }
}

// ==========================================
// VIDEO PLAYER SCREEN
// ==========================================

@Composable
private fun VideoPlayerScreen(
    video: VideoItem,
    videos: List<VideoItem>,
    currentIndex: Int,
    onVideoChange: (Int) -> Unit,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val activity = context as? Activity

    var fullscreen by remember {
        mutableStateOf(false)
    }

    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,
                5_000,
                2_000,
                2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
    }

    val player = remember(video.url) {

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {

                setMediaItem(
                    MediaItem.fromUri(video.url)
                )

                prepare()
                playWhenReady = true
            }
    }

    var isPlaying by remember(video.url) {
        mutableStateOf(true)
    }

    var position by remember(video.url) {
        mutableLongStateOf(0L)
    }

    var duration by remember(video.url) {
        mutableLongStateOf(0L)
    }

    var volume by remember(video.url) {
        mutableFloatStateOf(1f)
    }

    var showControls by remember(video.url) {
        mutableStateOf(true)
    }

    var interactionKey by remember(video.url) {
        mutableIntStateOf(0)
    }

    var seekFeedback by remember(video.url) {
        mutableIntStateOf(0)
    }

    var seekFeedbackKey by remember(video.url) {
        mutableIntStateOf(0)
    }

    fun showControlsNow() {
        showControls = true
        interactionKey++
    }

    fun togglePlayback() {
        if (player.isPlaying) {
            player.pause()
            isPlaying = false
            showControls = true
            interactionKey++
        } else {
            player.play()
            isPlaying = true
            showControls = true
            interactionKey++
        }
    }

    fun seekBy(amount: Long) {
        val total = player.duration
            .takeIf { it > 0L }
            ?: Long.MAX_VALUE

        val newPosition = (player.currentPosition + amount)
            .coerceIn(0L, total)

        player.seekTo(newPosition)
        position = newPosition
    }

    fun changeFullscreen(enabled: Boolean) {

        fullscreen = enabled

        if (enabled) {

            activity?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

            activity?.window?.decorView?.systemUiVisibility =
                (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )

        } else {

            activity?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            activity?.window?.decorView?.systemUiVisibility = 0
        }
    }

    fun goBack() {
        if (fullscreen) {
            changeFullscreen(false)
        } else {
            onBack()
        }
    }

    BackHandler {
        goBack()
    }

    DisposableEffect(player) {

        val listener = object : Player.Listener {

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing

                if (!playing) {
                    showControls = true
                    interactionKey++
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = player.duration.coerceAtLeast(0L)
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            player.release()

            activity?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            activity?.window?.decorView?.systemUiVisibility = 0
        }
    }

    LaunchedEffect(player) {

        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(400)
        }
    }

    LaunchedEffect(showControls, isPlaying, interactionKey) {

        if (showControls && isPlaying) {
            delay(3000)

            if (isPlaying) {
                showControls = false
            }
        }
    }

    LaunchedEffect(seekFeedbackKey) {

        if (seekFeedback != 0) {
            delay(700)
            seekFeedback = 0
        }
    }

    DisposableEffect(video.url) {
        onDispose {
            if (fullscreen) {
                changeFullscreen(false)
            }
        }
    }

    if (fullscreen) {

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {

            PlayerVideoContent(
                player = player,
                video = video,
                videos = videos,
                currentIndex = currentIndex,
                showControls = showControls,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                volume = volume,
                seekFeedback = seekFeedback,
                onTap = {
                    showControlsNow()
                },
                onDoubleTap = { forward ->

                    seekBy(if (forward) 10_000L else -10_000L)

                    seekFeedback = if (forward) 1 else -1
                    seekFeedbackKey++

                    showControls = false
                },
                onPlayPause = {
                    togglePlayback()
                },
                onPrevious = {
                    if (currentIndex > 0) {
                        onVideoChange(currentIndex - 1)
                    }
                },
                onNext = {
                    if (currentIndex < videos.lastIndex) {
                        onVideoChange(currentIndex + 1)
                    }
                },
                onSeek = {
                    player.seekTo(it)
                    position = it
                },
                onVolumeChange = {
                    volume = it
                    player.volume = it
                },
                onFullscreen = {
                    changeFullscreen(false)
                },
                onBack = {
                    goBack()
                }
            )
        }

    } else {

        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
        ) {

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        goBack()
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Icon(
                    Icons.Default.ArrowBack,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(23.dp)
                )

                Spacer(Modifier.width(10.dp))

                Text(
                    "ফিরে যান",
                    color = Color.White,
                    fontSize = 17.sp
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {

                PlayerVideoContent(
                    player = player,
                    video = video,
                    videos = videos,
                    currentIndex = currentIndex,
                    showControls = showControls,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    volume = volume,
                    seekFeedback = seekFeedback,
                    onTap = {
                        showControlsNow()
                    },
                    onDoubleTap = { forward ->

                        seekBy(if (forward) 10_000L else -10_000L)

                        seekFeedback = if (forward) 1 else -1
                        seekFeedbackKey++

                        showControls = false
                    },
                    onPlayPause = {
                        togglePlayback()
                    },
                    onPrevious = {
                        if (currentIndex > 0) {
                            onVideoChange(currentIndex - 1)
                        }
                    },
                    onNext = {
                        if (currentIndex < videos.lastIndex) {
                            onVideoChange(currentIndex + 1)
                        }
                    },
                    onSeek = {
                        player.seekTo(it)
                        position = it
                    },
                    onVolumeChange = {
                        volume = it
                        player.volume = it
                    },
                    onFullscreen = {
                        changeFullscreen(true)
                    },
                    onBack = {
                        goBack()
                    }
                )
            }

            Text(
                video.name.substringBeforeLast('.'),
                Modifier.padding(16.dp),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                video.path,
                Modifier.padding(horizontal = 16.dp),
                color = Muted,
                fontSize = 12.sp
            )
        }
    }
}

// ==========================================
// PLAYER VIDEO + CUSTOM CONTROLLER
// ==========================================

@Composable
private fun PlayerVideoContent(
    player: ExoPlayer,
    video: VideoItem,
    videos: List<VideoItem>,
    currentIndex: Int,
    showControls: Boolean,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    volume: Float,
    seekFeedback: Int,
    onTap: () -> Unit,
    onDoubleTap: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onFullscreen: () -> Unit,
    onBack: () -> Unit
) {

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        AndroidView(
            factory = { ctx ->

                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setKeepContentOnPlayerReset(true)
                }

            },
            update = {
                it.player = player
                it.useController = false
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(video.url) {

                    detectTapGestures(
                        onTap = {
                            onTap()
                        },
                        onDoubleTap = { offset ->

                            val forward = offset.x >= size.width / 2f
                            onDoubleTap(forward)
                        }
                    )
                }
        )

        if (seekFeedback != 0) {

            Box(
                Modifier
                    .align(
                        if (seekFeedback > 0)
                            Alignment.CenterEnd
                        else
                            Alignment.CenterStart
                    )
                    .padding(horizontal = 26.dp)
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Color(0xAA080B12)),
                contentAlignment = Alignment.Center
            ) {

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Icon(
                        if (seekFeedback > 0)
                            Icons.Default.FastForward
                        else
                            Icons.Default.FastRewind,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )

                    Text(
                        "10 seconds",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (showControls) {

            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x99000000),
                                Color.Transparent,
                                Color.Transparent,
                                Color(0xCC000000)
                            )
                        )
                    )
            ) {

                // উপরের অংশ: Back এবং ভিডিওর নাম
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(
                            horizontal = 15.dp,
                            vertical = 12.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    PremiumControlButton(
                        onClick = onBack,
                        size = 42.dp
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Text(
                        video.name.substringBeforeLast('.'),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // মাঝখানে Previous / Play-Pause / Next
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    PremiumControlButton(
                        onClick = onPrevious,
                        size = 58.dp,
                        enabled = currentIndex > 0
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Spacer(Modifier.width(34.dp))

                    PremiumControlButton(
                        onClick = onPlayPause,
                        size = 76.dp,
                        primary = true
                    ) {
                        Icon(
                            if (isPlaying)
                                Icons.Default.Pause
                            else
                                Icons.Default.PlayArrow,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(43.dp)
                        )
                    }

                    Spacer(Modifier.width(34.dp))

                    PremiumControlButton(
                        onClick = onNext,
                        size = 58.dp,
                        enabled = currentIndex < videos.lastIndex
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                // নিচের অংশ: Progress, Volume, Fullscreen
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            horizontal = 14.dp,
                            vertical = 10.dp
                        )
                ) {

                    Slider(
                        value = if (duration > 0L)
                            position.coerceIn(0L, duration).toFloat()
                        else 0f,
                        onValueChange = {
                            onSeek(it.toLong())
                        },
                        valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                        colors = SliderDefaults.colors(
                            thumbColor = Orange,
                            activeTrackColor = Orange,
                            inactiveTrackColor = Color(0x77FFFFFF)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Text(
                            formatTime(position),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            " / ${formatTime(duration)}",
                            color = Color(0xFFBFC6D2),
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.weight(1f))

                        Icon(
                            if (volume > 0f)
                                Icons.Default.VolumeUp
                            else
                                Icons.Default.VolumeOff,
                            null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(21.dp)
                                .clickable {
                                    onVolumeChange(
                                        if (volume > 0f) 0f else 1f
                                    )
                                }
                        )

                        Spacer(Modifier.width(7.dp))

                        Slider(
                            value = volume,
                            onValueChange = onVolumeChange,
                            valueRange = 0f..1f,
                            modifier = Modifier.width(75.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color(0x66FFFFFF)
                            )
                        )

                        Spacer(Modifier.width(8.dp))

                        PremiumControlButton(
                            onClick = onFullscreen,
                            size = 40.dp
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// PREMIUM ANIMATED BUTTON
// ==========================================

@Composable
private fun PremiumControlButton(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    primary: Boolean = false,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {

    val interaction = remember {
        MutableInteractionSource()
    }

    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        label = "buttonScale"
    )

    val background =
        if (primary)
            Orange
        else
            Color(0xCC202735)

    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (enabled) background
                else Color(0x66505A69)
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = ripple(
                    bounded = true,
                    color = Color.White
                ),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {

        Box(
            Modifier
                .fillMaxSize()
                .padding(1.dp)
                .clip(CircleShape)
                .background(
                    if (pressed)
                        Color(0x33FFFFFF)
                    else
                        Color.Transparent
                )
        )

        Box(
            Modifier
                .fillMaxSize()
                .padding(2.dp)
                .then(
                    Modifier
                        .size(size * scale)
                ),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

// ==========================================
// TIME FORMAT
// ==========================================

private fun formatTime(milliseconds: Long): String {

    if (milliseconds < 0L || milliseconds == Long.MAX_VALUE) {
        return "00:00"
    }

    val totalSeconds = milliseconds / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(
            "%d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    } else {
        String.format(
            "%02d:%02d",
            minutes,
            seconds
        )
    }
}
