package com.outliers.bluetoothcheck

import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

/**
 * v2.1 – silent Wi-Fi join for Device-Owner builds
 * Fixes CalledFromWrongThreadException by marshalling UI work onto the main thread.
 */
class MainActivity : Activity() {

    private val TAG = "WifiSetupBLE"

    private lateinit var wifiManager: WifiManager
    private lateinit var bleManager: KidsBleManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    // UI
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView

    private val PERMISSION_REQUEST = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText           = findViewById(R.id.statusText)
        connectionStatusText = findViewById(R.id.connectionStatusText)

        wifiManager     = getSystemService(Context.WIFI_SERVICE)  as WifiManager
        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bleManager       = KidsBleManager(this)

        requestRuntimePerms()
    }

    /* ---------- permissions ---------- */

    private fun requestRuntimePerms() {
        val toRequest = mutableListOf<String>()
        fun needs(p: String) =
            ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED

        if (needs(Manifest.permission.ACCESS_FINE_LOCATION))
            toRequest += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            needs(Manifest.permission.NEARBY_WIFI_DEVICES))
            toRequest += Manifest.permission.NEARBY_WIFI_DEVICES   // Android 13+ requirement :contentReference[oaicite:0]{index=0}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {      // runtime BT perms
            if (needs(Manifest.permission.BLUETOOTH_SCAN))      toRequest += Manifest.permission.BLUETOOTH_SCAN
            if (needs(Manifest.permission.BLUETOOTH_CONNECT))   toRequest += Manifest.permission.BLUETOOTH_CONNECT
            if (needs(Manifest.permission.BLUETOOTH_ADVERTISE)) toRequest += Manifest.permission.BLUETOOTH_ADVERTISE
        }

        if (toRequest.isNotEmpty())
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQUEST)
        else
            startBleServer()
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray
    ) {
        if (code == PERMISSION_REQUEST && results.all { it == PackageManager.PERMISSION_GRANTED })
            startBleServer()
        else
            Toast.makeText(this, "Setup needs all requested permissions", Toast.LENGTH_LONG).show()
    }

    /* ---------- BLE provisioning ---------- */

    private fun startBleServer() {
        if (!bluetoothAdapter.isEnabled) {
            statusText.text = "Enable Bluetooth and reopen the app."
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) {
            bleManager.startServer()
            statusText.text = "Waiting for Wi-Fi credentials…"
        }
    }

    /* ---------- called from KidsBleManager (always worker thread) ---------- */

    fun onWifiCredentialsReceived(ssid: String, pass: String) {
        // Move UI work to main thread
        runOnUiThread {
            statusText.text = "Received SSID: $ssid"
            connectionStatusText.text = "Connecting…"
        }
        connectSilently(ssid, pass)
    }

    /* ---------- silent join (Device-Owner path) ---------- */

    private fun connectSilently(ssid: String, pass: String) {
        if (!wifiManager.isWifiEnabled) {                       // allowed for DO apps :contentReference[oaicite:1]{index=1}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                wifiManager.setWifiEnabled(true)
            else
                wifiManager.isWifiEnabled = true
        }

        val cfg = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$pass\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }

        // Remove duplicates first
        wifiManager.configuredNetworks?.filter { it.SSID == cfg.SSID }?.forEach {
            wifiManager.removeNetwork(it.networkId)
        }

        val id = wifiManager.addNetwork(cfg)                    // still legal for DO apps :contentReference[oaicite:2]{index=2}
        if (id == -1) {
            runOnUiThread { connectionStatusText.text = "addNetwork() failed" }
            return
        }

        wifiManager.disconnect()
        wifiManager.enableNetwork(id, true)
        wifiManager.reconnect()

        runOnUiThread { connectionStatusText.text = "Connected (DHCP pending)…" }
        Log.d(TAG, "Enabled network id=$id for $ssid")
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.shutdown()
    }
}
