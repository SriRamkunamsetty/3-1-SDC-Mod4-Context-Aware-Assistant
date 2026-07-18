package com.example.data

import android.util.Log
import com.example.network.GeminiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantRepository(
    private val conversationDao: ConversationDao,
    private val memoryDao: MemoryDao,
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val TAG = "AssistantRepository"

    val allConversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversations()
    val allMemories: Flow<List<MemoryEntity>> = memoryDao.getAllMemories()

    fun getMessagesForConversation(conversationId: Int): Flow<List<MessageEntity>> {
        return conversationDao.getMessagesForConversation(conversationId)
    }

    suspend fun createConversation(title: String): Long = withContext(Dispatchers.IO) {
        conversationDao.insertConversation(ConversationEntity(title = title))
    }

    suspend fun renameConversation(id: Int, title: String) = withContext(Dispatchers.IO) {
        conversationDao.updateConversationTitle(id, title)
    }

    suspend fun deleteConversation(id: Int) = withContext(Dispatchers.IO) {
        conversationDao.deleteMessagesByConversationId(id)
        conversationDao.deleteConversationById(id)
    }

    suspend fun insertMessage(conversationId: Int, role: String, text: String): Long = withContext(Dispatchers.IO) {
        conversationDao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                text = text
            )
        )
    }

    suspend fun insertMemory(content: String): Long = withContext(Dispatchers.IO) {
        memoryDao.insertMemory(MemoryEntity(content = content))
    }

    suspend fun deleteMemory(id: Int) = withContext(Dispatchers.IO) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun clearAllMemories() = withContext(Dispatchers.IO) {
        memoryDao.deleteAllMemories()
    }

    /**
     * Sends the conversation history to the Gemini API, prepending stored memories.
     * On success, inserts the assistant response, and runs background auto-extraction.
     */
    suspend fun askAssistant(
        conversationId: Int,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. Insert user message to local database
            insertMessage(conversationId, "user", userPrompt)

            // 2. Fetch full updated history
            val history = conversationDao.getMessagesForConversation(conversationId).firstOrNull() ?: emptyList()
            
            // 3. Fetch all memories to build personalized context
            val memories = memoryDao.getAllMemories().firstOrNull() ?: emptyList()
            
            val systemPrompt = buildString {
                append("You are a helpful, context-aware AI assistant who maintains a continuous memory of the user to make conversations more helpful, personalized, and fluent. ")
                append("Here is the memory bank you have compiled about this user:\n")
                if (memories.isEmpty()) {
                    append("- No background facts or preferences recorded yet.\n")
                } else {
                    memories.forEach { mem ->
                        append("- ${mem.content}\n")
                    }
                }
                append("\nRules for interaction:\n")
                append("1. ALWAYS integrate these facts naturally in your responses when relevant, maintaining continuity across conversations. ")
                append("2. DO NOT explicitly list out your memory bank or repeat facts unless the user specifically asks you what you know about them. ")
                append("3. Keep responses conversational, concise, and personalized.")
            }

            // 4. Request response from Gemini Service
            val result = GeminiService.generateContent(history, systemPrompt)
            
            if (result.isSuccess) {
                val assistantResponse = result.getOrThrow()
                // 5. Save assistant response
                insertMessage(conversationId, "model", assistantResponse)

                // 6. Trigger background extraction of memories
                triggerBackgroundMemoryExtraction(conversationId)
                
                Result.success(assistantResponse)
            } else {
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "askAssistant error", e)
            Result.failure(e)
        }
    }

    /**
     * Runs in the background, analyzing the conversation history to extract facts/memories,
     * then saving any unique new memories to the database.
     */
    private fun triggerBackgroundMemoryExtraction(conversationId: Int) {
        repositoryScope.launch {
            try {
                val history = conversationDao.getMessagesForConversation(conversationId).firstOrNull() ?: return@launch
                val currentMemories = memoryDao.getAllMemories().firstOrNull() ?: emptyList()
                
                val extractedList = GeminiService.extractMemoriesFromConversation(history)
                
                for (extracted in extractedList) {
                    val cleanFact = extracted.trim()
                    if (cleanFact.isEmpty()) continue
                    
                    // Simple check to avoid duplicates or extremely similar items
                    val isDuplicate = currentMemories.any { existing ->
                        existing.content.equals(cleanFact, ignoreCase = true) ||
                        existing.content.contains(cleanFact, ignoreCase = true) ||
                        cleanFact.contains(existing.content, ignoreCase = true)
                    }
                    
                    if (!isDuplicate) {
                        Log.d(TAG, "Saving new extracted memory: $cleanFact")
                        memoryDao.insertMemory(MemoryEntity(content = cleanFact))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "triggerBackgroundMemoryExtraction failed", e)
            }
        }
    }
}
