package com.zebra.rfid.demo.sdksample;

import android.content.Context;
import android.util.Log;

import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.IRFIDLogger;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.TagData;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Owns the Zebra RFID SDK: init, discovery, connect, disconnect and inventory.
 * Results are reported through {@link ConnectionListener} and {@link InventoryListener}; UI, strings and sounds belong to the caller.
 */
class RFIDHandler implements Readers.RFIDReaderEventHandler {

    final static String TAG = "RFID_SAMPLE";
    // Applied when the reader reports RFID_READER_REGION_NOT_CONFIGURED.
    private static final String REGION_CODE = "USA";

    enum ConnectionState { DISCOVERING, CONNECTING, CONNECTED, DISCONNECTED, NO_READERS, DISCOVERY_FAILED, FAILED }

    public interface ReaderSelectionListener {
        void onReaderSelected(ReaderDevice readerDevice);
    }

    /** Reader discovery and connection events. Callbacks arrive on background threads. */
    public interface ConnectionListener {
        // detail carries SDK diagnostic text for DISCOVERY_FAILED/FAILED; null for FAILED means no reader was found.
        void onConnectionStateChanged(ConnectionState state, String readerName, String detail);
        void onReaderSelectionRequired(ArrayList<ReaderDevice> readerList, ReaderSelectionListener listener);
        void onReaderAppeared(String readerName, boolean usbHostMode);
        void onReaderDisappeared(String readerName);
    }

    /** Inventory, tag and trigger events. Callbacks arrive on background threads. */
    public interface InventoryListener {
        void onInventoryStateChanged(boolean reading);
        void onInventoryUnavailable(boolean startRequested);
        void onTagsRead(TagData[] tagData);
        void onTriggerChanged(boolean pressed);
    }

    private final Context appContext;
    // Single thread serializes every SDK call, so no method needs to be synchronized.
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private volatile ConnectionListener connectionListener;
    private volatile InventoryListener inventoryListener;
    private volatile Readers readers;
    private volatile ArrayList<ReaderDevice> availableRFIDReaderList;
    private volatile ReaderDevice readerDevice;
    private volatile RFIDReader reader;
    private EventHandler eventHandler;
    private volatile boolean reading = false;
    private volatile boolean active = false;
    private boolean wasConnected = false;
    // Reader the user picked; retried automatically on reconnect before asking again.
    private String preferredReaderName;
    private String preferredReaderAddress;

    RFIDHandler(Context context) {
        appContext = context.getApplicationContext();
    }

    //
    // Public API (any thread)
    //

    // App entered the foreground: (re)initialize the SDK and connect the reader.
    void onForeground(ConnectionListener connectionListener, InventoryListener inventoryListener) {
        this.connectionListener = connectionListener;
        this.inventoryListener = inventoryListener;
        active = true;
        IRFIDLogger.getLogger("sample app").EnableDebugLogs(false);
        executeOnExecutor(this::initOrConnect);
    }

    // App entered the background: disconnect, clean up and dispose the SDK.
    void onBackground() {
        active = false;
        executeOnExecutor(this::dispose);
    }

    void onDestroy() {
        active = false;
        executeOnExecutor(this::dispose);
        executorService.shutdown();
    }

    public void performConnect() {
        executeOnExecutor(this::initOrConnect);
    }

    public void performDisconnect() {
        executeOnExecutor(this::handleDisconnect);
    }

    public void performInventory() {
        executeOnExecutor(() -> runInventory(true));
    }

    public void stopInventory() {
        executeOnExecutor(() -> runInventory(false));
    }

    public boolean isReading() {
        return reading;
    }

    //
    // SDK work (executor thread only)
    //

    private void executeOnExecutor(Runnable runnable) {
        try {
            executorService.execute(runnable);
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Task execution rejected", e);
        }
    }

    private void notifyState(ConnectionState state, String detail) {
        connectionListener.onConnectionStateChanged(state, getReaderDisplayName(), detail);
    }

    private void initOrConnect() {
        if (!active) {
            return;
        }
        if (readers == null) {
            notifyState(ConnectionState.DISCOVERING, null);
            createInstance();
            return;
        }
        if (isReaderConnected()) {
            return;
        }
        getAvailableReader();
        if (availableRFIDReaderList != null && availableRFIDReaderList.size() > 1 && readerDevice == null) {
            requestReaderSelection();
        } else {
            connectionTask();
        }
    }

