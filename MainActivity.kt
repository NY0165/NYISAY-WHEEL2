package mz.nyisay.virtualracingwheel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Landscape racing-wheel UI — Bluetooth HID gamepad.
 * Editable local telemetry panel + live diagnostic parameters.
 */
class MainActivity : AppCompatActivity(), HidDeviceService.Listener {

    private lateinit var state: ControlState
    private lateinit var sensor: SteeringSensor
    private var sender: Sender? = null
    private var hidService: HidDeviceService? = null

    private lateinit var txtStatus: TextView
    private lateinit var txtSteerOut: TextView
    private lateinit var txtThrOut: TextView
    private lateinit var txtBrkOut: TextView
    private lateinit var steerBar: ProgressBar
    private lateinit var rpmBar: ProgressBar
    private lateinit var txtDiag: TextView
    private lateinit var diagPanel: View
    private lateinit var editSpeed: EditText
    private lateinit var editGear: EditText
    private lateinit var editRpm: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var diagVisible = false
    private var lastSteer = 0f
    private var bound = false

    private val prefs by lazy { getSharedPreferences("nyisay_v03", Context.MODE_PRIVATE) }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? HidDeviceService.LocalBinder
            if (binder == null) {
                Log.e(TAG, "Received an unexpected service binder")
                txtStatus.setText(R.string.status_failed)
                return
            }

            sender?.stop()
            hidService = binder.getService()
            hidService?.setListener(this@MainActivity)
            hidService?.setWantedHost(prefs.getString("host", null))
            hidService?.startHid(this@MainActivity)
            sender = Sender(state) { report ->
                hidService?.sendReport(report) ?: false
            }.also { it.start() }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sender?.stop()
            sender = null
            hidService = null
            // The Activity remains bound to this ServiceConnection until unbindService().
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        state = ControlState()
        bindViews()
        loadSettings()
        bindControls()
        bindSettingsUi()

        sensor = SteeringSensor(this) { steer ->
            lastSteer = steer
            state.steer = steer
        }

        requestBtPermissionIfNeeded()

        // Bluetooth permission must be granted before querying the HID profile.

