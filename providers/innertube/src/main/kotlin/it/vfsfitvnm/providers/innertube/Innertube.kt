package it.vfsfitvnm.providers.innertube

import it.vfsfitvnm.providers.innertube.models.Context
import it.vfsfitvnm.providers.innertube.models.MusicNavigationButtonRenderer
import it.vfsfitvnm.providers.innertube.models.NavigationEndpoint
import it.vfsfitvnm.providers.innertube.models.Runs
import it.vfsfitvnm.providers.innertube.models.Thumbnail
import it.vfsfitvnm.providers.utils.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.compression.brotli
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.host
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

object Innertube {
    private var javascriptChallenge: JavaScriptChallenge? = null
    private var lastChallengeUpdate = 0L
    private const val CHALLENGE_CACHE_DURATION = 30 * 60 * 1000L // 30 minutes
    private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private val OriginInterceptor = createClientPlugin("OriginInterceptor") {
        client.sendPipeline.intercept(HttpSendPipeline.State) {
            context.headers {
                val host = when (context.host) {
                    "youtubei.googleapis.com" -> "www.youtube.com"
                    "music.youtube.com" -> "music.youtube.com"
                    else -> context.host
                }
                val origin = "${context.url.protocol.name}://$host"
                set("origin", origin)
                set("referer", "$origin/")

            }
        }
    }

