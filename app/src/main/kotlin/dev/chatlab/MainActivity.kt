package dev.chatlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.*
import androidx.paging.LoadState
import kotlinx.coroutines.flow.distinctUntilChanged
import android.util.Log
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
    private val chatViewModel by viewModels<ChatViewModel> {
        viewModelFactory { initializer {
            val mode = if (BuildConfig.DEBUG) when (intent.getStringExtra("ack_loss_lab")) {
                "both" -> LabMode.BOTH_LOST
                "http-only" -> LabMode.HTTP_LOST
                "page-failure" -> LabMode.PAGE_FAILURE
                else -> LabMode.DIRECT
            } else LabMode.DIRECT
            ChatViewModel((application as ChatApplication).foregroundSession, mode)
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
    key(state.user, state.roomId) {
        val flow = remember(state.user, state.roomId) { viewModel.pagedMessages(state.user, state.roomId) }
        val rows = flow.collectAsLazyPagingItems()
        ChatScreen(state, rows, onSelectUser = viewModel::connect, onReconnect = { viewModel.connect() },
            onSend = viewModel::send, onRetry = viewModel::retry, onOlder = viewModel::loadOlder)
    }
}

@Composable
fun ChatScreen(state: ChatState, rows: LazyPagingItems<MessageRow>, onSelectUser: (String) -> Unit, onReconnect: () -> Unit, onSend: (String) -> Unit, onRetry: (String) -> Unit, onOlder: () -> Unit) {
    var draft by rememberSaveable(state.user) { mutableStateOf("") }
    var observedQueuedId by rememberSaveable(state.user) { mutableStateOf(state.lastQueuedId) }
    LaunchedEffect(state.lastQueuedId) {
        if (state.lastQueuedId != observedQueuedId) {
            if (state.lastQueuedId != null) draft = ""
            observedQueuedId = state.lastQueuedId
        }
    }
    val listState = rememberLazyListState()
    val cacheReadFailed = listOf(rows.loadState.refresh, rows.loadState.prepend, rows.loadState.append)
        .any { it is LoadState.Error }
    var followLatest by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().collect { (scrolling, index, offset) ->
                if (scrolling) followLatest = index == 0 && offset < 40
            }
    }
    val newestKey = rows.itemSnapshotList.items.firstOrNull()?.stableKey
    LaunchedEffect(newestKey, state.lastQueuedId, followLatest) {
        if (rows.itemCount > 0 && followLatest && !listState.isScrollInProgress) listState.scrollToItem(0)
    }
    LaunchedEffect(rows, listState, state.connected, state.loadingOlder, state.olderError, state.historyEnd) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            rows.itemCount > 0 && last >= rows.itemCount - 3 && rows.loadState.append is LoadState.NotLoading &&
                rows.loadState.append.endOfPaginationReached
        }.distinctUntilChanged().collect { edge ->
            if (edge && state.connected && !state.loadingOlder && state.olderError == null && !state.historyEnd) onOlder()
        }
    }
    if (BuildConfig.DEBUG) LaunchedEffect(rows, listState, state.user) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }
            .distinctUntilChanged().collect { (index, offset, scrolling) ->
                if (!scrolling) if (index < rows.itemCount) rows.peek(index)?.let { row ->
                    Log.i("ChatLab", "SCROLL_ANCHOR user=${state.user} key=${row.stableKey} sequence=${row.sequence} index=$index offset=$offset")
                }
            }
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
            if (cacheReadFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("기기 기록 읽기 실패", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = { rows.retry() }) { Text("기기 기록 다시 읽기") }
            }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.loadingOlder) "과거 기록 조회 중" else if (state.olderError != null) "과거 조회 실패" else if (state.historyEnd) "현재 서버의 처음까지 확인" else "과거 기록", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (!state.historyEnd) TextButton(onClick = onOlder, enabled = state.connected && !state.loadingOlder) {
                    Text(if (state.olderError != null) "과거 조회 재시도" else "과거 더 보기")
                }
                if (!followLatest) TextButton(onClick = { followLatest = true }, enabled = rows.itemCount > 0) { Text("최신으로") }
            }
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rows.itemCount == 0 && rows.loadState.refresh is LoadState.NotLoading) item { Text("저장된 메시지가 없습니다.") }
                items(count = rows.itemCount, key = rows.itemKey { it.stableKey }) { index ->
                    val row = rows[index] ?: return@items
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
