package app.unonime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val Navy = Color(0xFF0B1622)
private val Panel = Color(0xFF151F2E)
private val Panel2 = Color(0xFF1B2838)
private val Blue = Color(0xFF3DB4F2)
private val Muted = Color(0xFF9FADBD)
private val White = Color(0xFFF0F4F8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptAuth(intent)
        setContent { UnonimeApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptAuth(intent)
        recreate()
    }

    private fun acceptAuth(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "unonime" && uri.host == "auth") {
            val token = uri.fragment?.split("&")?.firstOrNull { it.startsWith("access_token=") }?.substringAfter("=")
            if (!token.isNullOrBlank()) {
                getSharedPreferences("unonime", MODE_PRIVATE).edit()
                    .putString("token", Uri.decode(token)).apply()
            }
        }
    }
}

private data class Anime(
    val id: Int,
    val title: String,
    val cover: String,
    val banner: String?,
    val score: Int?,
    val episodes: Int?,
    val status: String?,
    val format: String?,
    val description: String?,
    val genres: List<String>,
    val year: Int?,
    val progress: Int? = null
)

private object AniListApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val fields = """
        id title { userPreferred english romaji } coverImage { extraLarge large } bannerImage
        averageScore episodes status format description(asHtml:false) genres startDate { year }
    """.trimIndent()

    suspend fun gql(query: String, vars: JSONObject = JSONObject(), token: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("query", query).put("variables", vars).toString()
            val request = Request.Builder().url("https://graphql.anilist.co")
                .post(body.toRequestBody(jsonType)).header("Accept", "application/json")
                .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer " + token) }
                .build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string() ?: throw IOException("Empty response from AniList")
                val json = JSONObject(raw)
                if (!response.isSuccessful || json.has("errors")) {
                    val message = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                    throw IOException(message?.takeIf { it.isNotBlank() } ?: "AniList request failed (" + response.code + ")")
                }
                json.optJSONObject("data") ?: throw IOException("Invalid AniList response")
            }
        }

    private fun parse(o: JSONObject): Anime {
        val title = o.optJSONObject("title")
        val cover = o.optJSONObject("coverImage")
        val date = o.optJSONObject("startDate")
        val preferred = title?.optString("userPreferred")?.takeIf { it.isNotBlank() && it != "null" }
        val english = title?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
        val romaji = title?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
        val large = cover?.optString("extraLarge")?.takeIf { it.startsWith("http") }
        val small = cover?.optString("large")?.takeIf { it.startsWith("http") }
        val genresJson = o.optJSONArray("genres")
        val genres = if (genresJson == null) emptyList() else
            (0 until genresJson.length()).map { genresJson.optString(it) }
        val score = o.optInt("averageScore").takeIf { it > 0 }
        val episodes = o.optInt("episodes").takeIf { it > 0 }
        val year = date?.optInt("year")?.takeIf { it > 0 }
        return Anime(
            o.optInt("id"), preferred ?: english ?: romaji ?: "Unknown title",
            large ?: small ?: "", o.optString("bannerImage").takeIf { it.startsWith("http") },
            score, episodes, o.optString("status").takeIf { it != "null" },
            o.optString("format").takeIf { it != "null" },
            o.optString("description").takeIf { it != "null" }, genres, year
        )
    }

    private fun parsePage(data: JSONObject, key: String = "media"): List<Anime> {
        val arr = data.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parse) }
    }

    suspend fun home(): Triple<List<Anime>, List<Anime>, List<Anime>> {
        val query = """
            query {
              trending: Page(page:1,perPage:12) { media(type:ANIME,isAdult:false,sort:TRENDING_DESC) { $fields } }
              popular: Page(page:1,perPage:12) { media(type:ANIME,isAdult:false,sort:POPULARITY_DESC) { $fields } }
              top: Page(page:1,perPage:12) { media(type:ANIME,isAdult:false,sort:SCORE_DESC) { $fields } }
            }
        """.trimIndent()
        val d = gql(query)
        return Triple(
            parsePage(d.optJSONObject("trending") ?: JSONObject()),
            parsePage(d.optJSONObject("popular") ?: JSONObject()),
            parsePage(d.optJSONObject("top") ?: JSONObject())
        )
    }

    suspend fun search(term: String): List<Anime> {
        val query = """
            query (${'$'}search:String!) {
              Page(page:1,perPage:24) {
                media(search:${'$'}search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query (${'$'}id:Int!) {
              MediaListCollection(userId:${'$'}id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
}search:String!) {
              Page(page:1,perPage:24) {
                media(search:$search,type:ANIME,isAdult:false,sort:SEARCH_MATCH) { $fields }
              }
            }
        """.trimIndent()
        val d = gql(query, JSONObject().put("search", term))
        return parsePage(d.optJSONObject("Page") ?: JSONObject())
    }

    suspend fun viewer(token: String): Pair<String, Int> {
        val d = gql("query { Viewer { id name } }", token = token).getJSONObject("Viewer")
        return Pair(d.optString("name"), d.optInt("id"))
    }

    suspend fun myList(token: String): List<Anime> {
        val viewer = viewer(token)
        val query = """
            query ($id:Int!) {
              MediaListCollection(userId:$id,type:ANIME,status_in:[CURRENT,PLANNING,COMPLETED,PAUSED,DROPPED]) {
                lists { entries { progress media { $fields } } }
              }
            }
        """.trimIndent()
        val data = gql(query, JSONObject().put("id", viewer.second), token)
        val lists = data.optJSONObject("MediaListCollection")?.optJSONArray("lists") ?: return emptyList()
        val result = mutableListOf<Anime>()
        for (i in 0 until lists.length()) {
            val entries = lists.optJSONObject(i)?.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val media = entry.optJSONObject("media") ?: continue
                result.add(parse(media).copy(progress = entry.optInt("progress")))
            }
        }
        return result
    }

    suspend fun addCurrent(id: Int, token: String) {
        val query = """
            mutation ($id:Int!) {
              SaveMediaListEntry(mediaId:$id,status:CURRENT,progress:0) { id status }
            }
        """.trimIndent()
        gql(query, JSONObject().put("id", id), token)
    }
}

