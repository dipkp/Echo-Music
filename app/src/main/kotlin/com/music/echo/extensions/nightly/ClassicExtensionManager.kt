package iad1tya.echo.music.extensions.nightly

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import com.music.innertube.pages.SearchSummary
import com.music.innertube.pages.SearchSummaryPage
import dalvik.system.DexClassLoader
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.SettingsChangeListenerClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.providers.GlobalSettingsProvider
import dev.brahmkshatriya.echo.common.providers.MetadataProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

    data class ResolvedStream(
        val url: String,
        val headers: Map<String, String>,
        val type: Streamable.SourceType,
    )

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val tracks = context.getSharedPreferences(TRACKS, Context.MODE_PRIVATE)
    private val extensionDirectory = File(context.filesDir, "extensions").apply { mkdirs() }
    private val mutex = Mutex()
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

    @Volatile
    private var loaded = false

    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parsed = extensionDirectory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .map(::loadEntry)
            _entries.value = parsed
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
        }
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
                _entries.value.first { it.id == metadata.id }.metadata
                    ?: error("Installed extension could not be loaded")
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
        entry.client?.onExtensionSelected()
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

    suspend fun search(query: String): SearchSummaryPage = withContext(Dispatchers.IO) {
        ensureLoaded()
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

    suspend fun resolve(mediaId: String): ResolvedStream = withContext(Dispatchers.IO) {
        require(isExtensionMediaId(mediaId)) { "Not an extension media id" }
        ensureLoaded()
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
        val source = server.sources
            .filterIsInstance<Streamable.Source.Http>()
            .filter { !it.isVideo }
            .maxByOrNull { it.quality }
            ?: server.sources.filterIsInstance<Streamable.Source.Http>().maxByOrNull { it.quality }
            ?: error("Raw extension streams are not supported by the classic HTTP player")
        check(source.request.method == dev.brahmkshatriya.echo.common.models.NetworkRequest.Method.GET) {
            "Only GET playback streams are supported"
        }
        ResolvedStream(source.request.url, source.request.headers, source.type)
    }

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

    private fun loadEntry(file: File): Entry = runCatching {
        val metadata = parseMetadata(file)
        val classLoader = ExtensionDexLoader(metadata, context)
        val client = classLoader.loadClass(metadata.className)
            .getDeclaredConstructor()
            .newInstance() as ExtensionClient
        val extensionSettings = valuesFor(metadata.id)
        client.setSettings(extensionSettings)
        if (client is MetadataProvider) client.setMetadata(metadata)
        if (client is GlobalSettingsProvider) client.setGlobalSettings(
            PreferenceSettings(context.getSharedPreferences("classic_extension_global", Context.MODE_PRIVATE))
        )
        kotlinx.coroutines.runBlocking { client.onInitialize() }
        Entry(file, metadata, client)
    }.getOrElse { error ->
        Entry(file, null, null, error.message ?: error.javaClass.simpleName)
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

        @Volatile
        private var instance: ClassicExtensionManager? = null
        private val preservedClasses = mutableMapOf<String, WeakReference<Class<*>>>()

        fun get(context: Context): ClassicExtensionManager = instance ?: synchronized(this) {
            instance ?: ClassicExtensionManager(context.applicationContext).also { instance = it }
        }

        fun isExtensionMediaId(mediaId: String): Boolean = mediaId.startsWith(MEDIA_PREFIX)

        private fun enabledKey(id: String) = "enabled_$id"

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
