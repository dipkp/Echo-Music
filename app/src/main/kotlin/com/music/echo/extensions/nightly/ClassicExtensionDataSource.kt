package iad1tya.echo.music.extensions.nightly

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/** Routes Nightly raw stream providers into Media3 while preserving normal HTTP playback. */
@UnstableApi
class ClassicExtensionDataSource private constructor(
    private val fallbackFactory: DataSource.Factory,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null) { "Data source is already open" }
        val source = dataSpec.customData
        val next = if (source is Streamable.Source.Raw) {
            RawExtensionDataSource()
        } else {
            fallbackFactory.createDataSource()
        }
        listeners.forEach(next::addTransferListener)
        delegate = next
        return try {
            next.open(dataSpec)
        } catch (error: Throwable) {
            delegate = null
            runCatching { next.close() }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate) { "Data source is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders.orEmpty()

    override fun close() {
        delegate?.close()
        delegate = null
    }

    class Factory(private val fallbackFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ClassicExtensionDataSource(fallbackFactory)
    }
}

@UnstableApi
private class RawExtensionDataSource : BaseDataSource(false) {
    private var input: InputStream? = null
    private var opened = false
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val source = dataSpec.customData as? Streamable.Source.Raw
            ?: error("Missing raw extension stream")
        val provider = source.streamProvider ?: error("Raw extension stream provider is unavailable")
        val (stream, length) = runBlocking {
            provider.provide(dataSpec.position, dataSpec.length)
        }
        input = stream
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = input?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        input?.close()
        input = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
