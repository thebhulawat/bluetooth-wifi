package com.outliers.bluetoothcheck

import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

// Use regular Activity instead of AppCompatActivity
class MainActivity : Activity() {
    private val TAG = "WifiSetupBLE"
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var bleManager: KidsBleManager
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView

    // BLE permissions request code
    private val PERMISSION_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        statusText = findViewById(R.id.statusText)
        connectionStatusText = findViewById(R.id.connectionStatusText)

        // Initialize managers
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        bleManager = KidsBleManager(this)

        // Request permissions
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Add location permissions (required for BLE scanning)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // For Android 12+, add BLUETOOTH_SCAN and BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            // All permissions granted, start BLE
            startBleSetup()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if all permissions were granted
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                startBleSetup()
            } else {
                Toast.makeText(
                    this,
                    "Required permissions were not granted",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startBleSetup() {
        if (!bluetoothAdapter.isEnabled) {
            statusText.text = "Bluetooth is disabled. Please enable Bluetooth."
            Toast.makeText(this, "Bluetooth is required for setup", Toast.LENGTH_LONG).show()
            return
        }

        // Start BLE GATT server to receive Wi-Fi credentials
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bleManager.startServer()
            statusText.text = "Waiting for BLE connection..."
            Log.d(TAG, "BLE GATT server started")
            Toast.makeText(this, "Ready to receive Wi-Fi credentials via BLE", Toast.LENGTH_SHORT).show()
        }
    }

    // Function called by bleManager when Wi-Fi credentials are received
    fun onWifiCredentialsReceived(ssid: String, password: String) {
        Log.d(TAG, "Wi-Fi credentials received. SSID: $ssid")

        runOnUiThread {
            statusText.text = "Received credentials. SSID: $ssid, pass: $password"
            connectionStatusText.text = "Status: Connecting to Wi-Fi..."
        }

        // Connect to Wi-Fi network
        connectToWifi(ssid, password)
    }


    private fun connectToWifi(ssid: String, password: String) {
        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .setIsAppInteractionRequired(false)
                .build()

            val suggestionsList = listOf(suggestion)

            val status = wifiManager.addNetworkSuggestions(suggestionsList)

            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                Log.d(TAG, "Wi-Fi suggestion added successfully")
                runOnUiThread {
                    connectionStatusText.text = "Status: Suggestion added for $ssid"
                }

                // Now force a connection using NetworkRequest
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()

                val networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        // Force all connections to go through this network
                        connectivityManager.bindProcessToNetwork(network)
                        runOnUiThread {
                            connectionStatusText.text = "Status: Connected to $ssid"
                        }
                        Log.d(TAG, "Connected to Wi-Fi network: $ssid")
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        runOnUiThread {
                            connectionStatusText.text = "Status: Failed to connect to $ssid"
                        }
                        Log.e(TAG, "Failed to connect to Wi-Fi network: $ssid")
                    }
                }

                // Request network with a timeout
                connectivityManager.requestNetwork(request, networkCallback)

                // Optional: Add a timeout to cancel the request if it takes too long
                Handler(Looper.getMainLooper()).postDelayed({
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                    runOnUiThread {
                        connectionStatusText.text = "Status: Connection timeout for $ssid"
                    }
                }, 30000) // 30 seconds timeout

            } else {
                Log.e(TAG, "Failed to add Wi-Fi suggestion")
                runOnUiThread {
                    connectionStatusText.text = "Status: Failed to add Wi-Fi suggestion"
                }
            }


        } else {
            // Old way for Android 9 and below
            val quotedSsid = "\"$ssid\""
            val quotedPassword = "\"$password\""

            val wifiConfig = WifiConfiguration().apply {
                SSID = quotedSsid
                preSharedKey = quotedPassword
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }

            val existingNetworks = wifiManager.configuredNetworks
            for (existing in existingNetworks) {
                if (existing.SSID == quotedSsid) {
                    wifiManager.removeNetwork(existing.networkId)
                    wifiManager.saveConfiguration()
                }
            }

            val networkId = wifiManager.addNetwork(wifiConfig)
            if (networkId == -1) {
                Log.e(TAG, "Failed to add network configuration for $ssid")
                return
            }

            wifiManager.disconnect()
            wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()
        }
    }



}