    private void createInstance() {
        Log.d(TAG, "createInstance");
        ArrayList<ReaderDevice> discovered = new ArrayList<>();
        String error = null;
        try {
            readers = new Readers(appContext, RfidUtility.firstTransport());
            Readers.attach(this);
            discovered = RfidUtility.scanTransports(readers);
        } catch (InvalidUsageException e) {
            reportError(e);
            error = e.getInfo();
        } catch (Exception e) {
            reportError(e);
            error = "SDK Error: " + e.getMessage();
        }

        availableRFIDReaderList = discovered;
        if (!active) {
            return;
        }
        if (error != null) {
            releaseReaders();
            notifyState(ConnectionState.DISCOVERY_FAILED, error);
        } else if (discovered.isEmpty()) {
            releaseReaders();
            notifyState(ConnectionState.NO_READERS, null);
        } else if (discovered.size() == 1 || findPreferredReader(discovered) != null) {
            connectionTask();
        } else {
            requestReaderSelection();
        }
    }

    private void requestReaderSelection() {
        connectionListener.onReaderSelectionRequired(availableRFIDReaderList, this::onReaderSelected);
    }

    // Called on the UI thread from the reader selection dialog.
    private void onReaderSelected(ReaderDevice selectedDevice) {
        executeOnExecutor(() -> {
            readerDevice = selectedDevice;
            reader = selectedDevice != null ? selectedDevice.getRFIDReader() : null;
            preferredReaderName = selectedDevice != null ? selectedDevice.getName() : null;
            preferredReaderAddress = selectedDevice != null ? selectedDevice.getAddress() : null;
            connectionTask();
        });
    }

    private ReaderDevice findPreferredReader(ArrayList<ReaderDevice> devices) {
        return RfidUtility.findReader(devices, preferredReaderName, preferredReaderAddress);
    }

    private void connectionTask() {
        Log.d(TAG, "connectionTask");
        if (!active || isReaderConnected()) {
            return;
        }
        getAvailableReader();

        if (reader == null && availableRFIDReaderList != null && availableRFIDReaderList.size() > 1) {
            requestReaderSelection();
            return;
        }

        String error = null;
        if (reader != null) {
            notifyState(ConnectionState.CONNECTING, null);
            error = handleConnect();
            if (error != null && error.contains("RFID_READER_REGION_NOT_CONFIGURED")) {
                error = RfidUtility.configureRegion(reader, REGION_CODE);
                if (error == null) {
                    error = handleConnect();
                }
            }
            if (error == null) {
                configureReader();
                notifyState(ConnectionState.CONNECTED, null);
                return;
            }
        }

        Log.d(TAG, "connectionTask failed: " + error);
        refreshAvailableReaders();
        notifyState(ConnectionState.FAILED, error);
        if (active && availableRFIDReaderList != null && !availableRFIDReaderList.isEmpty()) {
            requestReaderSelection();
        }
    }

    private void refreshAvailableReaders() {
        if (readers != null) {
            try {
                ArrayList<ReaderDevice> found = RfidUtility.scanTransports(readers);
                if (!found.isEmpty()) {
                    availableRFIDReaderList = found;
                    return;
                }
            } catch (Exception e) {
                Log.d(TAG, "refreshAvailableReaders failed: " + e.getMessage());
            }
        }
        getAvailableReader();
    }

