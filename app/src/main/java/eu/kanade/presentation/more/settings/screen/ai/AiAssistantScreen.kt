package eu.kanade.presentation.more.settings.screen.ai

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.presentation.anime.components.MarkdownRender
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.ai.AiManager
import eu.kanade.tachiyomi.ui.more.settings.screen.ai.AiAssistantScreenModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiAssistantScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AiAssistantScreenModel() }
        val state by screenModel.state.collectAsState()
        val sessions by screenModel.sessions.collectAsState()

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val aiManager = remember { Injekt.get<AiManager>() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var input by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        var errorCount by remember { mutableIntStateOf(0) }
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            errorCount = aiManager.getErrorCount()
        }

        LaunchedEffect(state.messages.size, state.isLoading, state.streamingMessage) {
            if (state.messages.isNotEmpty() || state.streamingMessage != null) {
                val lastIndex = if (state.streamingMessage != null) state.messages.size else state.messages.size - 1
                if (lastIndex >= 0) {
                    listState.animateScrollToItem(lastIndex)
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerTonalElevation = 0.dp,
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "CHATS",
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Add, null) },
                        label = { Text("New Chat") },
                        selected = false,
                        onClick = {
                            screenModel.createNewSession()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        ),
                    )

                    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))

                    LazyColumn(modifier = Modifier.fillMaxHeight()) {
                        items(sessions, key = { it.id }) { session ->
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.History, null) },
                                label = {
                                    Text(
                                        session.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                selected = state.activeSessionId == session.id,
                                onClick = {
                                    screenModel.switchSession(session.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                badge = {
                                    IconButton(
                                        onClick = { screenModel.deleteSession(session.id) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    AppBar(
                        title = "Diagnostic Assistant",
                        navigateUp = { navigator.pop() },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Chat History")
                            }
                        },
                    )
                },
                containerColor = MaterialTheme.colorScheme.surface, // Base 60%
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                contentWindowInsets = WindowInsets(0), // Handle insets manually
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                        .imePadding(), // Follow keyboard
                ) {
                    DiagnosticHUD(errorCount)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (state.messages.isEmpty()) {
                            item {
                                AssistantMessage(
                                    content = "Diagnostic Assistant online. How can I help you troubleshoot issues or analyze your library today?",
                                    onCopy = {
                                        context.copyToClipboard("AniZen AI", it)
                                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                                    },
                                )
                            }
                        }
                        items(state.messages) { message ->
                            if (message.role == "user") {
                                val aiPreferences = remember { Injekt.get<AiPreferences>() }
                                val displayName by aiPreferences.displayName().changes()
                                    .collectAsState(aiPreferences.displayName().get())
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = displayName.ifBlank { "User" }.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp).alpha(0.6f),
                                    )
                                    UserMessage(message.content)
                                }
                            } else {
                                AssistantMessage(
                                    content = message.content,
                                    onCopy = {
                                        context.copyToClipboard("AniZen AI", it)
                                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                                    },
                                )
                            }
                        }
                        state.streamingMessage?.let { streamingContent ->
                            item {
                                AssistantMessage(
                                    content = streamingContent,
                                    onCopy = {
                                        context.copyToClipboard("AniZen AI", it)
                                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                                    },
                                )
                            }
                        }
                        if (state.isLoading && state.streamingMessage == null) {
                            item {
                                ProcessingIndicator(input)
                            }
                        }
                    }

                    ChatInput(
                        value = input,
                        onValueChange = { input = it },
                        isLoading = state.isLoading,
                        onSend = {
                            screenModel.sendMessage(input)
                            input = ""
                        },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }
        }
    }

    @Composable
    private fun DiagnosticHUD(errorCount: Int) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f), // Secondary 30%
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), // Reduced padding
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (errorCount > 0) {
                                Color.Red.copy(alpha = alpha)
                            } else {
                                Color.Green.copy(alpha = alpha)
                            },
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Status: Ready | Library: Synchronized",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                )
            }
        }
    }

    @Composable
    private fun ChatInput(
        value: String,
        onValueChange: (String) -> Unit,
        isLoading: Boolean,
        onSend: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val primaryColor = MaterialTheme.colorScheme.primary // Accent 10%
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh

        Surface(
            tonalElevation = 2.dp,
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp), // Minimal bottom padding
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("Type a message or ask a question...", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor,
                        cursorColor = primaryColor,
                    ),
                    maxLines = 5,
                )
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (value.isNotBlank() && !isLoading) {
                                primaryColor
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank() && !isLoading) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun UserMessage(content: String) {
        Box(modifier = Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                shape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp),
                modifier = Modifier.widthIn(min = 40.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = content,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    @Composable
    private fun AssistantMessage(content: String, onCopy: (String) -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.alpha(0.6f).padding(horizontal = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "ANI-ZEN OS // ANALYSIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onCopy(content) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    MarkdownRender(content = content)
                }
            }
        }
    }

    @Composable
    private fun ProcessingIndicator(query: String) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp).alpha(0.5f),
            )
            Text(
                text = "PROCESSING CORE COMMANDS...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(0.5f),
            )
        }
    }
}
