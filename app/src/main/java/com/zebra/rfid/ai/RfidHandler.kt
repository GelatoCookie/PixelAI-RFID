package com.zebra.rfid.ai

import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.IRFIDLogger
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import com.zebra.rfid.api3.TagData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Owns the Zebra RFID SDK: init, discovery, connect, suspend/disconnect/dispose, and inventory.
 * Serializes all SDK calls on a single background executor thread.
 */
class RfidHandler(context: Context) : Readers.RFIDReaderEventHandler {

    enum class ConnectionState {
        DISCOVERING, CONNECTING, CONNECTED, DISCONNECTED, NO_READERS, DISCOVERY_FAILED, FAILED
    }

    fun interface ReaderSelectionListener {
        fun onReaderSelected(readerDevice: ReaderDevice?)
    }

    interface ConnectionListener {
        fun onConnectionStateChanged(state: ConnectionState, readerName: String, detail: String?)
        fun onReaderSelectionRequired(readerList: ArrayList<ReaderDevice>, listener: ReaderSelectionListener)
        fun onReaderAppeared(readerName: String, connected: Boolean)
        fun onReaderDisappeared(readerName: String)
    }

    interface InventoryListener {
        fun onInventoryStateChanged(reading: Boolean)
        fun onInventoryUnavailable(startRequested: Boolean)
        fun onTagsRead(tagDataList: Array<TagData>)
        fun onTriggerChanged(pressed: Boolean)
    }

    private val appContext: Context = context.applicationContext
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var connectionListener: ConnectionListener? = null

    @Volatile
    private var inventoryListener: InventoryListener? = null

    @Volatile
    private var readers: Readers? = null

    @Volatile
    private var availableRFIDReaderList: ArrayList<ReaderDevice>? = null

    @Volatile
    private var readerDevice: ReaderDevice? = null

    @Volatile
    private var reader: RFIDReader? = null

    private var eventHandler: EventHandler? = null

    @Volatile
    private var reading = false

    @Volatile
    private var active = false

    private var wasConnected = false
    private var preferredReaderName: String? = null
    private var preferredReaderAddress: String? = null

    //
    // Public API
    //

    /**
     * App entered the foreground: initialize SDK, discover and connect reader.
     */
    fun onForeground(connectionListener: ConnectionListener, inventoryListener: InventoryListener) {
        this.connectionListener = connectionListener
        this.inventoryListener = inventoryListener
        active = true
        try {
            IRFIDLogger.getLogger("AI-RFID").EnableDebugLogs(true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable debug logs: ${e.message}")
        }
        executeOnExecutor { initOrConnect() }
    }

    /**
     * App entered the background: disconnect, clean up, and dispose SDK.
     */
    fun onBackground() {
        active = false
        executeOnExecutor { dispose() }
    }

    fun onDestroy() {
        active = false
        executeOnExecutor { dispose() }
        executorService.shutdown()
    }

    fun performConnect() {
        executeOnExecutor { initOrConnect() }
    }

    fun performDisconnect() {
        executeOnExecutor { handleDisconnect() }
    }

    fun performInventory() {
        executeOnExecutor { runInventory(true) }
    }

    fun stopInventory() {
        executeOnExecutor { runInventory(false) }
    }

    fun selectReader(selectedDevice: ReaderDevice?) {
        executeOnExecutor {
            readerDevice = selectedDevice
            reader = selectedDevice?.rfidReader
            preferredReaderName = selectedDevice?.name
            preferredReaderAddress = selectedDevice?.address
            connectionTask()
        }
    }

    fun isReading(): Boolean = reading

    //
    // Internal SDK operations (Executor thread)
    //

    private fun executeOnExecutor(runnable: Runnable) {
        try {
            executorService.execute(runnable)
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "Task execution rejected", e)
        }
    }

    private fun notifyState(state: ConnectionState, detail: String?) {
        connectionListener?.onConnectionStateChanged(state, getReaderDisplayName(), detail)
    }

    private fun initOrConnect() {
        if (!active) return

        val r = readers
        if (r == null) {
            notifyState(ConnectionState.DISCOVERING, null)
            createInstance()
            return
        }

        if (isReaderConnected()) return

        getAvailableReader()
        val availableList = availableRFIDReaderList
        if (availableList != null && availableList.size > 1 && readerDevice == null) {
            requestReaderSelection()
        } else {
            connectionTask()
        }
    }

