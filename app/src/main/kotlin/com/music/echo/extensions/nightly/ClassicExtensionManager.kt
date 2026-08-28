package iad1tya.echo.music.extensions.nightly

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import com.music.innertube.pages.HomePage
import com.music.innertube.pages.SearchSummary
import com.music.innertube.pages.SearchSummaryPage
import dalvik.system.DexClassLoader
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.LyricsExtension
import dev.brahmkshatriya.echo.common.MiscExtension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.TrackerExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.SettingsChangeListenerClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider
import dev.brahmkshatriya.echo.common.providers.MessageFlowProvider
import dev.brahmkshatriya.echo.common.providers.MetadataProvider
import dev.brahmkshatriya.echo.common.providers.MiscExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.NetworkConnectionProvider
import dev.brahmkshatriya.echo.common.providers.LyricsExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.TrackerExtensionsProvider
import dev.brahmkshatriya.echo.common.providers.WebViewClientProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import iad1tya.echo.music.extensions.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Nightly-compatible extension backend for the classic application.
 *
 * This class deliberately exposes classic-app models at its boundary. Nightly fragments,
 * navigation, player UI, themes, and resources are not used by the classic application.
 */
class ClassicExtensionManager private constructor(private val context: Context) {

    data class Entry(
        val file: File,
        val metadata: Metadata?,
        val client: ExtensionClient?,
        val error: String? = null,
    ) {
        val id: String get() = metadata?.id ?: file.nameWithoutExtension
        val isMusic: Boolean get() = metadata?.type == ExtensionType.MUSIC
    }

    data class ResolvedStream(val source: Streamable.Source) {
        val uri: Uri
            get() = when (source) {
                is Streamable.Source.Http -> Uri.parse(source.request.url)
                is Streamable.Source.Raw -> Uri.Builder()
                    .scheme("echo-raw")
                    .authority(source.id.hashCode().toUInt().toString(16))
                    .build()
            }

        val headers: Map<String, String>
            get() = (source as? Streamable.Source.Http)?.request?.headers.orEmpty()

        val mimeType: String?
            get() = when ((source as? Streamable.Source.Http)?.type) {
                Streamable.SourceType.HLS -> MimeTypes.APPLICATION_M3U8
                Streamable.SourceType.DASH -> MimeTypes.APPLICATION_MPD
                else -> null
            }
    }

    private data class CachedResolvedStream(
        val stream: ResolvedStream,
        val createdAt: Long,
    )

    private data class RuntimeSpec(
        val file: File,
        val metadata: Metadata,
        val injectable: Injectable<ExtensionClient>,
    )

    /** The same four typed extension collections exposed by Echo Nightly's loader. */
    private data class RuntimeCatalog(
        val music: List<MusicExtension>,
        val tracker: List<TrackerExtension>,
        val lyrics: List<LyricsExtension>,
        val misc: List<MiscExtension>,
    )

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val tracks = context.getSharedPreferences(TRACKS, Context.MODE_PRIVATE)
    private val extensionDirectory = File(context.filesDir, "extensions").apply { mkdirs() }
    private val mutex = Mutex()
    private val resolvedStreams = ConcurrentHashMap<String, CachedResolvedStream>()
    private val classicTrackMappings = ConcurrentHashMap<String, String>()
    val messages = MutableSharedFlow<Message>(extraBufferCapacity = 16)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "classicType"
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _selectedMusicExtensionId = MutableStateFlow(
        preferences.getString(SELECTED_MUSIC_EXTENSION, null)
    )
    val selectedMusicExtensionId: StateFlow<String?> = _selectedMusicExtensionId.asStateFlow()

    private val _loginUsers = MutableStateFlow<Map<String, User?>>(emptyMap())
    val loginUsers: StateFlow<Map<String, User?>> = _loginUsers.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    private var catalog = RuntimeCatalog(emptyList(), emptyList(), emptyList(), emptyList())

