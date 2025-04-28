package com.outliers.bluetoothcheck

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.outliers.bluetoothcheck.MainActivity
import java.util.UUID

class KidsBleManager(private val context: Context) {
    private val TAG = "KidsBleManager"

    // BLE service and characteristic UUIDs (match these in the companion app)
    private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val WIFI_SSID_CHAR_UUID = UUID.fromString("00001235-0000-1000-8000-00805f9b34fb")
    private val WIFI_PASSWORD_CHAR_UUID = UUID.fromString("00001236-0000-1000-8000-00805f9b34fb")

    private var bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    // Store Wi-Fi credentials
    private var wifiSsid: String? = null
    private var wifiPassword: String? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startServer() {
        setupGattServer()
        if (ActivityCompat.checkSelfPermission(
                context,  // Changed from 'this' to 'context'
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        startAdvertising()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        bluetoothLeAdvertiser = bluetoothManager.adapter.bluetoothLeAdvertiser

        // Create BLE Service
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // SSID Characteristic
        val ssidCharacteristic = BluetoothGattCharacteristic(
            WIFI_SSID_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Password Characteristic
        val passwordCharacteristic = BluetoothGattCharacteristic(
            WIFI_PASSWORD_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(ssidCharacteristic)
        service.addCharacteristic(passwordCharacteristic)

        // Start the GATT server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE Advertising failed with error: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(
                device, requestId, characteristic, preparedWrite,
                responseNeeded, offset, value
            )

            when (characteristic.uuid) {
                WIFI_SSID_CHAR_UUID -> {
                    wifiSsid = String(value)
                    Log.d(TAG, "SSID received: $wifiSsid")
                }
                WIFI_PASSWORD_CHAR_UUID -> {
                    wifiPassword = String(value)
                    Log.d(TAG, "Wi-Fi password received: $wifiPassword")

                    // Both credentials received, attempt connection
                    if (wifiSsid != null && wifiPassword != null) {
                        // Pass credentials to MainActivity
                        if (context is MainActivity) {
                            context.onWifiCredentialsReceived(wifiSsid!!, wifiPassword!!)
                        }
                    }
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun shutdown() {
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        if (ActivityCompat.checkSelfPermission(
                context,  // Changed from 'this' to 'context'
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        gattServer?.close()
    }
}