    private fun createInstance() {
        Log.d(TAG, "createInstance")
        var discovered = ArrayList<ReaderDevice>()
        var error: String? = null

        try {
            val r = Readers(appContext, RfidUtility.firstTransport())
            readers = r
            Readers.attach(this)
            discovered = RfidUtility.scanTransports(r)
        } catch (e: InvalidUsageException) {
            reportError(e)
            error = e.info
        } catch (e: Exception) {
            reportError(e)
            error = "SDK Error: ${e.message}"
        }

        availableRFIDReaderList = discovered
        if (!active) return

        if (error != null) {
            releaseReaders()
            notifyState(ConnectionState.DISCOVERY_FAILED, error)
        } else if (discovered.isEmpty()) {
            releaseReaders()
            notifyState(ConnectionState.NO_READERS, null)
        } else if (discovered.size == 1 || RfidUtility.findReader(discovered, preferredReaderName, preferredReaderAddress) != null) {
            connectionTask()
        } else {
            requestReaderSelection()
        }
    }

    private fun requestReaderSelection() {
        val availableList = availableRFIDReaderList ?: return
        connectionListener?.onReaderSelectionRequired(availableList) { selected ->
            selectReader(selected)
        }
    }

    private fun connectionTask() {
        Log.d(TAG, "connectionTask")
        if (!active || isReaderConnected()) return

        getAvailableReader()

        val availableList = availableRFIDReaderList
        if (reader == null && availableList != null && availableList.size > 1) {
            requestReaderSelection()
            return
        }

        var error: String? = null
        val currentReader = reader
        if (currentReader != null) {
            notifyState(ConnectionState.CONNECTING, null)
            error = handleConnect()
            if (error != null && error.contains("RFID_READER_REGION_NOT_CONFIGURED")) {
                error = RfidUtility.configureRegion(currentReader, REGION_CODE)
                if (error == null) {
                    error = handleConnect()
                }
            }
            if (error == null) {
                configureReader()
                notifyState(ConnectionState.CONNECTED, null)
                return
            }
        }

        Log.d(TAG, "connectionTask failed: $error")
        refreshAvailableReaders()
        notifyState(ConnectionState.FAILED, error)
        if (active && !availableRFIDReaderList.isNullOrEmpty()) {
            requestReaderSelection()
        }
    }

    private fun refreshAvailableReaders() {
        val r = readers
        if (r != null) {
            try {
                val found = RfidUtility.scanTransports(r)
                if (found.isNotEmpty()) {
                    availableRFIDReaderList = found
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "refreshAvailableReaders failed: ${e.message}")
            }
        }
        getAvailableReader()
    }

