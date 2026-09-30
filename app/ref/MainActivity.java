package com.zebra.rfid.demo.sdksample;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.button.MaterialButton;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.TagData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sample app to connect to the reader and perform inventory.
 */
public class MainActivity extends AppCompatActivity
        implements RFIDHandler.ConnectionListener, RFIDHandler.InventoryListener {

    private enum MessageType { INFO, SUCCESS, WARNING, ERROR }

    private TextView statusTextViewRFID = null;
    private ProgressBar progressBar;
    private View readingOverlay;
    private MaterialButton btnInventoryStart;
    private ColorStateList defaultStartButtonTint;
    private MaterialButton btnConnect;
    private MaterialButton btnDisconnect;
    private ColorStateList defaultConnectTint;
    private ColorStateList defaultDisconnectTint;
    private RecyclerView tagRecyclerView;
    private TagAdapter tagAdapter;
    private TextView listHeaderPrimary;

    private final Handler uiThrottleHandler = new Handler(Looper.getMainLooper());
    private final List<TagData> pendingTagBuffer = new ArrayList<>();
    private Runnable batchUpdateRunnable;
    private boolean acceptingTagData;

    private RFIDHandler rfidHandler;
    private ToneFeedback toneFeedback;
    final static String TAG = "RFID_SAMPLE";
    private boolean rfidPermissionReady = false;
    private AlertDialog readerSelectionDialog;
    private AlertDialog statusDialog;
    private RFIDHandler.ConnectionState connectionState = RFIDHandler.ConnectionState.DISCONNECTED;

    private long connectStartTime = 0;

    @SuppressLint("InlinedApi")
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean bluetoothConnect = result.getOrDefault(Manifest.permission.BLUETOOTH_CONNECT, false);
                Boolean bluetoothScan = result.getOrDefault(Manifest.permission.BLUETOOTH_SCAN, false);
                if (Boolean.TRUE.equals(bluetoothConnect) && Boolean.TRUE.equals(bluetoothScan)) {
                    rfidPermissionReady = true;
                    rfidHandler.onForeground(this, this);
                } else {
                    Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        Log.d(TAG, "App Start Up on " + Build.MODEL);

        // RFID Handler
        statusTextViewRFID = findViewById(R.id.textViewStatusrfid);
        progressBar = findViewById(R.id.progressBar);
        readingOverlay = findViewById(R.id.readingOverlay);
        btnInventoryStart = findViewById(R.id.btnInventoryStart);
        defaultStartButtonTint = btnInventoryStart.getBackgroundTintList();
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        defaultConnectTint = btnConnect.getBackgroundTintList();
        defaultDisconnectTint = btnDisconnect.getBackgroundTintList();
        setConnectionButtons(false);
        tagAdapter = new TagAdapter();
        tagRecyclerView = findViewById(R.id.tagRecyclerView);
        tagRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        tagRecyclerView.setAdapter(tagAdapter);
        listHeaderPrimary = findViewById(R.id.listHeaderPrimary);

        rfidHandler = new RFIDHandler(this);
        toneFeedback = new ToneFeedback();

        // Handling Runtime BT permissions for Android 12 and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean connectGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean scanGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            if (!connectGranted || !scanGranted) {
                requestPermissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT});
            } else {
                rfidPermissionReady = true;
            }
        } else {
            rfidPermissionReady = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        acceptingTagData = true;
        if (rfidPermissionReady) {
            rfidHandler.onForeground(this, this);
        }
    }

    @Override
    protected void onPause() {
        if (rfidHandler.isReading()) {
            rfidHandler.stopInventory();
        }
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        cancelPendingTagBatch();
        rfidHandler.onBackground();
        super.onStop();
        Log.d(TAG, "onStop");
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        Log.d(TAG, "onPostResume");
        clearStatusMessages();
        updateTitleWithSdkVersion(RfidUtility.getSdkVersion());
    }

    private void updateTitleWithSdkVersion(String version) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.title_model_sdk, Build.MODEL, version));
        }
    }

    @Override
    protected void onDestroy() {
        cancelPendingTagBatch();
        if (statusDialog != null && statusDialog.isShowing()) {
            statusDialog.dismiss();
        }
        statusHandler.removeCallbacksAndMessages(null);
        uiThrottleHandler.removeCallbacksAndMessages(null);
        if (readerSelectionDialog != null && readerSelectionDialog.isShowing()) {
            readerSelectionDialog.dismiss();
        }
        super.onDestroy();
        rfidHandler.onDestroy();
        toneFeedback.release();
    }

    private void cancelPendingTagBatch() {
        cancelPendingTagBatch(false);
    }

    private void cancelPendingTagBatch(boolean keepAcceptingTagData) {
        Runnable pendingRunnable;
        synchronized (pendingTagBuffer) {
            acceptingTagData = keepAcceptingTagData;
            pendingTagBuffer.clear();
            pendingRunnable = batchUpdateRunnable;
            batchUpdateRunnable = null;
        }
        if (pendingRunnable != null) {
            uiThrottleHandler.removeCallbacks(pendingRunnable);
        }
    }

    private final StringBuilder statusBuffer = new StringBuilder();
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable showStatusRunnable;
    private final Runnable dismissStatusDialog = () -> {
        if (statusDialog != null && statusDialog.isShowing()) {
            statusDialog.dismiss();
        }
    };

    private void updateUiStatus(final String status) {
        runOnUiThread(() -> {
            if (status != null && !status.isEmpty()) {
                setStatusText(status);
                if (!statusBuffer.toString().contains(status)) {
                    statusBuffer.append(status).append("\n");
                }

                if (showStatusRunnable != null) {
                    statusHandler.removeCallbacks(showStatusRunnable);
                }
                showStatusRunnable = MainActivity.this::showStatusDialog;
                statusHandler.postDelayed(showStatusRunnable, 500);
            }
        });
    }

    // Updates the status line without queuing the status dialog.
    private void setStatusText(String status) {
        Log.d(TAG, "statusTextViewRFID " + status);
        statusTextViewRFID.setText(status);
    }

    @Override
    public void onConnectionStateChanged(RFIDHandler.ConnectionState state, String readerName, String detail) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            RFIDHandler.ConnectionState previous = connectionState;
            connectionState = state;
            // Only DISCOVERING -> CONNECTING continues the same attempt; anything else starts a new one.
            boolean attemptStarted = !(previous == RFIDHandler.ConnectionState.DISCOVERING
                    && state == RFIDHandler.ConnectionState.CONNECTING);
            switch (state) {
                case DISCOVERING:
                    showProgress(true);
                    applyButtonState(btnConnect, defaultConnectTint, false);
                    if (attemptStarted) {
                        connectStartTime = System.currentTimeMillis();
                        clearStatusMessages();
                    }
                    setStatusText(getString(R.string.status_discovering));
                    break;
                case CONNECTING:
                    showProgress(true);
                    applyButtonState(btnConnect, defaultConnectTint, false);
                    if (attemptStarted) {
                        connectStartTime = System.currentTimeMillis();
                        clearStatusMessages();
                    }
                    updateUiStatus(getString(R.string.status_connecting_to, readerName));
                    break;
                case CONNECTED:
                    showProgress(false);
                    setConnectionButtons(true);
                    toneFeedback.playConnected();
                    if (readerSelectionDialog != null && readerSelectionDialog.isShowing()) {
                        readerSelectionDialog.dismiss();
                    }
                    long connectTime = connectStartTime > 0 ? (System.currentTimeMillis() - connectStartTime) : 0;
                    updateUiStatus(getString(R.string.status_connected, readerName) + "\n"
                            + getString(R.string.label_connect_time, connectTime));
                    break;
                case DISCONNECTED:
                    setConnectionButtons(false);
                    toneFeedback.playDisconnected();
                    sendToast(getString(R.string.status_disconnecting), MessageType.WARNING);
                    updateUiStatus(getString(R.string.status_disconnected));
                    break;
                case NO_READERS:
                    showProgress(false);
                    setConnectionButtons(false);
                    sendToast(getString(R.string.status_no_readers), MessageType.ERROR);
                    setStatusText(getString(R.string.status_no_reader_found));
                    break;
                case DISCOVERY_FAILED:
                    showProgress(false);
                    setConnectionButtons(false);
                    sendToast(getString(R.string.status_failed_get_readers) + "\n" + detail, MessageType.ERROR);
                    break;
                case FAILED:
                    showProgress(false);
                    setConnectionButtons(false);
                    updateUiStatus(detail == null ? getString(R.string.status_reader_not_found)
                            : getString(R.string.status_connection_failed, detail));
                    break;
            }
        });
    }

    @Override
    public void onReaderSelectionRequired(ArrayList<ReaderDevice> readerList, RFIDHandler.ReaderSelectionListener listener) {
        runOnUiThread(() -> {
            showProgress(false);
            setConnectionButtons(false);
            showReaderSelection(readerList, listener);
        });
    }

    @Override
    public void onReaderAppeared(String readerName, boolean usbHostMode) {
        sendToast(getString(R.string.toast_reader_appeared, readerName), MessageType.INFO);
        if (usbHostMode) {
            sendToast(getString(R.string.toast_reader_usb_host_mode), MessageType.INFO);
        }
    }

    @Override
    public void onReaderDisappeared(String readerName) {
        sendToast(getString(R.string.toast_reader_disappeared, readerName), MessageType.WARNING);
    }

    @Override
    public void onInventoryStateChanged(boolean reading) {
        showReadingProgress(reading);
        setStartButtonReading(reading);
    }

    @Override
    public void onInventoryUnavailable(boolean startRequested) {
        sendToast(getString(startRequested ? R.string.toast_not_connected_start : R.string.toast_not_connected_stop),
                MessageType.WARNING);
    }

    private void clearStatusMessages() {
        runOnUiThread(() -> statusBuffer.setLength(0));
    }

    private void showStatusDialog() {
        runOnUiThread(() -> {
            if (statusBuffer.length() == 0 || isFinishing() || isDestroyed()) {
                return;
            }
            String message = statusBuffer.toString().trim();
            if (statusDialog != null && statusDialog.isShowing()) {
                statusDialog.setMessage(message);
            } else {
                statusDialog = new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(R.string.dialog_title_rfid_status)
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, which) -> clearStatusMessages())
                        .setOnDismissListener(d -> clearStatusMessages())
                        .show();
            }
            statusHandler.removeCallbacks(dismissStatusDialog);
            statusHandler.postDelayed(dismissStatusDialog, 2000);
        });
    }

    private void showProgress(boolean show) {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void showReadingProgress(boolean show) {
        runOnUiThread(() -> {
            if (readingOverlay != null) {
                readingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void setStartButtonReading(final boolean reading) {
        runOnUiThread(() -> applyButtonState(btnInventoryStart, defaultStartButtonTint, !reading));
    }

    private void setConnectionButtons(final boolean connected) {
        runOnUiThread(() -> {
            applyButtonState(btnConnect, defaultConnectTint, !connected);
            applyButtonState(btnDisconnect, defaultDisconnectTint, connected);
        });
    }

    private void applyButtonState(MaterialButton button, ColorStateList defaultTint, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setBackgroundTintList(enabled ? defaultTint
                : ColorStateList.valueOf(ContextCompat.getColor(this, android.R.color.darker_gray)));
    }

    private void clearTagList() {
        cancelPendingTagBatch(true);
        tagAdapter.clear();
        if (listHeaderPrimary != null) {
            listHeaderPrimary.setText(R.string.label_tag_id);
        }
    }

    public void startInventory(View view) {
        clearTagList();
        rfidHandler.performInventory();
    }

    public void connectReader(View view) {
        clearStatusMessages();
        rfidHandler.performConnect();
    }

    public void disconnectReader(View view) {
        clearStatusMessages();
        rfidHandler.performDisconnect();
    }

    public void stopInventory(View view) {
        rfidHandler.stopInventory();
    }

    @Override
    public void onTagsRead(TagData[] tagData) {
        if (tagData == null || tagData.length == 0) {
            return;
        }
        synchronized (pendingTagBuffer) {
            if (!acceptingTagData) {
                return;
            }
            Collections.addAll(pendingTagBuffer, tagData);
            if (batchUpdateRunnable == null) {
                batchUpdateRunnable = () -> {
                    TagData[] batch;
                    synchronized (pendingTagBuffer) {
                        batch = pendingTagBuffer.toArray(new TagData[0]);
                        pendingTagBuffer.clear();
                        batchUpdateRunnable = null;
                    }
                    boolean isNewTagAdded = tagAdapter.addOrUpdateTags(batch);
                    if (listHeaderPrimary != null) {
                        String header = getString(R.string.label_header_summary, tagAdapter.getUniqueTagCount(), tagAdapter.getTotalReadCount());
                        listHeaderPrimary.setText(header);
                    }
                    if (isNewTagAdded) {
                        toneFeedback.playNewTag();
                        tagRecyclerView.scrollToPosition(tagAdapter.getItemCount() - 1);
                    }
                };
                // Increase delay slightly to allow more tags to accumulate during high-speed scans
                uiThrottleHandler.postDelayed(batchUpdateRunnable, 150);
            }
        }
    }

    @Override
    public void onTriggerChanged(boolean pressed) {
        if (pressed) {
            runOnUiThread(this::clearTagList);
            rfidHandler.performInventory();
        } else {
            rfidHandler.stopInventory();
        }
    }

    private void sendToast(String val, MessageType type) {
        runOnUiThread(() -> {
            if (val == null || val.isEmpty() || isDestroyed()) return;

            Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), val, Snackbar.LENGTH_LONG);
            
            // Modern Material 3 SLIDE animation
            snackbar.setAnimationMode(Snackbar.ANIMATION_MODE_SLIDE);
            
            int backgroundColor;
            int textColor = ContextCompat.getColor(this, android.R.color.white);

            switch (type) {
                case ERROR:
                    backgroundColor = ContextCompat.getColor(this, android.R.color.holo_red_dark);
                    break;
                case SUCCESS:
                    backgroundColor = ContextCompat.getColor(this, android.R.color.holo_green_dark);
                    break;
                case WARNING:
                    backgroundColor = ContextCompat.getColor(this, android.R.color.holo_orange_dark);
                    break;
                default:
                    backgroundColor = ContextCompat.getColor(this, android.R.color.black);
                    break;
            }

            snackbar.setBackgroundTint(backgroundColor);
            snackbar.setTextColor(textColor);
            
            // Add an action button to make it interactive and "easy to read" (gives user control)
            snackbar.setAction(android.R.string.ok, v -> snackbar.dismiss());
            snackbar.setActionTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light));

            snackbar.show();
        });
    }

    private void showReaderSelection(ArrayList<ReaderDevice> readerList, RFIDHandler.ReaderSelectionListener listener) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            if (readerSelectionDialog != null && readerSelectionDialog.isShowing()) {
                readerSelectionDialog.dismiss();
            }
            // Restart the connect timer once the user picks a reader.
            connectionState = RFIDHandler.ConnectionState.DISCONNECTED;
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_select_reader, null);
            RecyclerView recyclerView = dialogView.findViewById(R.id.readerRecyclerView);
            recyclerView.setLayoutManager(new LinearLayoutManager(this));

            ReaderAdapter adapter = new ReaderAdapter(readerList, selectedDevice -> {
                if (readerSelectionDialog != null && readerSelectionDialog.isShowing()) {
                    readerSelectionDialog.dismiss();
                }
                if (listener != null) {
                    listener.onReaderSelected(selectedDevice);
                }
            });

            recyclerView.setAdapter(adapter);

            readerSelectionDialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_title_select_reader)
                    .setView(dialogView)
                    .setNegativeButton(android.R.string.cancel, (d, which) -> d.cancel())
                    .setOnCancelListener(d -> {
                        updateUiStatus(getString(R.string.status_select_reader));
                        setConnectionButtons(false);
                    })
                    .setCancelable(true)
                    .show();
        });
    }

}