    suspend fun reload() = withContext(Dispatchers.IO) {
        val selectedClient = mutex.withLock {
            resolvedStreams.clear()
            classicTrackMappings.clear()
            val sourceFiles = extensionDirectory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            val specs = sourceFiles.map { file ->
                file to runCatching { createRuntimeSpec(file) }
            }
            val newCatalog = createCatalog(specs.mapNotNull { it.second.getOrNull() })
            catalog = newCatalog

            // Nightly injects the complete typed extension graph before a client is used.
            // This is required by aggregator, downloader, tracker and lyrics extensions.
            specs.mapNotNull { it.second.getOrNull() }.forEach { spec ->
                if (isEnabled(spec.metadata.id)) {
                    spec.injectable.injectOrRun("providers") {
                        injectProviders(this, newCatalog)
                    }
                }
            }

            val parsed = specs.map { (file, result) ->
                result.fold(
                    onSuccess = { loadEntry(it) },
                    onFailure = { Entry(file, null, null, it.fullMessage()) },
                )
            }
            _entries.value = parsed
            _loginUsers.value = parsed.associate { it.id to storedUser(it.id) }
            loaded = true

            val selected = _selectedMusicExtensionId.value
            val valid = parsed.any {
                it.id == selected && it.isMusic && it.client != null && isEnabled(it.id)
            }
            if (!valid) {
                val fallback = parsed.firstOrNull {
                    it.isMusic && it.client != null && isEnabled(it.id)
                }?.id
                setSelectedId(fallback)
            }
            val activeId = _selectedMusicExtensionId.value
            parsed.firstOrNull { it.id == activeId }?.client
        }
        selectedClient?.onExtensionSelected()
        Unit
    }

    suspend fun ensureLoaded() {
        if (!loaded) reload()
    }