    private fun getAvailableReader() {
        Log.d(TAG, "getAvailableReader")
        val r = readers ?: return
        try {
            val availableReaders = r.GetAvailableRFIDReaderList()
            if (!availableReaders.isNullOrEmpty()) {
                availableRFIDReaderList = availableReaders
                Log.d(TAG, "Available readers to connect = ${availableReaders.size}")
                if (availableReaders.size == 1) {
                    readerDevice = availableReaders[0]
                    reader = readerDevice?.rfidReader
                } else {
                    val preferred = RfidUtility.findReader(availableReaders, preferredReaderName, preferredReaderAddress)
                    readerDevice = preferred
                    reader = preferred?.rfidReader
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getAvailableReader failed", t)
        }
    }

    private fun handleConnect(): String? {
        val r = reader ?: return "Reader is null"
        Log.d(TAG, "connect ${getReaderDisplayName()}")
        return try {
            if (!r.isConnected) {
                r.connect()
            }
            if (r.isConnected) {
                wasConnected = true
                null
            } else {
                "Reader did not report connected"
            }
        } catch (ofe: OperationFailureException) {
            Log.d(TAG, "OperationFailureException ${ofe.vendorMessage}")
            "${ofe.vendorMessage} ${ofe.results}"
        } catch (iue: InvalidUsageException) {
            Log.e(TAG, "InvalidUsageException during connect", iue)
            iue.info
        } catch (t: Throwable) {
            Log.e(TAG, "Error during connect", t)
            t.message ?: "Unknown connect error"
        }
    }

    private fun configureReader() {
        Log.d(TAG, "configureReader ${getReaderDisplayName()}")
        val r = reader
        if (r != null && r.isConnected) {
            try {
                if (eventHandler == null) {
                    eventHandler = EventHandler()
                }
                eventHandler?.let { RfidUtility.enableEvents(r, it) }
                reading = false
            } catch (e: Exception) {
                Log.e(TAG, "configureReader failed", e)
            }
        }
    }

    private fun handleDisconnect() {
        if (disconnectInternal()) {
            notifyState(ConnectionState.DISCONNECTED, null)
        }
    }

    private fun disconnectInternal(): Boolean {
        Log.d(TAG, "Disconnect")
        val r = reader ?: return false
        val hadConnection = wasConnected
        wasConnected = false
        try {
            eventHandler?.let { r.Events.removeEventsListener(it) }
            r.disconnect()
        } catch (ofe: OperationFailureException) {
            Log.d(TAG, "OperationFailureException ofe=${ofe.vendorMessage}")
        } catch (e1: LinkageError) {
            Log.w(TAG, "Non-fatal SDK/system framework mismatch during disconnect: ${e1.message}")
        } catch (e1: Exception) {
            Log.d(TAG, "Exception e=${e1.message}")
        }
        return hadConnection
    }

    private fun releaseReaders() {
        val r = readers ?: return
        readers = null
        Readers.deattach(this)
        try {
            r.Dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during readers.Dispose()", e)
        }
    }

    private fun dispose() {
        try {
            handleDisconnect()
            releaseReaders()
            reader = null
            readerDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Exception in dispose", e)
        }
    }

    private fun runInventory(start: Boolean) {
        val r = reader
        if (r == null || !r.isConnected) {
            inventoryListener?.onInventoryUnavailable(start)
            return
        }
        try {
            if (start) {
                r.Actions.Inventory.perform()
            } else {
                r.Actions.Inventory.stop()
            }
        } catch (e: Exception) {
            reportError(e)
        }
    }

    private fun isReaderConnected(): Boolean {
        val r = reader
        return r != null && r.isConnected
    }

    private fun getReaderDisplayName(): String {
        return RfidUtility.displayNameOf(readerDevice, reader)
    }

    private fun reportError(t: Throwable) {
        Log.e(TAG, "RFID Error Reported: ${t.message}", t)
    }

    //
    // Readers.RFIDReaderEventHandler callbacks
    //

    override fun RFIDReaderAppeared(device: ReaderDevice?) {
        if (device == null) return
        Log.d(TAG, "RFIDReaderAppeared ${device.name}")
        val connected = isReaderConnected()
        connectionListener?.onReaderAppeared(RfidUtility.toDisplayName(device.name), connected)
        if (connected && RfidUtility.isSameReader(device, readerDevice, reader)) {
            performDisconnect()
        }
        executeOnExecutor { connectionTask() }
    }

    override fun RFIDReaderDisappeared(device: ReaderDevice?) {
        if (device == null) return
        Log.d(TAG, "RFIDReaderDisappeared ${device.name}")
        connectionListener?.onReaderDisappeared(RfidUtility.toDisplayName(device.name))
        if (RfidUtility.isSameReader(device, readerDevice, reader)) {
            performDisconnect()
        }
    }

    //
    // EventHandler for RfidEventsListener
    //

    inner class EventHandler : RfidEventsListener {
        override fun eventReadNotify(e: RfidReadEvents?) {
            executeOnExecutor {
                val r = reader ?: return@executeOnExecutor
                val myTags = r.Actions.getReadTags(100)
                if (myTags != null && myTags.isNotEmpty()) {
                    inventoryListener?.onTagsRead(myTags)
                }
            }
        }

        override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents?) {
            if (rfidStatusEvents == null) return
            val type = rfidStatusEvents.StatusEventData.statusEventType
            Log.d(TAG, "Status Notification: $type")
            if (type == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                val trigger = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.handheldEvent
                if (trigger == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    if(isReading()) {
                        Log.d(TAG, "Ignoring trigger event while reading")
                        return
                    }
                    inventoryListener?.onTriggerChanged(true)
                    performInventory()
                } else if (trigger == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                    inventoryListener?.onTriggerChanged(false)
                    stopInventory()
                }
            } else if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                Log.d(TAG, "Reader disconnected, To Do: Handle Bluetooth Inverface Auto-Reconnect")
                executeOnExecutor { handleDisconnect() }
            } else if (type == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
                reading = true
                inventoryListener?.onInventoryStateChanged(true)
            } else if (type == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
                reading = false
                inventoryListener?.onInventoryStateChanged(false)
            }
        }
    }

    companion object {
        private const val TAG = "RfidHandler"
        private const val REGION_CODE = "USA"
    }
}
