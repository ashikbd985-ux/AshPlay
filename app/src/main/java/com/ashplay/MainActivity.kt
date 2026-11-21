
package com.ashplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.TrafficStats
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
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

// সার্ভারে আগে থেকে থাকা থাম্বনেইল ডাউনলোড
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

// ভিডিও থেকে একটি ফ্রেম তৈরি
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

// তৈরি করা JPEG থাম্বনেইল PHP সার্ভারে আপলোড
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

// সার্ভার ক্যাশ → না থাকলে তৈরি → আপলোড
private suspend fun getOrCreateThumbnail(
    video: VideoItem
): Bitmap? = withContext(Dispatchers.IO) {

    // প্রথমে সার্ভারের ক্যাশ পরীক্ষা
    val serverBitmap = downloadServerThumbnail(video.thumbnailUrl)

    if (serverBitmap != null) {
        return@withContext serverBitmap
    }

    // সার্ভারে না থাকলে ভিডিও থেকে তৈরি
    val newBitmap = createVideoThumbnail(video.url)
        ?: return@withContext null

    // সার্ভারে আপলোড
    uploadThumbnail(
        API_URL,
        video.path,
        newBitmap
    )

    // আপলোড সফল হোক বা না হোক,
    // বর্তমান সেশনে তৈরি ছবিটি দেখানো হবে
    newBitmap
}

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

                VideoPlayerScreen(
                    selectedVideo!!,
                    onBack = {
                        selectedVideo = null
                    }
                )

            } else {

                Column(Modifier.fillMaxSize()) {

                    AshPlayHeader(
                        txBytes,
                        onlineCount
                    )

                    SearchBar(
                        query,
                        { query = it },
                        { }
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

                            val feed = remember(categories) {
                                categories.flatMap { cat ->
                                    cat.videos.map { video ->
                                        FeedItem(cat.name, video)
                                    }
                                }.shuffled()
                            }

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

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit
) {

    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 7.dp,
                bottom = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Row(
            Modifier
                .weight(1f)
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Panel)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                Icons.Default.Search,
                null,
                tint = Color(0xFFB8C3D4),
                modifier = Modifier.size(25.dp)
            )

            Spacer(Modifier.width(12.dp))

            Box(Modifier.weight(1f)) {

                if (query.isEmpty()) {

                    Text(
                        "Search videos...",
                        color = Color(0xFF8E9AAF),
                        fontSize = 16.sp
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 16.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Surface(
            onClick = onSearch,
            modifier = Modifier.size(
                width = 62.dp,
                height = 54.dp
            ),
            shape = RoundedCornerShape(16.dp),
            color = Orange
        ) {

            Box(contentAlignment = Alignment.Center) {

                Icon(
                    Icons.Default.Search,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(29.dp)
                )
            }
        }
    }
}

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

@Composable
private fun VideoPlayerScreen(
    video: VideoItem,
    onBack: () -> Unit
) {

    val context = LocalContext.current

    // ২ সেকেন্ড থেকে ৮ সেকেন্ড পর্যন্ত Playback Buffer-এর লক্ষ্য
    // এটি Media3-এর সময়ভিত্তিক Buffer নিয়ন্ত্রণ; নির্দিষ্ট HTTP Chunk নয়।
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000, // Minimum buffer
                8_000, // Maximum target buffer
                2_000, // Initial playback buffer
                2_000  // Rebuffer playback buffer
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

    DisposableEffect(player) {

        onDispose {
            player.release()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {

        Text(
            "←  ফিরে যান",
            Modifier
                .clickable(onClick = onBack)
                .padding(16.dp),
            color = Color.White,
            fontSize = 17.sp
        )

        AndroidView(
            factory = { ctx ->

                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }

            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

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
