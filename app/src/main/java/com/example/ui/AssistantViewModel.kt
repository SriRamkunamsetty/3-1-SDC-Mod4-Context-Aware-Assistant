package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AssistantRepository
import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.data.MemoryEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = AssistantRepository(
        database.conversationDao(),
        database.memoryDao(),
        viewModelScope
    )

    // Flow of all conversations from DB
    val conversations: StateFlow<List<ConversationEntity>> = repository.allConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Flow of all extracted memories from DB
    val memories: StateFlow<List<MemoryEntity>> = repository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active conversation ID
    private val _activeConversationId = MutableStateFlow<Int?>(null)
    val activeConversationId: StateFlow<Int?> = _activeConversationId.asStateFlow()

    // Observe messages for the active conversation
    val activeMessages: StateFlow<List<MessageEntity>> = _activeConversationId
        .flatMapLatest { id ->
            if (id != null) {
                repository.getMessagesForConversation(id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _inputMessage = MutableStateFlow("")
    val inputMessage: StateFlow<String> = _inputMessage.asStateFlow()

    private val _isAddMemoryDialogVisible = MutableStateFlow(false)
    val isAddMemoryDialogVisible: StateFlow<Boolean> = _isAddMemoryDialogVisible.asStateFlow()

    init {
        // Automatically set or create active conversation
        viewModelScope.launch {
            conversations.collect { list ->
                if (_activeConversationId.value == null) {
                    if (list.isNotEmpty()) {
                        _activeConversationId.value = list.first().id
                    } else {
                        // Create a default first conversation
                        val newId = repository.createConversation("Assistant Conversation")
                        _activeConversationId.value = newId.toInt()
                    }
                }
            }
        }
    }

    fun selectConversation(id: Int) {
        _activeConversationId.value = id
        _errorMessage.value = null
    }

    fun setInputMessage(text: String) {
        _inputMessage.value = text
    }

    fun setAddMemoryDialogVisible(visible: Boolean) {
        _isAddMemoryDialogVisible.value = visible
    }

    fun sendMessage() {
        val prompt = _inputMessage.value.trim()
        val convId = _activeConversationId.value
        if (prompt.isEmpty() || convId == null || _isLoading.value) return

        _inputMessage.value = ""
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            val result = repository.askAssistant(convId, prompt)
            _isLoading.value = false
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "An unknown network error occurred."
            }
        }
    }

    fun startNewConversation(title: String) {
        viewModelScope.launch {
            val cleanTitle = title.trim().ifEmpty { "New Conversation" }
            val newId = repository.createConversation(cleanTitle)
            _activeConversationId.value = newId.toInt()
            _errorMessage.value = null
        }
    }

    fun deleteConversation(id: Int) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_activeConversationId.value == id) {
                _activeConversationId.value = null // Init block will auto-pick or create another
            }
        }
    }

    fun renameConversation(id: Int, title: String) {
        if (title.trim().isEmpty()) return
        viewModelScope.launch {
            repository.renameConversation(id, title.trim())
        }
    }

    fun addCustomMemory(content: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.insertMemory(clean)
        }
    }

    fun deleteMemory(id: Int) {
        viewModelScope.launch {
            repository.deleteMemory(id)
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            repository.clearAllMemories()
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}
