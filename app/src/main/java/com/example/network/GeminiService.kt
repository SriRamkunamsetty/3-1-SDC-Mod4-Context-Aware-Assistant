package com.example.network

import android.util.Log
import com.example.BuildConfig
import com.example.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiService {
    private const val TAG = "GeminiService"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getApiKey(): String {
        return BuildConfig.GEMINI_API_KEY
    }

    suspend fun generateContent(
        history: List<MessageEntity>,
        systemInstruction: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(Exception("Gemini API key is not configured in Secrets."))
        }

        try {
            val url = "$BASE_URL?key=$apiKey"
            val requestBodyJson = JSONObject()

            // Build contents array
            val contentsArray = JSONArray()
            for (msg in history) {
                val contentObj = JSONObject()
                contentObj.put("role", msg.role)
                
                val partsArray = JSONArray()
                val partObj = JSONObject()
                partObj.put("text", msg.text)
                partsArray.put(partObj)
                
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
            }
            requestBodyJson.put("contents", contentsArray)

            // Build systemInstruction if present
            if (systemInstruction.isNotEmpty()) {
                val sysInstructionObj = JSONObject()
                val partsArray = JSONArray()
                val partObj = JSONObject()
                partObj.put("text", systemInstruction)
                partsArray.put(partObj)
                sysInstructionObj.put("parts", partsArray)
                requestBodyJson.put("systemInstruction", sysInstructionObj)
            }

            // Optional: Set temperature/topP via generationConfig
            val generationConfig = JSONObject()
            generationConfig.put("temperature", 0.7)
            requestBodyJson.put("generationConfig", generationConfig)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string()
                Log.d(TAG, "Response Code: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = if (response.code == 400) {
                        "Invalid request parameter or bad API key. Please verify your Secrets key."
                    } else if (response.code == 403) {
                        "API Key permission denied. Ensure Gemini API access is enabled for your key."
                    } else {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    return@withContext Result.failure(Exception(errorMsg))
                }

                if (bodyStr.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Received empty response from Gemini API."))
                }

                val responseJson = JSONObject(bodyStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext Result.failure(Exception("No content generated. Please try again."))
                }

                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts == null || parts.length() == 0) {
                    return@withContext Result.failure(Exception("No response parts received."))
                }

                val responseText = parts.getJSONObject(0).optString("text")
                if (responseText.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Received empty text part from Gemini."))
                }

                Result.success(responseText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network request failed", e)
            Result.failure(e)
        }
    }

    suspend fun extractMemoriesFromConversation(
        chatHistory: List<MessageEntity>
    ): List<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext emptyList()
        }

        // We only extract context if we have substantial text
        if (chatHistory.size < 2) return@withContext emptyList()

        try {
            val url = "$BASE_URL?key=$apiKey"
            val requestBodyJson = JSONObject()
            
            // Send conversation as history
            val contentsArray = JSONArray()
            for (msg in chatHistory.takeLast(10)) { // focus on recent messages for context
                val contentObj = JSONObject()
                contentObj.put("role", msg.role)
                val partsArray = JSONArray()
                val partObj = JSONObject()
                partObj.put("text", msg.text)
                partsArray.put(partObj)
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
            }
            requestBodyJson.put("contents", contentsArray)

            // High priority extraction instructions
            val systemInstruction = "Analyze the recent conversation and extract user details: preferences, facts, name, favorite technologies, goals, constraints, or unique info. " +
                    "Return ONLY a clean bulleted list where each line starts with '- ' (one fact per bullet). " +
                    "Example:\n- User's name is Alex\n- User likes to code in Kotlin\n" +
                    "Do NOT output any intro, outro, headers, or explanations. If no distinct new facts or preferences are discovered, output nothing."
            
            val sysInstructionObj = JSONObject()
            val sysPartsArray = JSONArray()
            val sysPartObj = JSONObject()
            sysPartObj.put("text", systemInstruction)
            sysPartsArray.put(sysPartObj)
            sysInstructionObj.put("parts", sysPartsArray)
            requestBodyJson.put("systemInstruction", sysInstructionObj)

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestBodyJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                if (!response.isSuccessful) return@withContext emptyList()

                val responseJson = JSONObject(bodyStr)
                val candidates = responseJson.optJSONArray("candidates") ?: return@withContext emptyList()
                if (candidates.length() == 0) return@withContext emptyList()

                val text = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text") ?: ""

                val facts = mutableListOf<String>()
                text.lines().forEach { line ->
                    val clean = line.trim()
                    if (clean.startsWith("-") && clean.length > 3) {
                        facts.add(clean.substring(1).trim())
                    } else if (clean.startsWith("*") && clean.length > 3) {
                        facts.add(clean.substring(1).trim())
                    }
                }
                facts
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting memories", e)
            emptyList()
        }
    }
}