        // UI refresh ~20 Hz
        handler.post(uiTick)
    }

    private fun bindViews() {
        txtStatus = findViewById(R.id.txtStatus)
        txtSteerOut = findViewById(R.id.txtSteerOut)
        txtThrOut = findViewById(R.id.txtThrOut)
        txtBrkOut = findViewById(R.id.txtBrkOut)
        steerBar = findViewById(R.id.steerBar)
        rpmBar = findViewById(R.id.rpmBar)
        txtDiag = findViewById(R.id.txtDiag)
        diagPanel = findViewById(R.id.diagPanel)
        editSpeed = findViewById(R.id.editSpeed)
        editGear = findViewById(R.id.editGear)
        editRpm = findViewById(R.id.editRpm)
    }

    private fun bindControls() {
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            sensor.calibrate()
            Toast.makeText(this, "Zero calibrado", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPair).setOnClickListener { makeDiscoverable() }
        findViewById<Button>(R.id.btnPc).setOnClickListener { pickHost() }
        findViewById<Button>(R.id.btnDiag).setOnClickListener {
            diagVisible = !diagVisible
            diagPanel.visibility = if (diagVisible) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            diagVisible = true
            diagPanel.visibility = View.VISIBLE
        }

        bindHold(findViewById(R.id.btnThrottle)) { p -> state.throttle = if (p) 1f else 0f }
        bindHold(findViewById(R.id.btnBrake)) { p -> state.brake = if (p) 1f else 0f }
        bindHold(findViewById(R.id.btnShiftUp)) { p -> state.setButton(ControlState.BIT_SHIFT_UP, p) }
        bindHold(findViewById(R.id.btnShiftDown)) { p -> state.setButton(ControlState.BIT_SHIFT_DOWN, p) }
        bindHold(findViewById(R.id.btnDrs)) { p -> state.setButton(ControlState.BIT_DRS, p) }
        bindHold(findViewById(R.id.btnErs)) { p -> state.setButton(ControlState.BIT_ERS, p) }
        bindHold(findViewById(R.id.btnPause)) { p -> state.setButton(ControlState.BIT_PAUSE, p) }

        // Editable telemetry → update RPM bar when user types
        editRpm.setOnFocusChangeListener { _, _ -> updateRpmBar() }
        editRpm.addTextChangedListener(SimpleWatcher { updateRpmBar() })
    }

    private fun bindSettingsUi() {
        val seekMax = findViewById<SeekBar>(R.id.seekMaxAngle)
        val valMax = findViewById<TextView>(R.id.valMaxAngle)
        seekMax.setOnSeekBarChangeListener(SimpleSeek { v ->
            sensor.maxAngleDeg = v.toFloat().coerceAtLeast(10f)
            valMax.text = v.toString()
            saveSettings()
        })

        val seekFilter = findViewById<SeekBar>(R.id.seekFilter)
        val valFilter = findViewById<TextView>(R.id.valFilter)
        seekFilter.setOnSeekBarChangeListener(SimpleSeek { v ->
            sensor.filterAlpha = (v / 100f).coerceIn(0.02f, 0.5f)
            valFilter.text = "%.2f".format(sensor.filterAlpha)
            saveSettings()
        })

        val seekDz = findViewById<SeekBar>(R.id.seekDeadzone)
        val valDz = findViewById<TextView>(R.id.valDeadzone)
        seekDz.setOnSeekBarChangeListener(SimpleSeek { v ->
            sensor.deadzone = (v / 100f).coerceIn(0f, 0.3f)
            valDz.text = "%.2f".format(sensor.deadzone)
            saveSettings()
        })

        val seekCurve = findViewById<SeekBar>(R.id.seekCurve)
        val valCurve = findViewById<TextView>(R.id.valCurve)
        seekCurve.setOnSeekBarChangeListener(SimpleSeek { v ->
            sensor.curveExp = 1f + v / 20f
            valCurve.text = "%.1f".format(sensor.curveExp)
            saveSettings()
        })

        val seekHz = findViewById<SeekBar>(R.id.seekHz)
        val valHz = findViewById<TextView>(R.id.valHz)
        seekHz.setOnSeekBarChangeListener(SimpleSeek { v ->
            val hz = v.coerceIn(10, 100)
            sender?.hz = hz
            valHz.text = hz.toString()
            saveSettings()
        })

        findViewById<CheckBox>(R.id.chkInvert).setOnCheckedChangeListener { _, checked ->
            sensor.invert = checked
            saveSettings()
        }
    }

    private fun loadSettings() {
        val maxA = prefs.getInt("max_angle", 45)
        val filter = prefs.getInt("filter", 18)
        val dz = prefs.getInt("deadzone", 4)
        val curve = prefs.getInt("curve", 13)
        val hz = prefs.getInt("hz", 50)
        val inv = prefs.getBoolean("invert", false)

        findViewById<SeekBar>(R.id.seekMaxAngle).progress = maxA
        findViewById<TextView>(R.id.valMaxAngle).text = maxA.toString()
        findViewById<SeekBar>(R.id.seekFilter).progress = filter
        findViewById<TextView>(R.id.valFilter).text = "%.2f".format(filter / 100f)
        findViewById<SeekBar>(R.id.seekDeadzone).progress = dz
        findViewById<TextView>(R.id.valDeadzone).text = "%.2f".format(dz / 100f)
        findViewById<SeekBar>(R.id.seekCurve).progress = curve
        findViewById<TextView>(R.id.valCurve).text = "%.1f".format(1f + curve / 20f)
        findViewById<SeekBar>(R.id.seekHz).progress = hz
        findViewById<TextView>(R.id.valHz).text = hz.toString()
        findViewById<CheckBox>(R.id.chkInvert).isChecked = inv

        // Applied when sensor is created — set after
        handler.post {
            if (::sensor.isInitialized) {
                sensor.maxAngleDeg = maxA.toFloat()
                sensor.filterAlpha = filter / 100f
                sensor.deadzone = dz / 100f
                sensor.curveExp = 1f + curve / 20f
                sensor.invert = inv
                sender?.hz = hz
            }
        }
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("max_angle", findViewById<SeekBar>(R.id.seekMaxAngle).progress)
            .putInt("filter", findViewById<SeekBar>(R.id.seekFilter).progress)
            .putInt("deadzone", findViewById<SeekBar>(R.id.seekDeadzone).progress)
            .putInt("curve", findViewById<SeekBar>(R.id.seekCurve).progress)
            .putInt("hz", findViewById<SeekBar>(R.id.seekHz).progress)
            .putBoolean("invert", findViewById<CheckBox>(R.id.chkInvert).isChecked)
            .apply()
    }

    private fun updateRpmBar() {
        val rpm = editRpm.text.toString().toIntOrNull() ?: 0
        rpmBar.progress = ((rpm / 18000f) * 100f).toInt().coerceIn(0, 100)
    }

    private val uiTick = object : Runnable {
        override fun run() {
            val s = lastSteer
            txtSteerOut.text = "DIR ${"%+.3f".format(s)}"
            txtThrOut.text = "ACC ${(state.throttle * 100).toInt()}%"
            txtBrkOut.text = "TRV ${(state.brake * 100).toInt()}%"
            steerBar.progress = ((s + 1f) * 100f).toInt().coerceIn(0, 200)

            if (diagVisible) {
                val rps = sender?.reportsPerSecond ?: 0
                txtDiag.text = buildString {
                    append("ângulo ${"%.1f".format(sensor.filteredDeg)}°  ")
                    append("zero ${"%.1f".format(sensor.zeroDeg)}°\n")
                    append("steer ${"%.3f".format(s)}  ")
                    append("btn 0x${state.buttons.toString(16)}\n")
                    append("HID ${hidService?.state}  ")
                    append("$rps rel/s")
                }
            }
            handler.postDelayed(this, 50)
        }
    }

    private fun bindHold(view: View, onChange: (Boolean) -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    v.isPressed = true
                    onChange(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    onChange(false)
                    true
                }
                else -> false
            }
        }
    }

    // --- HID listener ---
    override fun onState(state: HidDeviceService.State, deviceName: String?) {
        runOnUiThread {
            txtStatus.text = when (state) {
                HidDeviceService.State.OFF -> getString(R.string.status_off)
                HidDeviceService.State.REGISTERING -> getString(R.string.status_registering)
                HidDeviceService.State.WAITING -> getString(R.string.status_waiting)
                HidDeviceService.State.CONNECTING -> getString(R.string.status_connecting)
                HidDeviceService.State.CONNECTED ->
                    "${getString(R.string.status_connected)}${deviceName?.let { " · $it" } ?: ""}"
                HidDeviceService.State.FAILED -> getString(R.string.status_failed)
            }
        }
    }

    override fun onReportSent() { /* counted in Sender */ }

    // --- Emparelhar / escolher PC ---
    private fun makeDiscoverable() {
        try {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        } catch (error: Exception) {
            Toast.makeText(this, R.string.pair_manual, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String {
        val name = try {
            device.name
        } catch (error: SecurityException) {
            null
        }
        return "${name ?: "?"}\n${device.address}"
    }

    private fun pickHost() {
        val list = hidService?.bondedDevices() ?: emptyList()
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_LONG).show()
            return
        }
        val names = list.map { deviceLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_pc)
            .setItems(names) { _, index ->
                val address = list[index].address
                prefs.edit().putString("host", address).apply()
                hidService?.setWantedHost(address)
            }
            .show()
    }

    // --- Permissions ---
    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val required = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        return required.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBtPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                REQ_BT
            )
        } else {
            bindHidService()
        }
    }

    private fun bindHidService() {
        if (bound || isFinishing || !hasBtPermission()) return
        val intent = Intent(this, HidDeviceService::class.java)
        bound = bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            txtStatus.setText(R.string.status_failed)
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        if (hasBtPermission()) {
            bindHidService()
        } else {
            txtStatus.setText(R.string.status_failed)
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        sensor.start()
    }

    override fun onPause() {
        super.onPause()
        // Neutral report before leaving foreground
        state.neutral()
        hidService?.sendReport(ReportBuilder.neutral())
        sensor.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiTick)
        sender?.stop()
        if (bound) {
            hidService?.setListener(null)
            hidService?.stopHid()
            unbindService(conn)
            bound = false
        }
        sensor.stop()
        super.onDestroy()
    }

    /** Tiny helpers to avoid anonymous class noise */
    private class SimpleSeek(val cb: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) cb(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private class SimpleWatcher(val cb: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { cb() }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_BT = 1001
    }
}