    val logger: Logger = LoggerFactory.getLogger(Innertube::class.java)
    val baseClient = HttpClient(OkHttp) {
        expectSuccess = true

        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                val ex = cause as? ResponseException ?: return@handleResponseExceptionWithRequest
                val code = ex.response.status.value

                // Log rate limiting and auth errors
                when (code) {
                    403 -> logger.warn("Access forbidden (403) - possible rate limiting or auth issue")
                    429 -> logger.warn("Too many requests (429) - rate limited")
                    else -> if (code !in (100..<600)) throw InvalidHttpCodeException(code)
                }
            }
        }

        install(ContentNegotiation) {
            json(json)
        }

        install(ContentEncoding) {
            brotli(1.0f)
            gzip(0.9f)
            deflate(0.8f)
        }

        install(Logging) {
            level = LogLevel.INFO
        }

        install(OriginInterceptor)

        engine {
            config {
                connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    val client = baseClient.config {
        defaultRequest {
            url(scheme = "https", host = "music.youtube.com") {
                contentType(ContentType.Application.Json)
                headers {
                    // Use consistent working API key
                    set("X-Goog-Api-Key", API_KEY)
                    set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    set("Accept", "application/json")
                    set("Accept-Language", "en-US,en;q=0.9")
                    set("Accept-Encoding", "gzip, deflate, br")
                }
                parameters {
                    set("prettyPrint", "false")
                    set("key", API_KEY)
                }
            }
        }
    }

    @Suppress("all")
    private val regexes = listOf(
        // New, more reliable patterns translated from yt-dlp
        """\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\(""".toRegex(),
        """(?:\b|[^a-zA-Z0-9$])([a-zA-Z0-9$]{2,})\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\)""".toRegex(),
        """([a-zA-Z0-9$]+)\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\);""".toRegex(),
        // Original patterns (kept as fallbacks)
        """\bm=([a-zA-Z0-9$]{2,})\(decodeURIComponent\(h\.s\)\)""".toRegex(),
        """\bc&&\(c=([a-zA-Z0-9$]{2,})\(decodeURIComponent\(c\)\)""".toRegex()
    )

    private suspend fun getJavaScriptChallenge(context: Context): JavaScriptChallenge? {
        val currentTime = System.currentTimeMillis()

        // Check if we need to refresh the challenge
        if (javascriptChallenge != null &&
            (currentTime - lastChallengeUpdate) < CHALLENGE_CACHE_DURATION) {
            return javascriptChallenge
        }

        return try {
            context.client.getConfiguration()
            val jsUrl = context.client.jsUrl ?: return null

            // Remove artificial delay - it's suspicious
            // delay(Random.nextLong(100, 500)) // REMOVED

            val sourceFile = baseClient
                .get("${context.client.root}$jsUrl") {
                    context.apply()
                    // Simplified headers - less suspicious
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    header("Referer", "${context.client.root}/")
                    // Remove Accept header manipulation
                }
                .bodyAsText()

            val timestamp = "(?:signatureTimestamp|sts):(\\d{5})".toRegex()
                .find(sourceFile)
                ?.groups
                ?.get(1)
                ?.value
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null

            val functionName = regexes.firstNotNullOfOrNull { regex ->
                regex
                    .find(sourceFile)
                    ?.groups
                    ?.get(1)
                    ?.value
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } ?: return null

            JavaScriptChallenge(
                source = sourceFile
                    .replace("document.location.hostname", "\"youtube.com\"")
                    .replace("window.location.hostname", "\"youtube.com\"")
                    .replace("XMLHttpRequest.prototype.fetch", "\"aaa\""),
                timestamp = timestamp,
                functionName = functionName
            ).also {
                javascriptChallenge = it
                lastChallengeUpdate = currentTime
                logger.info("JavaScript challenge updated successfully")
            }
        } catch (e: Exception) {
            logger.error("Failed to get JavaScript challenge", e)
            null
        }
    }

    suspend fun getSignatureTimestamp(context: Context): String? = runCatchingCancellable {
        getJavaScriptChallenge(context)?.timestamp
    }?.onFailure {
        logger.error("Failed to get signature timestamp", it)
        it.printStackTrace()
    }?.getOrNull()

    private const val BASE = "/youtubei/v1"
    internal const val BROWSE = "$BASE/browse"
    internal const val NEXT = "$BASE/next"
    internal const val PLAYER = "https://youtubei.googleapis.com/youtubei/v1/player"
    internal const val PLAYER_MUSIC = "$BASE/player"
    internal const val QUEUE = "$BASE/music/get_queue"
    internal const val SEARCH = "$BASE/search"
    internal const val SEARCH_SUGGESTIONS = "$BASE/music/get_search_suggestions"
    internal const val MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_MASK =
        "musicResponsiveListItemRenderer(flexColumns,fixedColumns,thumbnail,navigationEndpoint,badges)"
    internal const val MUSIC_TWO_ROW_ITEM_RENDERER_MASK =
        "musicTwoRowItemRenderer(thumbnailRenderer,title,subtitle,navigationEndpoint)"

    @Suppress("MaximumLineLength")
    internal const val PLAYLIST_PANEL_VIDEO_RENDERER_MASK =
        "playlistPanelVideoRenderer(title,navigationEndpoint,longBylineText,shortBylineText,thumbnail,lengthText,badges)"

    internal fun HttpRequestBuilder.mask(value: String = "*") =
        header("X-Goog-FieldMask", value)

    @Serializable
    data class Info<T : NavigationEndpoint.Endpoint>(
        val name: String?,
        val endpoint: T?
    ) {
        @Suppress("UNCHECKED_CAST")
        constructor(run: Runs.Run) : this(
            name = run.text,
            endpoint = run.navigationEndpoint?.endpoint as T?
        )
    }

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val Song = SearchFilter("EgWKAQIIAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val Video = SearchFilter("EgWKAQIQAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val Album = SearchFilter("EgWKAQIYAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val Artist = SearchFilter("EgWKAQIgAWoOEAMQBBAJEAoQBRAQEBU%3D")
            val CommunityPlaylist = SearchFilter("EgeKAQQoAEABag4QAxAEEAkQChAFEBAQFQ%3D%3D")
        }
    }

    sealed class Item {
        abstract val thumbnail: Thumbnail?
        abstract val key: String
    }

    @Serializable
    data class SongItem(
        val info: Info<NavigationEndpoint.Endpoint.Watch>?,
        val authors: List<Info<NavigationEndpoint.Endpoint.Browse>>?,
        val album: Info<NavigationEndpoint.Endpoint.Browse>?,
        val durationText: String?,
        val explicit: Boolean,
        override val thumbnail: Thumbnail?
    ) : Item() {
        override val key get() = info!!.endpoint!!.videoId!!

        companion object
    }

    data class VideoItem(
        val info: Info<NavigationEndpoint.Endpoint.Watch>?,
        val authors: List<Info<NavigationEndpoint.Endpoint.Browse>>?,
        val viewsText: String?,
        val durationText: String?,
        override val thumbnail: Thumbnail?
    ) : Item() {
        override val key get() = info!!.endpoint!!.videoId!!

        val isOfficialMusicVideo: Boolean
            get() = info
                ?.endpoint
                ?.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig
                ?.musicVideoType == "MUSIC_VIDEO_TYPE_OMV"

        companion object
    }

    @Serializable
    data class AlbumItem(
        val info: Info<NavigationEndpoint.Endpoint.Browse>?,
        val authors: List<Info<NavigationEndpoint.Endpoint.Browse>>?,
        val year: String?,
        override val thumbnail: Thumbnail?
    ) : Item() {
        override val key get() = info!!.endpoint!!.browseId!!

        companion object
    }

    @Serializable
    data class ArtistItem(
        val info: Info<NavigationEndpoint.Endpoint.Browse>?,
        val subscribersCountText: String?,
        override val thumbnail: Thumbnail?
    ) : Item() {
        override val key get() = info!!.endpoint!!.browseId!!

        companion object
    }

    @Serializable
    data class PlaylistItem(
        val info: Info<NavigationEndpoint.Endpoint.Browse>?,
        val channel: Info<NavigationEndpoint.Endpoint.Browse>?,
        val songCount: Int?,
        override val thumbnail: Thumbnail?
    ) : Item() {
        override val key get() = info!!.endpoint!!.browseId!!

        companion object
    }

    data class ArtistPage(
        val name: String?,
        val description: String?,
        val thumbnail: Thumbnail?,
        val shuffleEndpoint: NavigationEndpoint.Endpoint.Watch?,
        val radioEndpoint: NavigationEndpoint.Endpoint.Watch?,
        val songs: List<SongItem>?,
        val songsEndpoint: NavigationEndpoint.Endpoint.Browse?,
        val albums: List<AlbumItem>?,
        val albumsEndpoint: NavigationEndpoint.Endpoint.Browse?,
        val singles: List<AlbumItem>?,
        val singlesEndpoint: NavigationEndpoint.Endpoint.Browse?,
        val subscribersCountText: String?
    )

    data class PlaylistOrAlbumPage(
        val title: String?,
        val description: String?,
        val authors: List<Info<NavigationEndpoint.Endpoint.Browse>>?,
        val year: String?,
        val thumbnail: Thumbnail?,
        val url: String?,
        val songsPage: ItemsPage<SongItem>?,
        val otherVersions: List<AlbumItem>?,
        val otherInfo: String?
    )

    data class NextPage(
        val itemsPage: ItemsPage<SongItem>?,
        val playlistId: String?,
        val params: String? = null,
        val playlistSetVideoId: String? = null
    )

    @Serializable
    data class RelatedPage(
        val songs: List<SongItem>? = null,
        val playlists: List<PlaylistItem>? = null,
        val albums: List<AlbumItem>? = null,
        val artists: List<ArtistItem>? = null
    )

    data class DiscoverPage(
        val newReleaseAlbums: List<AlbumItem>,
        val moods: List<Mood.Item>,
        val trending: Trending
    ) {
        data class Trending(
            val songs: List<SongItem>,
            val endpoint: NavigationEndpoint.Endpoint.Browse?
        )
    }

    data class Mood(
        val title: String,
        val items: List<Item>
    ) {
        data class Item(
            val title: String,
            val stripeColor: Long,
            val endpoint: NavigationEndpoint.Endpoint.Browse
        ) : Innertube.Item() {
            override val thumbnail get() = null
            override val key
                get() = "${endpoint.browseId.orEmpty()}${endpoint.params?.let { "/$it" }.orEmpty()}"

            companion object
        }
    }

    fun MusicNavigationButtonRenderer.toMood(): Mood.Item? {
        return Mood.Item(
            title = buttonText.runs.firstOrNull()?.text ?: return null,
            stripeColor = solid?.leftStripeColor ?: return null,
            endpoint = clickCommand.browseEndpoint ?: return null
        )
    }

    data class ItemsPage<T : Item>(
        val items: List<T>?,
        val continuation: String?
    )
}

data class InvalidHttpCodeException(val code: Int) :
    IllegalStateException("Invalid http code received: $code")
