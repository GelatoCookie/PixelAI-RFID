package com.zebra.rfid.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.TagData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class TagItem(
    val epc: String,
    val count: Int,
    val rssi: String,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class RfidUiState(
    val connectionState: RfidHandler.ConnectionState = RfidHandler.ConnectionState.DISCONNECTED,
    val readerName: String = RfidUtility.DEFAULT_READER_NAME,
    val statusDetail: String? = null,
    val isScanning: Boolean = false,
    val uniqueTagCount: Int = 0,
    val totalReadCount: Int = 0,
    val tags: List<TagItem> = emptyList(),
    val searchQuery: String = "",
    val soundEnabled: Boolean = true,
    val availableReaders: List<ReaderDevice> = emptyList(),
    val showReaderSelectionDialog: Boolean = false,
    val selectionListener: RfidHandler.ReaderSelectionListener? = null
)

class RfidViewModel(application: Application) : AndroidViewModel(application),
    RfidHandler.ConnectionListener, RfidHandler.InventoryListener {

    private val rfidHandler = RfidHandler(application)
    private val toneFeedback = ToneFeedback()

    private val _uiState = MutableStateFlow(RfidUiState())
    val uiState: StateFlow<RfidUiState> = _uiState.asStateFlow()

    // Thread-safe map for tag aggregation
    private val tagMap = ConcurrentHashMap<String, TagItem>()
    private var totalReads = 0

    fun onForeground() {
        rfidHandler.onForeground(this, this)
    }

    fun onBackground() {
        rfidHandler.onBackground()
    }

    override fun onCleared() {
        super.onCleared()
        rfidHandler.onDestroy()
        toneFeedback.release()
    }

    fun toggleInventory() {
        if (_uiState.value.isScanning) {
            stopInventory()
        } else {
            startInventory()
        }
    }

    fun startInventory() {
        rfidHandler.performInventory()
    }

    fun stopInventory() {
        rfidHandler.stopInventory()
    }

    fun performConnect() {
        rfidHandler.performConnect()
    }

    fun performDisconnect() {
        rfidHandler.performDisconnect()
    }

    fun clearTags() {
        tagMap.clear()
        totalReads = 0
        updateTagList()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        updateTagList()
    }

    fun toggleSound() {
        _uiState.update { it.copy(soundEnabled = !it.soundEnabled) }
    }

    fun selectReader(device: ReaderDevice?) {
        val listener = _uiState.value.selectionListener
        if (listener != null) {
            listener.onReaderSelected(device)
        } else {
            rfidHandler.selectReader(device)
        }
        _uiState.update {
            it.copy(
                showReaderSelectionDialog = false,
                selectionListener = null
            )
        }
    }

    fun openReaderSelection() {
        if (_uiState.value.availableReaders.isNotEmpty()) {
            _uiState.update { it.copy(showReaderSelectionDialog = true) }
        } else {
            rfidHandler.performConnect()
        }
    }

    fun dismissReaderSelectionDialog() {
        _uiState.update {
            it.copy(
                showReaderSelectionDialog = false,
                selectionListener = null
            )
        }
    }

    //
    // ConnectionListener Callbacks
    //

    override fun onConnectionStateChanged(
        state: RfidHandler.ConnectionState,
        readerName: String,
        detail: String?
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            val previousState = _uiState.value.connectionState
            _uiState.update {
                it.copy(
                    connectionState = state,
                    readerName = readerName,
                    statusDetail = detail,
                    isScanning = if (state != RfidHandler.ConnectionState.CONNECTED) false else it.isScanning
                )
            }

            if (_uiState.value.soundEnabled) {
                if (state == RfidHandler.ConnectionState.CONNECTED && previousState != RfidHandler.ConnectionState.CONNECTED) {
                    toneFeedback.playConnected()
                } else if (previousState == RfidHandler.ConnectionState.CONNECTED && state != RfidHandler.ConnectionState.CONNECTED) {
                    toneFeedback.playDisconnected()
                }
            }
        }
    }

    override fun onReaderSelectionRequired(
        readerList: ArrayList<ReaderDevice>,
        listener: RfidHandler.ReaderSelectionListener
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    availableReaders = readerList,
                    showReaderSelectionDialog = true,
                    selectionListener = listener
                )
            }
        }
    }

    override fun onReaderAppeared(readerName: String, connected: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(statusDetail = "Reader appeared: $readerName")
            }
        }
    }

    override fun onReaderDisappeared(readerName: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(statusDetail = "Reader disappeared: $readerName")
            }
        }
    }

    //
    // InventoryListener Callbacks
    //

    override fun onInventoryStateChanged(reading: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { it.copy(isScanning = reading) }
        }
    }

    override fun onInventoryUnavailable(startRequested: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    statusDetail = if (startRequested) "Inventory unavailable - check connection" else "Inventory stopped"
                )
            }
        }
    }

    override fun onTagsRead(tagDataList: Array<TagData>) {
        var hasNewTag = false
        for (tag in tagDataList) {
            val epc = tag.tagID ?: continue
            val rssi = tag.peakRSSI.toString()
            totalReads++

            val existing = tagMap[epc]
            if (existing != null) {
                tagMap[epc] = existing.copy(
                    count = existing.count + 1,
                    rssi = rssi,
                    lastSeenMs = System.currentTimeMillis()
                )
            } else {
                tagMap[epc] = TagItem(epc = epc, count = 1, rssi = rssi)
                hasNewTag = true
            }
        }

        if (hasNewTag && _uiState.value.soundEnabled) {
            toneFeedback.playNewTag()
        }

        updateTagList()
    }

    override fun onTriggerChanged(pressed: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(statusDetail = if (pressed) "Handheld Trigger Pressed" else "Handheld Trigger Released")
            }
        }
    }

    private fun updateTagList() {
        val query = _uiState.value.searchQuery.trim().lowercase()
        val allTags = tagMap.values.sortedByDescending { it.lastSeenMs }
        val filteredTags = if (query.isEmpty()) {
            allTags
        } else {
            allTags.filter { it.epc.lowercase().contains(query) }
        }

        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    tags = filteredTags,
                    uniqueTagCount = tagMap.size,
                    totalReadCount = totalReads
                )
            }
        }
    }
}