    suspend fun install(uri: Uri): Result<Metadata> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File.createTempFile("extension-", ".apk", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to open the selected extension" }
                    staging.outputStream().use(input::copyTo)
                }
                val metadata = parseMetadata(staging)
                val safeId = metadata.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val destination = File(extensionDirectory, "$safeId.apk")
                staging.copyTo(destination, overwrite = true)
                destination.setReadable(true, true)
                reload()
                val entry = _entries.value.first { it.id == metadata.id }
                entry.client ?: error(
                    "Installed ${metadata.name} could not be loaded: ${entry.error ?: "unknown loader error"}"
                )
                entry.metadata ?: error("Installed extension metadata is unavailable")
            } finally {
                staging.delete()
            }
        }
    }

    suspend fun remove(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureLoaded()
            val entry = _entries.value.firstOrNull { it.id == id }
                ?: error("Extension not found: $id")
            check(entry.file.delete()) { "Unable to delete ${entry.file.name}" }
            preferences.edit().remove(enabledKey(id)).apply()
            if (_selectedMusicExtensionId.value == id) setSelectedId(null)
            reload()
        }
    }

    fun isEnabled(id: String): Boolean = preferences.getBoolean(enabledKey(id), true)

    suspend fun setEnabled(id: String, enabled: Boolean) {
        preferences.edit().putBoolean(enabledKey(id), enabled).apply()
        if (!enabled && _selectedMusicExtensionId.value == id) {
            setSelectedId(null)
        }
        reload()
    }

    suspend fun selectMusicExtension(id: String) {
        ensureLoaded()
        val entry = _entries.value.firstOrNull {
            it.id == id && it.isMusic && it.client != null && isEnabled(it.id)
        } ?: error("Music extension is unavailable: $id")
        setSelectedId(entry.id)
        refreshNetworkState()
        entry.client?.onExtensionSelected()
        if (entry.client is LoginClient && storedUser(entry.id) == null) {
            launchLogin(entry.id)
        }
    }

    suspend fun settingsFor(id: String): List<Setting> {
        ensureLoaded()
        return _entries.value.firstOrNull { it.id == id }?.client?.getSettingItems().orEmpty()
    }

    fun valuesFor(id: String): Settings = PreferenceSettings(
        context.getSharedPreferences("classic_extension_settings_$id", Context.MODE_PRIVATE)
    )

    suspend fun notifySettingChanged(id: String, key: String?) {
        ensureLoaded()
        val client = _entries.value.firstOrNull { it.id == id }?.client
        if (client is SettingsChangeListenerClient) {
            client.onSettingsChanged(valuesFor(id), key)
        }
    }

    fun clientFor(id: String): ExtensionClient? = _entries.value.firstOrNull { it.id == id }?.client

    fun imageUrl(image: ImageHolder?): String? = image.toClassicUrl()

    fun launchLogin(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id && it.client is LoginClient }
            ?: error("${_entries.value.firstOrNull { it.id == id }?.metadata?.name ?: id} does not provide login")
        context.startActivity(
            Intent(context, ClassicExtensionLoginActivity::class.java)
                .putExtra(ClassicExtensionLoginActivity.EXTRA_EXTENSION_ID, entry.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun saveLoginUsers(id: String, users: List<User>) = withContext(Dispatchers.IO) {
        val client = clientFor(id) as? LoginClient ?: error("Extension does not provide login")
        val user = users.firstOrNull() ?: error("No account was returned by the extension")
        preferences.edit().putString(userKey(id), json.encodeToString(user)).apply()
        client.setLoginUser(user)
        _loginUsers.value = _loginUsers.value + (id to user)
    }

    suspend fun logout(id: String) = withContext(Dispatchers.IO) {
        preferences.edit().remove(userKey(id)).apply()
        (clientFor(id) as? LoginClient)?.setLoginUser(null)
        _loginUsers.value = _loginUsers.value + (id to null)
    }

    suspend fun search(query: String): SearchSummaryPage = withContext(Dispatchers.IO) {
        ensureLoaded()
        refreshNetworkState()
        val entry = selectedEntry() ?: return@withContext SearchSummaryPage(emptyList())
        val searchClient = entry.client as? SearchFeedClient
            ?: error("${entry.metadata?.name ?: entry.id} does not provide search")
        val feed = searchClient.loadSearchFeed(query)
        val data = feed.getPagedData(feed.notSortTabs.firstOrNull())
        val shelves = data.pagedData.loadPage(null).data
        val summaries = shelves.mapNotNull { shelf ->
            val extensionTracks = shelf.tracks()
            val items = extensionTracks.map { track -> track.toClassicSong(entry.id) }
            if (items.isEmpty()) null else SearchSummary(shelf.title, items)
        }
        SearchSummaryPage(summaries)
    }

    suspend fun home(): HomePage = withContext(Dispatchers.IO) {
        ensureLoaded()
        refreshNetworkState()
        val entry = selectedEntry() ?: error("Select a music extension in Settings → Extensions")
        val client = entry.client as? HomeFeedClient
            ?: error("${entry.metadata?.name ?: entry.id} does not provide a home feed")
        val feed = client.loadHomeFeed()
        val data = feed.getPagedData(feed.notSortTabs.firstOrNull())
        val shelves = data.pagedData.loadPage(null).data
        HomePage(
            chips = null,
            sections = shelves.mapNotNull { shelf ->
                val items = shelf.tracks().map { it.toClassicSong(entry.id) }
                if (items.isEmpty()) null else HomePage.Section(
                    title = shelf.title,
                    label = (shelf as? Shelf.Lists<*>)?.subtitle,
                    thumbnail = null,
                    endpoint = null,
                    items = items,
                )
            },
            continuation = null,
        )
    }

    suspend fun resolve(mediaId: String): ResolvedStream = withContext(Dispatchers.IO) {
        require(isExtensionMediaId(mediaId)) { "Not an extension media id" }
        resolvedStreams[mediaId]?.takeIf {
            SystemClock.elapsedRealtime() - it.createdAt < RESOLVED_STREAM_TTL_MS
        }?.stream?.let { return@withContext it }
        ensureLoaded()
        refreshNetworkState()
        val extensionId = extensionIdFrom(mediaId)
        val entry = _entries.value.firstOrNull {
            it.id == extensionId && it.client != null && isEnabled(it.id)
        } ?: error("Extension is unavailable: $extensionId")
        val client = entry.client as? TrackClient
            ?: error("${entry.metadata?.name ?: entry.id} cannot play tracks")
        val stored = tracks.getString(mediaId, null)
            ?: error("Track metadata is unavailable; search for the track again")
        val track = json.decodeFromString<Track>(stored)
        val loadedTrack = client.loadTrack(track, false)
        val streamable = loadedTrack.servers.maxByOrNull { it.quality }
            ?: loadedTrack.streamables
                .filter { it.type == Streamable.MediaType.Server }
                .maxByOrNull { it.quality }
            ?: error("The extension returned no playable server")
        val media = client.loadStreamableMedia(streamable, false)
        val server = media as? Streamable.Media.Server
            ?: error("The extension returned non-server media")
        val source = server.sources.filter { !it.isVideo }.maxByOrNull { it.quality }
            ?: server.sources.maxByOrNull { it.quality }
            ?: error("The extension returned no playable source")
        if (source is Streamable.Source.Http) {
            check(source.request.method == dev.brahmkshatriya.echo.common.models.NetworkRequest.Method.GET) {
                "Only GET playback streams are supported"
            }
        }
        ResolvedStream(source).also {
            resolvedStreams[mediaId] = CachedResolvedStream(it, SystemClock.elapsedRealtime())
        }
    }

    suspend fun prepareMediaItem(item: SongItem): MediaItem {
        val resolved = resolve(item.id)
        val builder = item.toMediaItem().buildUpon().setUri(resolved.uri)
        resolved.mimeType?.let(builder::setMimeType)
        return builder.build()
    }

    /** Invalidates Nightly stream resolution so Media3 retries through the extension. */
    fun invalidate(mediaId: String) {
        resolvedStreams.remove(mediaId)
        classicTrackMappings.remove(mediaId)?.let(resolvedStreams::remove)
    }

    /** Resolves a song shown by the classic UI through the selected extension backend. */
    suspend fun resolveClassicSong(
        classicMediaId: String,
        title: String,
        artists: List<String>,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        ensureLoaded()
        val selectedId = _selectedMusicExtensionId.value
            ?: error("Select a music extension in Settings → Extensions")

        classicTrackMappings[classicMediaId]?.takeIf {
            extensionIdFrom(it) == selectedId
        }?.let { return@withContext resolve(it) }

        val primaryArtist = artists.firstOrNull().orEmpty()
        val query = listOf(title, primaryArtist).filter(String::isNotBlank).joinToString(" ")
        require(query.isNotBlank()) { "Song title is unavailable for extension lookup" }

        val candidates = search(query).summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()
        val match = candidates.maxByOrNull { candidate ->
            var score = 0
            if (candidate.title.normalizedMatchText() == title.normalizedMatchText()) score += 4
            if (primaryArtist.isNotBlank() && candidate.artists.any {
                    it.name.normalizedMatchText() == primaryArtist.normalizedMatchText()
                }
            ) score += 2
            if (candidate.title.normalizedMatchText().contains(title.normalizedMatchText())) score += 1
            score
        } ?: error("$title was not found by the selected extension")

        classicTrackMappings[classicMediaId] = match.id
        resolve(match.id)
    }

    private fun String.normalizedMatchText(): String = lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun selectedEntry(): Entry? {
        val selected = _selectedMusicExtensionId.value ?: return null
        return _entries.value.firstOrNull {
            it.id == selected && it.isMusic && it.client != null && isEnabled(it.id)
        }
    }

    private fun setSelectedId(id: String?) {
        _selectedMusicExtensionId.value = id
        preferences.edit().apply {
            if (id == null) remove(SELECTED_MUSIC_EXTENSION) else putString(SELECTED_MUSIC_EXTENSION, id)
        }.apply()
    }

    private fun createRuntimeSpec(file: File): RuntimeSpec {
        val metadata = parseMetadata(file)
        val injectable = Injectable(
            getter = {
                val classLoader = ExtensionDexLoader(metadata, context)
                classLoader.loadClass(metadata.className)
                    .getDeclaredConstructor()
                    .newInstance() as ExtensionClient
            },
            injections = mutableListOf({
                setSettings(valuesFor(metadata.id))
                if (this is MetadataProvider) setMetadata(metadata)
                if (this is GlobalSettingsProvider) setGlobalSettings(
                    PreferenceSettings(
                        context.getSharedPreferences(
                            "classic_extension_global",
                            Context.MODE_PRIVATE,
                        )
                    )
                )
                if (this is MessageFlowProvider) setMessageFlow(messages)
                if (this is NetworkConnectionProvider) {
                    setNetworkConnection(currentNetworkConnection())
                }
                if (this is WebViewClientProvider) {
                    setWebViewClient(ClassicExtensionWebViewBroker.client(context, metadata))
                }
                if (this is LoginClient) setLoginUser(storedUser(metadata.id))
                onInitialize()
                // Echo Nightly invokes this during initial injection as well as when the
                // user explicitly selects an extension.
                onExtensionSelected()
            }),
        )
        return RuntimeSpec(file, metadata, injectable)
    }

    private fun createCatalog(specs: List<RuntimeSpec>): RuntimeCatalog = RuntimeCatalog(
        music = specs.filter { it.metadata.type == ExtensionType.MUSIC }
            .map { MusicExtension(it.metadata.withEnabledState(), it.injectable) },
        tracker = specs.filter { it.metadata.type == ExtensionType.TRACKER }
            .map { TrackerExtension(it.metadata.withEnabledState(), it.injectable.casted<TrackerClient>()) },
        lyrics = specs.filter { it.metadata.type == ExtensionType.LYRICS }
            .map { LyricsExtension(it.metadata.withEnabledState(), it.injectable.casted<LyricsClient>()) },
        misc = specs.filter { it.metadata.type == ExtensionType.MISC }
            .map { MiscExtension(it.metadata.withEnabledState(), it.injectable) },
    )

    private suspend fun loadEntry(spec: RuntimeSpec): Entry = spec.injectable.value().fold(
        onSuccess = { Entry(spec.file, spec.metadata.withEnabledState(), it) },
        onFailure = { Entry(spec.file, spec.metadata.withEnabledState(), null, it.fullMessage()) },
    )

    private fun Metadata.withEnabledState() = copy(isEnabled = isEnabled(id))

    private fun Throwable.fullMessage(): String = generateSequence(this) { it.cause }
        .joinToString(" → ") { error ->
            val name = error.javaClass.simpleName
            error.message?.takeIf(String::isNotBlank)?.let { "$name: $it" } ?: name
        }

    private fun injectProviders(client: ExtensionClient, runtime: RuntimeCatalog) {
        if (client is MusicExtensionsProvider) {
            client.setMusicExtensions(requiredExtensions(
                "music", client.requiredMusicExtensions, runtime.music
            ))
        }
        if (client is TrackerExtensionsProvider) {
            client.setTrackerExtensions(requiredExtensions(
                "tracker", client.requiredTrackerExtensions, runtime.tracker
            ))
        }
        if (client is LyricsExtensionsProvider) {
            client.setLyricsExtensions(requiredExtensions(
                "lyrics", client.requiredLyricsExtensions, runtime.lyrics
            ))
        }
        if (client is MiscExtensionsProvider) {
            client.setMiscExtensions(requiredExtensions(
                "misc", client.requiredMiscExtensions, runtime.misc
            ))
        }
    }

    private fun <T : Extension<*>> requiredExtensions(
        type: String,
        required: List<String>,
        available: List<T>,
    ): List<T> {
        if (required.isEmpty()) return available
        val filtered = available.filter { it.id in required }
        val missing = required.filterNot { id -> filtered.any { it.id == id } }
        check(missing.isEmpty()) {
            "Required $type extensions are missing: ${missing.joinToString()}"
        }
        return filtered
    }

    private fun refreshNetworkState() {
        val connection = currentNetworkConnection()
        _entries.value.forEach { entry ->
            (entry.client as? NetworkConnectionProvider)?.setNetworkConnection(connection)
        }
    }

    private fun storedUser(id: String): User? = preferences.getString(userKey(id), null)?.let {
        runCatching { json.decodeFromString<User>(it) }.getOrNull()
    }

    private fun currentNetworkConnection(): NetworkConnection {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return NetworkConnection.NotConnected
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkConnection.Unmetered
        } else {
            NetworkConnection.Metered
        }
    }

    @Suppress("DEPRECATION")
    private fun parseMetadata(file: File): Metadata {
        val flags = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Invalid extension APK: ${file.name}")
        val bundle = packageInfo.applicationInfo?.metaData
            ?: error("Extension metadata is missing")
        fun optional(key: String) = bundle.getString(key)?.takeIf(String::isNotBlank)
        fun required(key: String) = optional(key) ?: error("Extension metadata '$key' is missing")
        val feature = packageInfo.reqFeatures.orEmpty().firstOrNull {
            it.name?.startsWith(EXTENSION_FEATURE_PREFIX) == true
        }?.name ?: error("Echo extension feature declaration is missing")
        val extensionType = feature.substringAfter(EXTENSION_FEATURE_PREFIX)
        val type = ExtensionType.entries.firstOrNull { it.feature == extensionType }
            ?: error("Unsupported extension type: $extensionType")
        return Metadata(
            className = required("class"),
            path = file.absolutePath,
            importType = ImportType.File,
            type = type,
            id = required("id"),
            name = required("name"),
            version = required("version"),
            description = required("description"),
            author = required("author"),
            authorUrl = optional("author_url"),
            icon = optional("icon_url")?.let {
                ImageHolder.NetworkRequestImageHolder(
                    dev.brahmkshatriya.echo.common.models.NetworkRequest(it), false
                )
            },
            repoUrl = optional("repo_url"),
            updateUrl = optional("update_url"),
            preservedPackages = optional("preserved_packages")
                .orEmpty().split(',').mapNotNull { it.trim().ifEmpty { null } },
            isEnabled = bundle.getBoolean("enabled", true),
        )
    }

    private fun Shelf.tracks(): List<Track> = when (this) {
        is Shelf.Item -> listOfNotNull(media as? Track)
        is Shelf.Lists.Tracks -> list
        is Shelf.Lists.Items -> list.filterIsInstance<Track>()
        is Shelf.Lists.Categories -> emptyList()
        is Shelf.Category -> emptyList()
    }

    private fun Track.toClassicSong(extensionId: String): SongItem {
        val mediaId = mediaId(extensionId, id)
        tracks.edit().putString(mediaId, json.encodeToString(this)).apply()
        return SongItem(
            id = mediaId,
            title = title,
            artists = artists.map { Artist(it.name, null) }.ifEmpty { listOf(Artist("Unknown artist", null)) },
            album = null,
            duration = duration?.div(1000L)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            musicVideoType = null,
            thumbnail = cover.toClassicUrl().orEmpty(),
            explicit = isExplicit,
        )
    }

    private fun ImageHolder?.toClassicUrl(): String? = when (this) {
        is ImageHolder.NetworkRequestImageHolder -> request.url
        is ImageHolder.ResourceUriImageHolder -> uri
        else -> null
    }

    private class PreferenceSettings(private val preferences: SharedPreferences) : Settings {
        override fun getString(key: String): String? = preferences.getString(key, null)
        override fun putString(key: String, value: String?) = preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()

        override fun getStringSet(key: String): Set<String>? = preferences.getStringSet(key, null)
        override fun putStringSet(key: String, value: Set<String>?) = preferences.edit().apply {
            if (value == null) remove(key) else putStringSet(key, value)
        }.apply()

        override fun getInt(key: String): Int? = if (preferences.contains(key)) preferences.getInt(key, 0) else null
        override fun putInt(key: String, value: Int?) = preferences.edit().apply {
            if (value == null) remove(key) else putInt(key, value)
        }.apply()

        override fun getBoolean(key: String): Boolean? =
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null

        override fun putBoolean(key: String, value: Boolean?) = preferences.edit().apply {
            if (value == null) remove(key) else putBoolean(key, value)
        }.apply()
    }

    private class ExtensionDexLoader(
        private val metadata: Metadata,
        context: Context,
    ) : DexClassLoader(
        metadata.path,
        context.codeCacheDir.absolutePath,
        extractLibraries(metadata, context).absolutePath,
        context.classLoader,
    ) {
        override fun loadClass(name: String?, resolve: Boolean): Class<*> {
            if (name != null && metadata.preservedPackages.any(name::startsWith)) {
                preservedClasses[name]?.get()?.let { return it }
                return super.loadClass(name, resolve).also {
                    preservedClasses[name] = WeakReference(it)
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    companion object {
        private const val PREFERENCES = "classic_extensions"
        private const val TRACKS = "classic_extension_tracks"
        private const val SELECTED_MUSIC_EXTENSION = "selected_music_extension"
        private const val EXTENSION_FEATURE_PREFIX = "dev.brahmkshatriya.echo."
        private const val MEDIA_PREFIX = "echoext:"
        private const val RESOLVED_STREAM_TTL_MS = 5 * 60 * 1000L

        @Volatile
        private var instance: ClassicExtensionManager? = null
        private val preservedClasses = mutableMapOf<String, WeakReference<Class<*>>>()

        fun get(context: Context): ClassicExtensionManager = instance ?: synchronized(this) {
            instance ?: ClassicExtensionManager(context.applicationContext).also { instance = it }
        }

        fun isExtensionMediaId(mediaId: String): Boolean = mediaId.startsWith(MEDIA_PREFIX)

        private fun enabledKey(id: String) = "enabled_$id"
        private fun userKey(id: String) = "user_$id"

        private fun mediaId(extensionId: String, trackId: String): String {
            val encoded = Base64.encodeToString(
                trackId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            return "$MEDIA_PREFIX$extensionId:$encoded"
        }

        private fun extensionIdFrom(mediaId: String): String =
            mediaId.removePrefix(MEDIA_PREFIX).substringBefore(':')

        private fun extractLibraries(metadata: Metadata, context: Context): File {
            val root = File(context.codeCacheDir, "extension-libs/${metadata.id}")
            val marker = File(root, "version.txt")
            if (marker.isFile && marker.readText() == metadata.version) return root
            root.deleteRecursively()
            root.mkdirs()
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return root
            ZipFile(metadata.path).use { archive ->
                archive.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
                    .forEach { entry ->
                        val destination = File(root, entry.name.substringAfterLast('/'))
                        archive.getInputStream(entry).use { input ->
                            FileOutputStream(destination).use(input::copyTo)
                        }
                    }
            }
            marker.writeText(metadata.version)
            return root
        }

        @Suppress("unused")
        private fun File.sha256(): String = inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