@Composable
private fun UnonimeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("unonime", android.content.Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var clientId by remember { mutableStateOf(prefs.getString("client_id", "") ?: "") }
    var tab by remember { mutableStateOf("Home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var trending by remember { mutableStateOf(emptyList<Anime>()) }
    var popular by remember { mutableStateOf(emptyList<Anime>()) }
    var top by remember { mutableStateOf(emptyList<Anime>()) }
    var results by remember { mutableStateOf(emptyList<Anime>()) }
    var list by remember { mutableStateOf(emptyList<Anime>()) }
    var query by remember { mutableStateOf("") }
    var username by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshHome() {
        scope.launch {
            loading = true; error = null
            try {
                val data = AniListApi.home()
                trending = data.first; popular = data.second; top = data.third
            } catch (e: Exception) { error = e.message ?: "Could not load AniList" }
            loading = false
        }
    }
    fun doSearch() {
        if (query.isBlank()) { results = emptyList(); return }
        scope.launch {
            loading = true; error = null
            try { results = AniListApi.search(query.trim()) }
            catch (e: Exception) { error = e.message ?: "Search failed" }
            loading = false
        }
    }
    fun refreshList() {
        val auth = token
        if (auth.isNullOrBlank()) { message = "Connect your AniList account from Profile to view your list."; return }
        scope.launch {
            loading = true; error = null
            try { list = AniListApi.myList(auth) }
            catch (e: Exception) { error = e.message ?: "Could not load your list" }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHome()
        if (!token.isNullOrBlank()) {
            try { username = AniListApi.viewer(token!!).first } catch (_: Exception) { }
        }
    }
    BackHandler(enabled = selected != null) { selected = null }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Blue, background = Navy, surface = Panel,
        onPrimary = Navy, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Navy,
            bottomBar = {
                if (selected == null) {
                    NavigationBar(containerColor = Panel, contentColor = White, tonalElevation = 0.dp) {
                        val nav = listOf(
                            Triple("Home", Icons.Filled.Home, "Home"),
                            Triple("Search", Icons.Filled.Search, "Search"),
                            Triple("My List", Icons.Filled.Bookmark, "My List"),
                            Triple("Profile", Icons.Filled.Person, "Profile")
                        )
                        nav.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item.third,
                                onClick = {
                                    tab = item.third
                                    if (tab == "My List") refreshList()
                                    if (tab == "Home" && trending.isEmpty()) refreshHome()
                                },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = .14f),
                                    unselectedIconColor = Muted, unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selected != null) {
                    DetailScreen(selected!!, { selected = null }, {
                        val auth = token
                        if (auth.isNullOrBlank()) message = "Connect your AniList account in Profile first."
                        else scope.launch {
                            try {
                                AniListApi.addCurrent(selected!!.id, auth)
                                message = "Added to Currently Watching."
                                if (tab == "My List") refreshList()
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                } else {
                    AppHeader(username, { tab = "Home" }, { tab = "Profile" })
                    when (tab) {
                        "Home" -> HomeScreen(trending, popular, top, loading, error, ::refreshHome, { selected = it }, { tab = "Search" })
                        "Search" -> SearchScreen(query, { query = it }, results, loading, error, ::doSearch, { selected = it })
                        "My List" -> MyListScreen(list, loading, error, ::refreshList, { selected = it })
                        else -> ProfileScreen(
                            username, token != null, clientId,
                            { clientId = it; prefs.edit().putString("client_id", it).apply() },
                            {
                                if (clientId.isBlank()) message = "Enter your AniList Client ID first."
                                else {
                                    val authUrl = Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
                                        .appendQueryParameter("client_id", clientId.trim())
                                        .appendQueryParameter("redirect_uri", "unonime://auth")
                                        .appendQueryParameter("response_type", "token").build()
                                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl))
                                }
                            },
                            {
                                prefs.edit().remove("token").apply()
                                token = null; username = null; message = "Signed out."
                            }
                        )
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = Panel)
        if (message != null) AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
            title = { Text("Unonime") }, text = { Text(message!!) },
            containerColor = Panel, titleContentColor = White, textContentColor = Muted
        )
    }
}

@Composable
private fun AppHeader(name: String?, onHome: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onHome() }) {
            Text("UNONIME", color = Blue, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Text("anime, tracked your way", color = Muted, fontSize = 10.sp)
        }
        IconButton(onClick = onProfile) {
            if (name.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, tint = Muted, modifier = Modifier.size(30.dp))
            else Box(Modifier.size(32.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Navy, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    trending: List<Anime>, popular: List<Anime>, top: List<Anime>,
    loading: Boolean, error: String?, refresh: () -> Unit,
    select: (Anime) -> Unit, search: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Discover", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Find your next favourite anime.", color = Muted, fontSize = 13.sp)
                }
                IconButton(onClick = search) { Icon(Icons.Filled.Search, "Search", tint = Blue) }
                IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, "Refresh", tint = Muted) }
            }
        }
        if (error != null) item { ErrorCard(error, refresh) }
        if (trending.isNotEmpty()) {
            item { HeroCard(trending.first(), select) }
            item { AnimeSection("Trending Now", "Popular this week", trending, select) }
        }
        if (popular.isNotEmpty()) item { AnimeSection("Popular Anime", "Most watched by the community", popular, select) }
        if (top.isNotEmpty()) item { AnimeSection("All-Time Favourites", "Highest rated anime", top, select) }
        if (loading && trending.isEmpty()) item { LoadingView() }
        item { Text("Anime data provided by AniList", Modifier.padding(18.dp), color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun HeroCard(anime: Anime, select: (Anime) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(210.dp).clip(RoundedCornerShape(13.dp)).clickable { select(anime) }) {
        AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = .97f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text("TRENDING #1", color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(anime.title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—") + "  •  " + (anime.score?.toString() ?: "—") + "%", color = White.copy(alpha = .8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnimeSection(title: String, subtitle: String, data: List<Anime>, select: (Anime) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(data, key = { it.id }) { AnimeTile(it, select) }
        }
    }
}

@Composable
private fun AnimeTile(anime: Anime, select: (Anime) -> Unit) {
    Column(Modifier.width(116.dp).clickable { select(anime) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(7.dp)).background(Panel2)) {
            AsyncImage(anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (anime.score != null) {
                Text("★ " + anime.score, Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Navy.copy(alpha = .9f)).padding(horizontal = 5.dp, vertical = 3.dp), color = Color(0xFFFFD166), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(anime.title, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        Text(anime.format ?: "ANIME", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun SearchScreen(
    query: String, change: (String) -> Unit, results: List<Anime>,
    loading: Boolean, error: String?, search: () -> Unit, select: (Anime) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("Search Anime", Modifier.padding(start = 18.dp, top = 18.dp, bottom = 12.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(9.dp)).background(Panel2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            BasicTextField(
                value = query, onValueChange = change, modifier = Modifier.weight(1f).padding(12.dp),
                singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search anime titles...", color = Muted, fontSize = 14.sp)
                    inner()
                }
            )
            IconButton(onClick = search) { Icon(Icons.Filled.ArrowForward, "Search", tint = Blue) }
        }
        if (error != null) ErrorCard(error, search)
        if (results.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.TravelExplore, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "Search the AniList catalogue" else "No results yet", color = Muted)
                Text("Anime titles, genres and more", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun MyListScreen(data: List<Anime>, loading: Boolean, error: String?, refresh: () -> Unit, select: (Anime) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My Anime List", Modifier.weight(1f), color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = refresh) { Icon(Icons.Filled.Refresh, null, tint = Blue) }
        }
        if (error != null) ErrorCard(error, refresh)
        if (data.isEmpty() && !loading) {
            Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmarks, null, tint = Muted, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(10.dp))
                Text("Your list will appear here", color = Muted)
                Text("Sign in to sync your AniList entries.", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data, key = { it.id }) { AnimeRow(it, select) }
            }
        }
    }
}

@Composable
private fun AnimeRow(anime: Anime, select: (Anime) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Panel).clickable { select(anime) }.padding(9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(anime.cover, null, Modifier.width(68.dp).height(94.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(anime.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "—"), color = Muted, fontSize = 10.sp)
            Text((anime.episodes?.toString() ?: "TBA") + " episodes  •  " + (anime.score?.toString() ?: "—") + "%", color = Blue, fontSize = 10.sp)
            if (anime.progress != null) Text("Progress: " + anime.progress, color = Muted, fontSize = 10.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun DetailScreen(anime: Anime, back: () -> Unit, add: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null, tint = White) }
            Text("Anime Details", color = White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(185.dp).background(Panel2)) {
                    AsyncImage(anime.banner ?: anime.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy))))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).offset(y = (-42).dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(anime.cover, null, Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(8.dp)).background(Panel2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(anime.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text((anime.format ?: "ANIME") + "  •  " + (anime.year?.toString() ?: "TBA"), color = Muted, fontSize = 11.sp)
                        Text(anime.score?.let { "★ " + it + " / 100" } ?: "Not yet rated", color = Color(0xFFFFD166), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp).offset(y = (-25).dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = add, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy), shape = RoundedCornerShape(7.dp)) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add to My List", fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        anime.genres.forEach { Text(it, Modifier.clip(RoundedCornerShape(20.dp)).background(Panel2).padding(horizontal = 10.dp, vertical = 6.dp), color = Muted, fontSize = 10.sp) }
                    }
                    Text("Overview", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(Html.fromHtml(anime.description ?: "No synopsis available.", Html.FROM_HTML_MODE_COMPACT).toString(), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    HorizontalDivider(color = Panel2)
                    Text("Information", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    InfoLine("Format", anime.format ?: "Unknown")
                    InfoLine("Episodes", anime.episodes?.toString() ?: "TBA")
                    InfoLine("Status", anime.status?.replace("_", " ") ?: "Unknown")
                    InfoLine("Year", anime.year?.toString() ?: "TBA")
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileScreen(
    name: String?, signedIn: Boolean, clientId: String,
    changeClientId: (String) -> Unit, login: () -> Unit, logout: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Profile", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Blue, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name ?: if (signedIn) "AniList connected" else "Not signed in", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(if (signedIn) "Your account is connected" else "Connect to sync your anime list", color = Muted, fontSize = 11.sp)
            }
        }
        Text("Connect AniList", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Create an app in AniList Developer Settings and set its Redirect URL to exactly: unonime://auth", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(
            value = clientId, onValueChange = changeClientId, modifier = Modifier.fillMaxWidth(),
            label = { Text("AniList Client ID") }, placeholder = { Text("Enter your Client ID") }, singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Blue,
                unfocusedBorderColor = Muted, focusedLabelColor = Blue, unfocusedLabelColor = Muted, cursorColor = Blue
            )
        )
        if (signedIn) {
            Button(onClick = logout, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = White)) {
                Icon(Icons.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
            }
        } else {
            Button(onClick = login, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Navy)) {
                Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Login with AniList", fontWeight = FontWeight.Bold)
            }
        }
        Text("About Unonime", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("A native Android AniList client. No WebView. Anime data and account features use the official AniList GraphQL API.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Unonime • Version 0.2.0", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ErrorCard(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CloudOff, null, tint = Color(0xFFFF8A80))
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
        TextButton(onClick = retry) { Text("Retry", color = Blue) }
    }
}

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(10.dp))
        Text("Loading AniList...", color = Muted, fontSize = 12.sp)
    }
}
