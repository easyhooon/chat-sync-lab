package dev.chatlab

import androidx.lifecycle.ViewModel

class ChatViewModel(private val session: ForegroundChatSession, labMode: LabMode = LabMode.DIRECT) : ViewModel() {
    init { session.configureMode(labMode) }
    val state = session.state
    fun pagedMessages(user: String, room: String) = session.pagedMessages(user, room)
    fun connect(user: String = state.value.user) = session.connect(user)
    fun send(text: String) = session.send(text)
    fun retry(id: String) = session.retry(id)
    fun loadOlder() = session.loadOlder()
    // Application process lifecycle owns the session; Activity disposal does not stop the socket.
}
