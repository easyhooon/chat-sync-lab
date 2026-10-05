package dev.chatlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
    private val chatViewModel by viewModels<ChatViewModel> {
        viewModelFactory { initializer {
            val mode = if (BuildConfig.DEBUG) when (intent.getStringExtra("ack_loss_lab")) {
                "both" -> LabMode.BOTH_LOST
                "http-only" -> LabMode.HTTP_LOST
                else -> LabMode.DIRECT
            } else LabMode.DIRECT
            ChatViewModel((application as ChatApplication).outbox, (application as ChatApplication).messages, mode)
        } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ChatRoute(chatViewModel) } }
    }
}

@Composable
private fun ChatRoute(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.connect()
                Lifecycle.Event.ON_STOP -> viewModel.disconnect()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.disconnect() }
    }
    ChatScreen(state, onSelectUser = viewModel::connect, onReconnect = { viewModel.connect() }, onSend = viewModel::send, onRetry = viewModel::retry)
}

@Composable
fun ChatScreen(state: ChatState, onSelectUser: (String) -> Unit, onReconnect: () -> Unit, onSend: (String) -> Unit, onRetry: (String) -> Unit) {
    var draft by rememberSaveable(state.user) { mutableStateOf("") }
    var observedQueuedId by rememberSaveable(state.user) { mutableStateOf(state.lastQueuedId) }
    LaunchedEffect(state.lastQueuedId) {
        if (state.lastQueuedId != observedQueuedId) {
            if (state.lastQueuedId != null) draft = ""
            observedQueuedId = state.lastQueuedId
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.lastOrNull()?.clientMessageId, state.messages.lastOrNull()?.status, state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat Lab · demo", style = MaterialTheme.typography.headlineSmall)
            Text("로컬 테스트 신원 · 계정별 기기 저장 기록", style = MaterialTheme.typography.bodySmall)
            if (state.labMode != LabMode.DIRECT) Text(state.labMode.label, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("alice", "bob").forEach { user ->
                    FilterChip(selected = state.user == user, onClick = { onSelectUser(user) }, label = { Text(user) })
                }
                Text(state.connection, style = MaterialTheme.typography.labelMedium)
            }
            if (!state.connected) OutlinedButton(onClick = onReconnect) { Text("다시 연결") }
            state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.messages.isEmpty()) item { Text("첫 메시지를 보내세요.") }
                items(state.messages, key = { it.stableKey }) { row ->
                    val own = row.senderId == state.user
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                        Card(colors = CardDefaults.cardColors(containerColor = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.padding(12.dp).widthIn(max = 280.dp)) {
                                Text(row.senderId, style = MaterialTheme.typography.labelSmall)
                                Text(row.text)
                                Text(if (own) when (row.status) {
                                    SendStatus.SENDING -> "전송 중"
                                    SendStatus.SENT -> "서버 수락 · #${row.sequence}"
                                    SendStatus.FAILED -> "전송 거절"
                                    SendStatus.UNKNOWN -> "결과 미확인"
                                } else "#${row.sequence}", style = MaterialTheme.typography.labelSmall)
                                row.serverInstanceId?.let { Text("서버 실행 ${it.take(8)}", style = MaterialTheme.typography.labelSmall) }
                                if (state.labMode != LabMode.DIRECT) Text("client ID ${row.clientMessageId.take(8)}", style = MaterialTheme.typography.labelSmall)
                                if (own && row.status == SendStatus.UNKNOWN) {
                                    TextButton(onClick = { onRetry(row.clientMessageId) }, enabled = state.connected && state.outboxReady) { Text("같은 ID로 재시도") }
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= 1000) draft = it },
                    modifier = Modifier.weight(1f), label = { Text("메시지") }, maxLines = 3, enabled = !state.queueing)
                Button(onClick = { onSend(draft) }, enabled = state.connected && state.outboxReady && !state.queueing && draft.isNotBlank()) { Text("전송") }
            }
            Text("기기 캐시는 서버 재시작 뒤에도 남습니다. #순서는 서버 실행별입니다.", style = MaterialTheme.typography.labelSmall)
        }
    }
}
