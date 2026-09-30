package com.zebra.rfid.ai

import android.util.Log
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener

/**
 * Stateless helpers around the Zebra RFID SDK.
 */
object RfidUtility {

    private const val TAG = "RfidUtility"
    const val DEFAULT_READER_NAME = "RFID-TC701"

    /** The built-in TC701 reader reports itself as "QC"/"QC_READER". */
    fun toDisplayName(rawName: String?): String {
        if (rawName.isNullOrEmpty() || "QC".equals(rawName, ignoreCase = true) || "QC_READER".equals(rawName, ignoreCase = true)) {
            return DEFAULT_READER_NAME
        }
        return rawName
    }

    fun displayNameOf(device: ReaderDevice?, reader: RFIDReader?): String {
        if (device != null && !device.name.isNullOrEmpty()) {
            return toDisplayName(device.name)
        }
        return toDisplayName(reader?.hostName)
    }

    @Throws(InvalidUsageException::class)
    fun firstTransport(): ENUM_TRANSPORT {
        for (candidate in ENUM_TRANSPORT.values()) {
            if (candidate != ENUM_TRANSPORT.ALL) {
                return candidate
            }
        }
        throw InvalidUsageException("No RFID transports are available", "")
    }

    /** Leaves `readers` on the first transport that reports readers. */
    @Throws(InvalidUsageException::class)
    fun scanTransports(readers: Readers): ArrayList<ReaderDevice> {
        var lastException: InvalidUsageException? = null
        for (candidate in ENUM_TRANSPORT.values()) {
            if (candidate == ENUM_TRANSPORT.ALL) {
                continue
            }
            try {
                readers.setTransport(candidate)
                val found = readers.GetAvailableRFIDReaderList()
                if (found != null && found.isNotEmpty()) {
                    Log.d(TAG, "Found Reader, Size=${found.size}, Transport=$candidate")
                    return found
                }
                Log.d(TAG, "No readers found for Transport=$candidate")
            } catch (e: InvalidUsageException) {
                lastException = e
                Log.d(TAG, "Transport discovery failed for $candidate: ${e.info}")
            }
        }
        if (lastException != null) {
            throw lastException
        }
        return ArrayList()
    }

    /** Matches by address when both sides have one, otherwise by name. */
    fun findReader(devices: List<ReaderDevice>?, name: String?, address: String?): ReaderDevice? {
        if (devices == null || (name == null && address == null)) {
            return null
        }
        for (device in devices) {
            val matches = if (!address.isNullOrEmpty() && !device.address.isNullOrEmpty()) {
                address == device.address
            } else {
                name != null && name == device.name
            }
            if (matches) {
                return device
            }
        }
        return null
    }

    fun isSameReader(device: ReaderDevice?, current: ReaderDevice?, reader: RFIDReader?): Boolean {
        if (device == null || device.name == null || reader == null) {
            return false
        }
        val devName = toDisplayName(device.name)
        if (current != null && devName == toDisplayName(current.name)) {
            return true
        }
        return devName == toDisplayName(reader.hostName)
    }

    /** Returns null on success, otherwise SDK diagnostic text. */
    fun configureRegion(reader: RFIDReader, regionCode: String = "USA"): String? {
        try {
            val regCfg = reader.Config.regulatoryConfig
            val supportedRegions = reader.ReaderCapabilities.SupportedRegions
            for (i in 0 until supportedRegions.length()) {
                val regionInfo = supportedRegions.getRegionInfo(i)
                if (regionCode.equals(regionInfo.regionCode, ignoreCase = true)) {
                    regCfg.region = regionInfo.regionCode
                    regCfg.setIsHoppingOn(regionInfo.isHoppingConfigurable)
                    regCfg.setEnabledChannels(regionInfo.supportedChannels)
                    regCfg.setStandardName(regionInfo.name)
                    reader.Config.regulatoryConfig = regCfg
                    return null
                }
            }
            return "Region $regionCode not supported by reader"
        } catch (e: Exception) {
            Log.e(TAG, "configureRegion failed", e)
            return e.message
        }
    }

    @Throws(Exception::class)
    fun enableEvents(reader: RFIDReader, listener: RfidEventsListener) {
        reader.Events.addEventsListener(listener)
        reader.Events.setHandheldEvent(true)
        reader.Events.setTagReadEvent(true)
        reader.Events.setAttachTagDataWithReadEvent(false)
        reader.Events.setInventoryStartEvent(true)
        reader.Events.setInventoryStopEvent(true)
    }
}
