

package iad1tya.echo.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.pages.SearchSummaryPage
import iad1tya.echo.music.models.ItemsPage
import iad1tya.echo.music.extensions.nightly.ClassicExtensionManager
import iad1tya.echo.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = try {
        URLDecoder.decode(savedStateHandle.get<String>("query")!!, "UTF-8")
    } catch (e: IllegalArgumentException) {
        savedStateHandle.get<String>("query")!!
    }
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()
    private val extensionManager = ClassicExtensionManager.get(context)

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                extensionManager.ensureLoaded()
                if (extensionManager.selectedMusicExtensionId.value == null) {
                    if (filter == null) summaryPage = SearchSummaryPage(emptyList())
                    else viewStateMap[filter.value] = ItemsPage(emptyList(), null)
                    return@collect
                }
                runCatching {
                    val extensionResults = extensionManager.search(query)
                    if (filter == null) {
                        summaryPage = extensionResults
                    } else {
                        val items = if (filter.value == YouTube.SearchFilter.FILTER_SONG.value) {
                            extensionResults.summaries.flatMap { it.items }
                        } else {
                            emptyList()
                        }
                        viewStateMap[filter.value] = ItemsPage(items, null)
                    }
                }.onFailure {
                    reportException(it)
                    if (filter == null) summaryPage = SearchSummaryPage(emptyList())
                    else viewStateMap[filter.value] = ItemsPage(emptyList(), null)
                }
            }
        }
    }

    fun loadMore() {
        // Extension search owns pagination; classic search renders its returned page.
    }
}