    private void getAvailableReader() {
        Log.d(TAG, "getAvailableReader");
        if (readers != null) {
            try {
                ArrayList<ReaderDevice> availableReaders = readers.GetAvailableRFIDReaderList();
                if (availableReaders != null && !availableReaders.isEmpty()) {
                    availableRFIDReaderList = availableReaders;
                    Log.d(TAG, "Available readers to connect = " + availableRFIDReaderList.size());
                    if (availableRFIDReaderList.size() == 1) {
                        readerDevice = availableRFIDReaderList.get(0);
                        reader = readerDevice.getRFIDReader();
                    } else {
                        readerDevice = findPreferredReader(availableRFIDReaderList);
                        reader = readerDevice != null ? readerDevice.getRFIDReader() : null;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "getAvailableReader failed", t);
            }
        }
    }

    // Returns null on success, otherwise SDK diagnostic text.
    private String handleConnect() {
        RFIDReader r = reader;
        Log.d(TAG, "connect " + getReaderDisplayName());
        try {
            if (!r.isConnected()) {
                r.connect();
            }
            if (r.isConnected()) {
                wasConnected = true;
                return null;
            }
            return "reader did not report connected";
        } catch (OperationFailureException ofe) {
            Log.d(TAG, "OperationFailureException " + ofe.getVendorMessage());
            return ofe.getVendorMessage() + " " + ofe.getResults();
        } catch (InvalidUsageException iue) {
            Log.e(TAG, "InvalidUsageException during connect", iue);
            return iue.getInfo();
        } catch (Throwable t) {
            Log.e(TAG, "Error during connect", t);
            return t.getMessage();
        }
    }

    private void configureReader() {
        Log.d(TAG, "configureReader " + getReaderDisplayName());
        RFIDReader r = reader;
        if (r != null && r.isConnected()) {
            try {
                if (eventHandler == null)
                    eventHandler = new EventHandler();
                RfidUtility.enableEvents(r, eventHandler);
                reading = false;
            } catch (Exception e) {
                Log.e(TAG, "configureReader failed", e);
            }
        }
    }

    private void handleDisconnect() {
        if (disconnectInternal()) {
            notifyState(ConnectionState.DISCONNECTED, null);
        }
    }

    // Returns true if a previously connected reader was disconnected.
    private boolean disconnectInternal() {
        Log.d(TAG, "Disconnect");
        RFIDReader r = reader;
        if (r == null) {
            return false;
        }
        boolean hadConnection = wasConnected;
        wasConnected = false;
        try {
            if (eventHandler != null)
                r.Events.removeEventsListener(eventHandler);
            r.disconnect();
        } catch (OperationFailureException ofe) {
            Log.d(TAG, "OperationFailureException ofe=" + ofe.getVendorMessage());
        } catch (LinkageError e1) {
            Log.w(TAG, "Non-fatal SDK/system framework mismatch during disconnect: " + e1.getMessage());
        } catch (Exception e1) {
            Log.d(TAG, "Exception e=" + e1.getMessage());
        }
        return hadConnection;
    }

    private void releaseReaders() {
        Readers r = readers;
        readers = null;
        if (r == null) {
            return;
        }
        Readers.deattach(this);
        try {
            r.Dispose();
        } catch (Exception e) {
            Log.e(TAG, "Exception during readers.Dispose()", e);
        }
    }

    private void dispose() {
        try {
            handleDisconnect();
            releaseReaders();
            reader = null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in dispose", e);
        }
    }

    private void runInventory(boolean start) {
        RFIDReader r = reader;
        if (r == null || !r.isConnected()) {
            inventoryListener.onInventoryUnavailable(start);
            return;
        }
        try {
            if (start) {
                r.Actions.Inventory.perform();
            } else {
                r.Actions.Inventory.stop();
            }
        } catch (Exception e) {
            reportError(e);
        }
    }

    private boolean isReaderConnected() {
        RFIDReader r = reader;
        return r != null && r.isConnected();
    }

    private String getReaderDisplayName() {
        return RfidUtility.displayNameOf(readerDevice, reader);
    }

    private void reportError(Throwable t) {
        Log.e(TAG, "RFID Error Reported: " + t.getMessage(), t);
    }

    //
    // SDK callbacks (SDK threads)
    //

    @Override
    public void RFIDReaderAppeared(ReaderDevice device) {
        Log.d(TAG, "RFIDReaderAppeared " + device.getName());
        boolean connected = isReaderConnected();
        connectionListener.onReaderAppeared(RfidUtility.toDisplayName(device.getName()), connected);
        if (connected && RfidUtility.isSameReader(device, readerDevice, reader)) {
            performDisconnect();
        }
        executeOnExecutor(this::connectionTask);
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice device) {
        Log.d(TAG, "RFIDReaderDisappeared " + device.getName());
        connectionListener.onReaderDisappeared(RfidUtility.toDisplayName(device.getName()));
        if (RfidUtility.isSameReader(device, readerDevice, reader)) {
            performDisconnect();
        }
    }

    public class EventHandler implements RfidEventsListener {
        public void eventReadNotify(RfidReadEvents e) {
            // Route through the executor so this SDK call can't race connect/disconnect.
            executeOnExecutor(() -> {
                RFIDReader r = reader;
                if (r == null) {
                    return;
                }
                TagData[] myTags = r.Actions.getReadTags(100);
                if (myTags != null) {
                    inventoryListener.onTagsRead(myTags);
                }
            });
        }

        public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
            STATUS_EVENT_TYPE type = rfidStatusEvents.StatusEventData.getStatusEventType();
            Log.d(TAG, "Status Notification: " + type);
            if (type == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                HANDHELD_TRIGGER_EVENT_TYPE trigger = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
                if (trigger == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    inventoryListener.onTriggerChanged(true);
                } else if (trigger == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                    inventoryListener.onTriggerChanged(false);
                }
            } else if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                executeOnExecutor(RFIDHandler.this::handleDisconnect);
            } else if (type == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
                reading = true;
                inventoryListener.onInventoryStateChanged(true);
            } else if (type == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
                reading = false;
                inventoryListener.onInventoryStateChanged(false);
            }
        }
    }
}
