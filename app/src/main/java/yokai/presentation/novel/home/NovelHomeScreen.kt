package yokai.presentation.novel.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.setting.controllers.debug.LnRepoBrowseController
import eu.kanade.tachiyomi.ui.setting.controllers.debug.NovelBrowseController
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.presentation.novel.library.NovelLibraryContent

/**
 * Top-level Novels tab destination. Hosts [NovelLibraryContent] inside a Scaffold with an
 * overflow menu instead of a back arrow (this is the tab home, not a pushed controller). The
 * overflow menu surfaces the two transient screens a user reaches from here: browse sources
 * and manage plugin repos. A "Beta" chip in the title makes the state of LN support legible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelHomeScreen() {
    val router = LocalRouter.currentOrThrow
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Novels (Beta)", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = {
                        router.pushController(NovelBrowseController().withFadeTransaction())
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Browse sources")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Manage plugin repos") },
                                onClick = {
                                    menuOpen = false
                                    router.pushController(LnRepoBrowseController().withFadeTransaction())
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        NovelLibraryContent(padding)
    }
}
