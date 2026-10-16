package mz.nyisay.virtualracingwheel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Registers this app as a Bluetooth HID gamepad and forwards input reports to a host.
 * Requires Android 9+ and a device whose Bluetooth stack exposes the HID Device profile.
 */
class HidDeviceService : Service() {

    enum class State { OFF, REGISTERING, WAITING, CONNECTING, CONNECTED, FAILED }

    interface Listener {
        fun onState(state: State, deviceName: String?)
        fun onReportSent()
    }

    inner class LocalBinder : Binder() {
        fun getService(): HidDeviceService = this@HidDeviceService
    }

    private val binder = LocalBinder()

    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var hostDevice: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var stopRequested = false
    @Volatile private var profileRequestPending = false
    @Volatile var state: State = State.OFF
        private set

    private var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var wantedAddress: String? = null
    private val retryConnect = Runnable { connectWanted() }
    private var profileAdapter: BluetoothAdapter? = null
    private var profileGeneration = 0

    override fun onBind(intent: Intent?): IBinder = binder

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** Endereco do PC a que o telemovel deve ligar (guardado pela Activity). */
    fun setWantedHost(address: String?) {
        wantedAddress = address
        if (registered) connectWanted()
    }

    /** Dispositivos ja emparelhados (o PC tem de aparecer aqui). */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermissions()) return emptyList()
        return try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() ?: emptyList()
        } catch (error: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectWanted() {
        mainHandler.removeCallbacks(retryConnect)
        if (stopRequested || !registered || hostDevice != null) return
        val address = wantedAddress ?: return
        val hid = hidDevice ?: return
        if (!hasBluetoothPermissions()) return
        try {
            val device = BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
            if (device != null) hid.connect(device)
        } catch (error: Exception) {
            Log.w(TAG, "HID connect request failed", error)
        }
        mainHandler.postDelayed(retryConnect, RETRY_MS)
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val required = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        return required.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startHid(context: Context) {
        if (state == State.REGISTERING || state == State.WAITING || state == State.CONNECTING ||
            state == State.CONNECTED || profileRequestPending
        ) {
            return
        }
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Bluetooth runtime permissions have not been granted")
            setState(State.FAILED, null)
            return
        }

        stopRequested = false
        registered = false
        hostDevice = null
        setState(State.REGISTERING, null)

        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied while obtaining Bluetooth adapter", error)
            setState(State.FAILED, null)
            return
        }

        if (adapter == null) {
            Log.e(TAG, "This device does not provide a Bluetooth adapter")
            setState(State.FAILED, null)
            return
        }

        val enabled = try {
            adapter.isEnabled
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied while checking Bluetooth state", error)
            false
        }
        if (!enabled) {
            Log.e(TAG, "Bluetooth adapter is disabled")
            setState(State.FAILED, null)
            return
        }

        val generation = ++profileGeneration
        profileRequestPending = true
        val requestAccepted = try {
            adapter.getProfileProxy(
                context.applicationContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        profileRequestPending = false
                        if (profile != BluetoothProfile.HID_DEVICE) return

                        if (stopRequested || generation != profileGeneration) {
                            try {
                                adapter.closeProfileProxy(profile, proxy)
                            } catch (error: Exception) {
                                Log.w(TAG, "Could not close stale HID profile proxy", error)
                            }
                            return
                        }

                        val hid = proxy as? BluetoothHidDevice
                        if (hid == null) {
                            Log.e(TAG, "Bluetooth profile proxy is not a BluetoothHidDevice")
                            try {
                                adapter.closeProfileProxy(profile, proxy)
                            } catch (error: Exception) {
                                Log.w(TAG, "Could not close unsupported HID profile proxy", error)
                            }
                            setState(State.FAILED, null)
                            return
                        }

                        profileAdapter = adapter
                        hidDevice = hid
                        registerHidApplication(hid)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile != BluetoothProfile.HID_DEVICE) return
                        profileRequestPending = false
                        hidDevice = null
                        registered = false
                        hostDevice = null
                        profileAdapter = null
                        if (!stopRequested) setState(State.OFF, null)
                    }
                },
                BluetoothProfile.HID_DEVICE
            )
        } catch (error: SecurityException) {
            profileRequestPending = false
            Log.e(TAG, "Permission denied while requesting the HID profile", error)
            setState(State.FAILED, null)
            false
        } catch (error: RuntimeException) {
            profileRequestPending = false
            Log.e(TAG, "Unable to request the HID profile", error)
            setState(State.FAILED, null)
            false
        }

        if (!requestAccepted) {
            profileRequestPending = false
            Log.e(TAG, "Bluetooth stack rejected the HID profile request")
            setState(State.FAILED, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApplication(hid: BluetoothHidDevice) {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Bluetooth permissions were revoked before HID registration")
            setState(State.FAILED, null)
            return
        }

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "NYISAY Racing Wheel",
            "Virtual racing wheel / gamepad",
            "NYISAY",
            BluetoothHidDevice.SUBCLASS2_GAMEPAD,
            ReportBuilder.DESCRIPTOR
        )
        val inQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )
        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )

        val accepted = try {
            // API 28+ requires an Executor and callback. A true return value means
            // the request was sent successfully, not that registration has completed.
            hid.registerApp(sdp, inQos, outQos, ContextCompat.getMainExecutor(this), callback)
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied while registering the HID application", error)
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "HID application registration request failed", error)
            false
        }

        if (!accepted) {
            Log.e(TAG, "Bluetooth stack rejected HID registration request")
            setState(State.FAILED, null)
        } else {
            // Keep REGISTERING until onAppStatusChanged confirms the actual result.
            setState(State.REGISTERING, null)
            Log.i(TAG, "HID registration request accepted; waiting for callback")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registeredNow: Boolean) {
            Log.i(TAG, "onAppStatusChanged registered=$registeredNow device=$pluggedDevice")
            registered = registeredNow
            if (stopRequested) return

            if (registeredNow) {
                setState(State.WAITING, null)
                // A non-null pluggedDevice is informational; connection state is tracked
                // by onConnectionStateChanged and is not assumed from this callback.
                connectWanted()
            } else {
                hostDevice = null
                setState(State.FAILED, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, connectionState: Int) {
            Log.i(TAG, "onConnectionStateChanged device=$device state=$connectionState")
            if (stopRequested) return

            when (connectionState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    mainHandler.removeCallbacks(retryConnect)
                    setState(State.CONNECTED, device?.name ?: device?.address)
                    sendReport(ReportBuilder.neutral())
                }
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING -> {
                    hostDevice = null
                    setState(if (registered) State.WAITING else State.FAILED, null)
                    if (registered) {
                        mainHandler.removeCallbacks(retryConnect)
                        mainHandler.postDelayed(retryConnect, 1500L)
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> setState(State.CONNECTING, device?.name ?: device?.address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendReport(report: ByteArray): Boolean {
        if (!hasBluetoothPermissions()) return false
        val hid = hidDevice ?: return false
        val host = hostDevice ?: return false
        return try {
            val sent = hid.sendReport(host, ReportBuilder.REPORT_ID.toInt(), report)
            if (sent) listener?.onReportSent()
            sent
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied while sending HID report", error)
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to send HID report", error)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopHid() {
        mainHandler.removeCallbacks(retryConnect)
        stopRequested = true
        profileGeneration++
        profileRequestPending = false

        // Send a neutral report before unregistering, while a host is still connected.
        try {
            sendReport(ReportBuilder.neutral())
        } catch (error: Exception) {
            Log.w(TAG, "Could not send neutral report while stopping", error)
        }

        val hid = hidDevice
        if (hid != null) {
            if (registered && hasBluetoothPermissions()) {
                try {
                    hid.unregisterApp()
                } catch (error: Exception) {
                    Log.w(TAG, "HID unregister request failed", error)
                }
            }
            val adapter = profileAdapter
            if (adapter != null) {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not close HID profile proxy", error)
                }
            }
        }

        hidDevice = null
        profileAdapter = null
        registered = false
        hostDevice = null
        setState(State.OFF, null)
    }

    private fun setState(newState: State, deviceName: String?) {
        state = newState
        listener?.onState(newState, deviceName)
    }

    override fun onDestroy() {
        stopHid()
        listener = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HidDeviceService"
        private const val RETRY_MS = 5000L
    }
}
