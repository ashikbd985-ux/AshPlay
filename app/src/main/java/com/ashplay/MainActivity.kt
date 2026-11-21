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
