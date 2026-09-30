package com.zebra.rfid.demo.sdksample;

import static android.util.Log.d;

import android.util.Log;

import com.zebra.rfid.api3.BuildConfig;
import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RegionInfo;
import com.zebra.rfid.api3.RegulatoryConfig;
import com.zebra.rfid.api3.RfidEventsListener;

import java.util.ArrayList;
import java.util.List;

/** Stateless helpers around the Zebra RFID SDK. Callers own threading. */
final class RfidUtility {

    private static final String TAG = "RFID_SAMPLE";
    static final String DEFAULT_READER_NAME = "RFID-TC701";

    private RfidUtility() {
    }

    static String getSdkVersion() {
        return BuildConfig.VERSION_NAME;
    }

    // The built-in TC701 reader reports itself as "QC"/"QC_READER".
    static String toDisplayName(String rawName) {
        if (isEmpty(rawName) || "QC".equalsIgnoreCase(rawName) || "QC_READER".equalsIgnoreCase(rawName)) {
            return DEFAULT_READER_NAME;
        }
        return rawName;
    }

    static String displayNameOf(ReaderDevice device, RFIDReader reader) {
        if (device != null && !isEmpty(device.getName())) {
            return toDisplayName(device.getName());
        }
        return toDisplayName(reader != null ? reader.getHostName() : null);
    }

    static ENUM_TRANSPORT firstTransport() throws InvalidUsageException {
        for (ENUM_TRANSPORT candidate : ENUM_TRANSPORT.values()) {
            if (candidate != ENUM_TRANSPORT.ALL) {
                return candidate;
            }
        }
        throw new InvalidUsageException("No RFID transports are available", "");
    }

    // Leaves `readers` on the first transport that reports readers.
    static ArrayList<ReaderDevice> scanTransports(Readers readers) throws InvalidUsageException {
        InvalidUsageException lastException = null;
        for (ENUM_TRANSPORT candidate : ENUM_TRANSPORT.values()) {
            if (candidate == ENUM_TRANSPORT.ALL) {
                continue;
            }
            try {
                readers.setTransport(candidate);
                ArrayList<ReaderDevice> found = readers.GetAvailableRFIDReaderList();
                if (found != null && !found.isEmpty()) {
                    d(TAG, "Found Reader, Size=" + found.size() + ", Transport=" + candidate);
                    return found;
                }
                d(TAG, "No readers found for Transport=" + candidate);
            } catch (InvalidUsageException e) {
                lastException = e;
                d(TAG, "Transport discovery failed for " + candidate + ": " + e.getInfo());
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return new ArrayList<>();
    }

    // Matches by address when both sides have one, otherwise by name.
    static ReaderDevice findReader(List<ReaderDevice> devices, String name, String address) {
        if (devices == null || (name == null && address == null)) {
            return null;
        }
        for (ReaderDevice device : devices) {
            boolean matches = !isEmpty(address) && !isEmpty(device.getAddress())
                    ? address.equals(device.getAddress())
                    : name != null && name.equals(device.getName());
            if (matches) {
                return device;
            }
        }
        return null;
    }

    static boolean isSameReader(ReaderDevice device, ReaderDevice current, RFIDReader reader) {
        if (device == null || device.getName() == null || reader == null) {
            return false;
        }
        String devName = toDisplayName(device.getName());
        if (current != null && devName.equals(toDisplayName(current.getName()))) {
            return true;
        }
        return devName.equals(toDisplayName(reader.getHostName()));
    }

    // Returns null on success, otherwise SDK diagnostic text.
    static String configureRegion(RFIDReader reader, String regionCode) {
        try {
            RegulatoryConfig regCfg = reader.Config.getRegulatoryConfig();
            for (int i = 0; i < reader.ReaderCapabilities.SupportedRegions.length(); i++) {
                RegionInfo regionInfo = reader.ReaderCapabilities.SupportedRegions.getRegionInfo(i);
                if (regionCode.equalsIgnoreCase(regionInfo.getRegionCode())) {
                    regCfg.setRegion(regionInfo.getRegionCode());
                    regCfg.setIsHoppingOn(regionInfo.isHoppingConfigurable());
                    regCfg.setEnabledChannels(regionInfo.getSupportedChannels());
                    regCfg.setStandardName(regionInfo.getName());
                    reader.Config.setRegulatoryConfig(regCfg);
                    return null;
                }
            }
            return "region " + regionCode + " not supported by reader";
        } catch (Exception e) {
            Log.e(TAG, "configureRegion failed", e);
            return e.getMessage();
        }
    }

    static void enableEvents(RFIDReader reader, RfidEventsListener listener) throws Exception {
        reader.Events.addEventsListener(listener);
        reader.Events.setHandheldEvent(true);
        reader.Events.setTagReadEvent(true);
        reader.Events.setAttachTagDataWithReadEvent(false);
        reader.Events.setInventoryStartEvent(true);
        reader.Events.setInventoryStopEvent(true);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
