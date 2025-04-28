package com.outliers.bluetoothcheck

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import java.util.UUID

class KidsBleManager(private val context: Context) {

    private val TAG = "KidsBleManager"

    /* UUIDs */
    private val SERVICE_UUID            = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val WIFI_SSID_CHAR_UUID     = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
    private val WIFI_PASSWORD_CHAR_UUID = UUID.fromString("00001236-0000-1000-8000-00805f9b34fb")

    private val btManager  = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var advertiser : BluetoothLeAdvertiser? = null
    private var gattServer : BluetoothGattServer?    = null

    private var wifiSsid: String? = null
    private var wifiPass: String? = null

    /* ---------- life-cycle ---------- */

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startServer() {
        setupGattServer()
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
            != PackageManager.PERMISSION_GRANTED) return
        startAdvertising()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun shutdown() {
        advertiser?.stopAdvertising(adCallback)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) return
        gattServer?.close()
    }

    /* ---------- GATT ---------- */

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        advertiser = btManager.adapter.bluetoothLeAdvertiser

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                WIFI_SSID_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                WIFI_PASSWORD_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )

        gattServer = btManager.openGattServer(context, gattCallback)
        gattServer?.addService(service)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, adCallback)
    }

    private val adCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    /* ---------- handle writes ---------- */

    private val gattCallback = object : BluetoothGattServerCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            when (ch.uuid) {
                WIFI_SSID_CHAR_UUID     -> { wifiSsid = String(value); Log.d(TAG,"SSID:$wifiSsid") }
                WIFI_PASSWORD_CHAR_UUID -> {
                    wifiPass = String(value); Log.d(TAG,"PASS:******")
                    if (wifiSsid != null && wifiPass != null && context is MainActivity) {
                        // Hand off to UI thread to avoid exceptions :contentReference[oaicite:3]{index=3}
                        (context as Activity).runOnUiThread {
                            context.onWifiCredentialsReceived(wifiSsid!!, wifiPass!!)
                        }
                    }
                }
            }
            if (responseNeeded)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }
}
