package iad1tya.echo.music.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.common.clients.LoginClient
import iad1tya.echo.music.LocalPlayerAwareWindowInsets
import iad1tya.echo.music.R
import iad1tya.echo.music.extensions.nightly.ClassicExtensionManager
import iad1tya.echo.music.ui.component.IconButton
import iad1tya.echo.music.ui.component.Material3SettingsGroup
import iad1tya.echo.music.ui.component.Material3SettingsItem
import iad1tya.echo.music.ui.utils.backToMain
import kotlinx.coroutines.launch

/** Account center backed only by installed Echo Nightly-compatible extensions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    @Suppress("UNUSED_PARAMETER") highlightKey: String? = null,
) {
    val context = LocalContext.current
    val manager = remember(context) { ClassicExtensionManager.get(context) }
    val entries by manager.entries.collectAsState()
    val users by manager.loginUsers.collectAsState()
    val selectedId by manager.selectedMusicExtensionId.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(manager) { manager.ensureLoaded() }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        Text(
            text = "Extension accounts",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp),
        )

        val accountEntries = entries.filter { it.client is LoginClient && it.error == null }
        Material3SettingsGroup(
            title = "Music services",
            scrollState = scrollState,
            items = if (accountEntries.isEmpty()) {
                listOf(
                    Material3SettingsItem(
                        customIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                        title = { Text("No account extension installed") },
                        description = { Text("Install YouTube Music, Spotify, or another login-capable extension") },
                        onClick = { navController.navigate("settings/extensions") },
                    )
                )
            } else accountEntries.map { entry ->
                val metadata = entry.metadata
                val user = users[entry.id]
                val image = manager.imageUrl(user?.cover ?: metadata?.icon)
                Material3SettingsItem(
                    customIcon = {
                        if (image.isNullOrBlank()) {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = null)
                        } else {
                            AsyncImage(
                                model = image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                        }
                    },
                    title = { Text(metadata?.name ?: entry.id) },
                    description = {
                        Text(buildString {
                            append(user?.name ?: "Not signed in")
                            if (selectedId == entry.id) append(" • Active music service")
                        })
                    },
                    trailingContent = {
                        OutlinedButton(onClick = {
                            if (user == null) {
                                runCatching { manager.launchLogin(entry.id) }.onFailure {
                                    Toast.makeText(context, it.message ?: "Login could not open", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                scope.launch {
                                    manager.logout(entry.id)
                                    Toast.makeText(context, "${metadata?.name ?: entry.id} disconnected", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text(if (user == null) "Sign in" else "Log out") }
                    },
                    onClick = {
                        scope.launch {
                            if (entry.isMusic) {
                                runCatching { manager.selectMusicExtension(entry.id) }.onFailure {
                                    Toast.makeText(context, it.message ?: "Service could not be selected", Toast.LENGTH_LONG).show()
                                }
                            }
                            if (user == null) runCatching { manager.launchLogin(entry.id) }
                        }
                    },
                )
            },
        )

        Spacer(Modifier.height(16.dp))
        Material3SettingsGroup(
            title = "Manage",
            scrollState = scrollState,
            items = listOf(
                Material3SettingsItem(
                    customIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                    title = { Text("Extensions and service settings") },
                    description = { Text("Install, select, and configure backend extensions") },
                    onClick = { navController.navigate("settings/extensions") },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.discord),
                    title = { Text(stringResource(R.string.discord_integration)) },
                    onClick = { navController.navigate("settings/discord") },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.ic_lastfm),
                    title = { Text(stringResource(R.string.lastfm_integration)) },
                    onClick = { navController.navigate("settings/lastfm") },
                ),
            ),
        )

        Spacer(Modifier.height(50.dp))
        Spacer(Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)))
    }

    TopAppBar(
        title = { Text("Extension accounts") },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
