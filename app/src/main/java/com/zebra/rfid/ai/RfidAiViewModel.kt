package com.zebra.rfid.ai

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed interface RfidAiUiState {
    data object Initial : RfidAiUiState
    data object Loading : RfidAiUiState
    data class Success(val responseText: String, val isCached: Boolean = false) : RfidAiUiState
    data class Error(val errorMessage: String) : RfidAiUiState
}

class RfidAiViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<RfidAiUiState>(RfidAiUiState.Initial)
    val uiState: StateFlow<RfidAiUiState> = _uiState.asStateFlow()

    private val generativeModel = Firebase.ai.generativeModel("gemini-3.8-flash")

    private val sharedPreferences: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val responseCache = ConcurrentHashMap<String, String>()

    init {
        loadCacheFromPrefs()
    }

    fun askQuestion(
        userPrompt: String,
        scannedTags: List<TagItem> = emptyList(),
        forceRefresh: Boolean = false
    ) {
        if (userPrompt.isBlank()) return

        val cacheKey = generateCacheKey(userPrompt, scannedTags)

        if (!forceRefresh) {
            val cachedResponse = responseCache[cacheKey]
            if (!cachedResponse.isNullOrBlank()) {
                _uiState.value = RfidAiUiState.Success(cachedResponse, isCached = true)
                return
            }
        }

        _uiState.value = RfidAiUiState.Loading

        viewModelScope.launch(Dispatchers.IO) {
            val systemInstruction = buildString {
                append("You are an expert Zebra RFID Android SDK AI developer assistant. ")
                append("You specialize in Android development on Zebra devices like ${Build.MODEL}, Zebra RFID API3 SDK (com.zebra.rfid.api3), ")
                append("handling RFIDReader, Readers, RfidEventsListener, TagData, RSSI, region configuration, and inventory scanning.\n")
                if (scannedTags.isNotEmpty()) {
                    append("The user currently has ${scannedTags.size} scanned RFID tags in their active inventory session:\n")
                    scannedTags.take(15).forEach { tag ->
                        append("- EPC: ${tag.epc}, Reads: ${tag.count}, RSSI: ${tag.rssi} dBm\n")
                    }
                    if (scannedTags.size > 15) {
                        append("- ... and ${scannedTags.size - 15} more tags.\n")
                    }
                }
                append("\nUser Question: ")
                append(userPrompt)
            }

            try {
                val response = generativeModel.generateContent(
                    content {
                        text(systemInstruction)
                    }
                )
                val output = response.text
                if (!output.isNullOrBlank()) {
                    saveToCache(cacheKey, output)
                    _uiState.value = RfidAiUiState.Success(output, isCached = false)
                } else {
                    _uiState.value = RfidAiUiState.Error("No response received from Gemini AI.")
                }
            } catch (e: Exception) {
                _uiState.value = RfidAiUiState.Error(e.localizedMessage ?: "Failed to generate AI response.")
            }
        }
    }

    fun clearCache() {
        responseCache.clear()
        sharedPreferences.edit().clear().apply()
    }

    fun resetState() {
        _uiState.value = RfidAiUiState.Initial
    }

    private fun generateCacheKey(userPrompt: String, scannedTags: List<TagItem>): String {
        val cleanPrompt = userPrompt.trim().lowercase()
        return if (scannedTags.isEmpty()) {
            cleanPrompt
        } else {
            val tagsSummary = scannedTags.take(15).joinToString(";") { "${it.epc}:${it.count}" }
            "$cleanPrompt|tags:$tagsSummary"
        }
    }

    private fun loadCacheFromPrefs() {
        try {
            val allEntries = sharedPreferences.all
            for ((key, value) in allEntries) {
                if (value is String) {
                    responseCache[key] = value
                }
            }
        } catch (e: Exception) {
            // Non-fatal cache load error
        }
    }

    private fun saveToCache(key: String, responseText: String) {
        responseCache[key] = responseText
        try {
            sharedPreferences.edit().putString(key, responseText).apply()
        } catch (e: Exception) {
            // Non-fatal cache save error
        }
    }

    companion object {
        private const val PREFS_NAME = "gemini_ai_response_cache"
    }
}
