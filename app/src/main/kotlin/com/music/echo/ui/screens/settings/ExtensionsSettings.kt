package iad1tya.echo.music.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import iad1tya.echo.music.LocalPlayerAwareWindowInsets
import iad1tya.echo.music.R
import iad1tya.echo.music.extensions.nightly.ClassicExtensionManager
import dev.brahmkshatriya.echo.common.clients.LoginClient
import iad1tya.echo.music.ui.component.IconButton
import iad1tya.echo.music.ui.component.Material3SettingsGroup
import iad1tya.echo.music.ui.component.Material3SettingsItem
import iad1tya.echo.music.ui.utils.backToMain
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsSettings(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val manager = remember(context) { ClassicExtensionManager.get(context) }
    val entries by manager.entries.collectAsState()
    val selectedId by manager.selectedMusicExtensionId.collectAsState()
    val loginUsers by manager.loginUsers.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(manager) {
        manager.reload()
    }

    LaunchedEffect(manager) {
        manager.messages.collect {
            Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
        }
    }

    val installer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                manager.install(uri)
                    .onSuccess { metadata ->
                        Toast.makeText(context, "Installed ${metadata.name}", Toast.LENGTH_SHORT).show()
                        if (metadata.type == dev.brahmkshatriya.echo.common.models.ExtensionType.MUSIC) {
                            runCatching { manager.selectMusicExtension(metadata.id) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        it.message ?: "Could not activate extension",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    }
                    .onFailure {
                        Toast.makeText(
                            context,
                            it.message ?: "Could not install extension",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal)
            )
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )
        Text(
            text = "Extensions",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp),
        )

        Material3SettingsGroup(
            title = "Manage",
            scrollState = scrollState,
            items = listOf(
                Material3SettingsItem(
                    customIcon = {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    },
                    title = { Text("Install extension APK manually") },
                    description = {
                        Text("Install or update an Echo Nightly-compatible extension from this device")
                    },
                    onClick = {
                        installer.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                            )
                        )
                    },
                )
            )
        )

        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Material3SettingsGroup(
                title = "Installed",
                scrollState = scrollState,
                items = listOf(
                    Material3SettingsItem(
                        customIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                        title = { Text("No extensions installed") },
                        description = { Text("Install a music extension to power search and playback") },
                        enabled = false,
                    )
                )
            )
        } else {
            Material3SettingsGroup(
                title = "Installed",
                scrollState = scrollState,
                items = entries.map { entry ->
                    val metadata = entry.metadata
                    val selectable = entry.isMusic && entry.client != null
                    Material3SettingsItem(
                        customIcon = {
                            Icon(Icons.Outlined.Extension, contentDescription = null)
                        },
                        title = { Text(metadata?.name ?: entry.file.name) },
                        description = {
                            Text(
                                entry.error ?: buildString {
                                    append(metadata?.author ?: "Unknown author")
                                    metadata?.version?.let { append(" • ").append(it) }
                                    if (entry.isBundled) append(" • Built-in")
                                    if (selectable && selectedId == entry.id) append(" • Active")
                                    loginUsers[entry.id]?.let { append(" • Signed in as ").append(it.name) }
                                    if (!entry.isMusic && metadata != null) {
                                        append(" • ").append(metadata.type.name.lowercase())
                                    }
                                }
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (entry.client is LoginClient) {
                                    IconButton(
                                        onClick = {
                                            runCatching { manager.launchLogin(entry.id) }.onFailure {
                                                Toast.makeText(
                                                    context,
                                                    it.message ?: "Could not open extension login",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        },
                                        onLongClick = {
                                            scope.launch {
                                                manager.logout(entry.id)
                                                Toast.makeText(context, "Account disconnected", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Outlined.AccountCircle,
                                            contentDescription = "Extension account",
                                        )
                                    }
                                }
                                if (selectable) {
                                    RadioButton(
                                        selected = selectedId == entry.id,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    manager.selectMusicExtension(entry.id)
                                                }.onFailure {
                                                    Toast.makeText(
                                                        context,
                                                        it.message ?: "Could not activate extension",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                        },
                                    )
                                }
                                if (!entry.isBundled) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                manager.remove(entry.id).onFailure {
                                                    Toast.makeText(
                                                        context,
                                                        it.message ?: "Could not remove extension",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                        },
                                        onLongClick = {},
                                    ) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove")
                                    }
                                }
                            }
                        },
                        enabled = entry.client != null,
                        onClick = if (selectable) {
                            {
                                scope.launch {
                                    manager.selectMusicExtension(entry.id)
                                }
                            }
                        } else null,
                    )
                }
            )
        }

        Spacer(Modifier.height(50.dp))
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
            )
        )
    }

    TopAppBar(
        title = { Text("Extensions") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
