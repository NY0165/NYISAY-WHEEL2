package mz.nyisay.virtualracingwheel

/**
 * Builds the HID input report matching the descriptor registered with BluetoothHidDevice.
 *
 * Report layout (8 bytes):
 *   [0..1] X axis  uint16 LE   steer  0..65535  (center 32768)
 *   [2..3] Y axis  uint16 LE   throttle 0..65535
 *   [4..5] Z axis  uint16 LE   brake 0..65535
 *   [6..7] Buttons uint16 LE   bit0 shift+, 1 shift-, 2 DRS, 3 ERS, 4 pause
 */
object ReportBuilder {

    const val REPORT_ID: Byte = 0x01
    const val REPORT_SIZE = 8

    /** Full HID report descriptor for a 3-axis + 16-button gamepad. */
    val DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01,                    // Usage Page (Generic Desktop)
        0x09, 0x05,                    // Usage (Game Pad)
        0xA1.toByte(), 0x01,           // Collection (Application)
        0x85.toByte(), REPORT_ID,      //   Report ID 1

        // X, Y, Z axes — 16-bit each
        0x09, 0x01,                    //   Usage (Pointer)
        0xA1.toByte(), 0x00,           //   Collection (Physical)
        0x09, 0x30,                    //     Usage (X)
        0x09, 0x31,                    //     Usage (Y)
        0x09, 0x32,                    //     Usage (Z)
        0x15, 0x00,                    //     Logical Min 0
        0x27, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, // Logical Max 65535
        0x75, 0x10,                    //     Report Size 16
        0x95.toByte(), 0x03,           //     Report Count 3
        0x81.toByte(), 0x02,           //     Input (Data,Var,Abs)
        0xC0.toByte(),                 //   End Collection

        // 16 buttons
        0x05, 0x09,                    //   Usage Page (Button)
        0x19, 0x01,                    //   Usage Min 1
        0x29, 0x10,                    //   Usage Max 16
        0x15, 0x00,                    //   Logical Min 0
        0x25, 0x01,                    //   Logical Max 1
        0x75, 0x01,                    //   Report Size 1
        0x95.toByte(), 0x10,           //   Report Count 16
        0x81.toByte(), 0x02,           //   Input (Data,Var,Abs)

        0xC0.toByte()                  // End Collection
    )

    fun build(snap: ControlState.Snapshot): ByteArray {
        // steer -1..+1 → 0..65535 (center 32768)
        val x = ((snap.steer.coerceIn(-1f, 1f) + 1f) * 32767.5f).toInt().coerceIn(0, 65535)
        val y = (snap.throttle.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)
        val z = (snap.brake.coerceIn(0f, 1f) * 65535f).toInt().coerceIn(0, 65535)
        val buttons = snap.buttons and 0xFFFF

        return byteArrayOf(
            (x and 0xFF).toByte(),
            ((x shr 8) and 0xFF).toByte(),
            (y and 0xFF).toByte(),
            ((y shr 8) and 0xFF).toByte(),
            (z and 0xFF).toByte(),
            ((z shr 8) and 0xFF).toByte(),
            (buttons and 0xFF).toByte(),
            ((buttons shr 8) and 0xFF).toByte()
        )
    }

    fun neutral(): ByteArray = build(ControlState.Snapshot(0f, 0f, 0f, 0))
}
