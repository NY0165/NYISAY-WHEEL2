package mz.nyisay.virtualracingwheel

/**
 * Latest control inputs. Written by UI + sensor; read by Sender.
 */
class ControlState {
    @Volatile var steer: Float = 0f       // -1 .. +1
    @Volatile var throttle: Float = 0f    // 0 .. 1
    @Volatile var brake: Float = 0f       // 0 .. 1
    @Volatile var buttons: Int = 0        // bitfield

    companion object {
        const val BIT_SHIFT_UP = 0x01
        const val BIT_SHIFT_DOWN = 0x02
        const val BIT_DRS = 0x04
        const val BIT_ERS = 0x08
        const val BIT_PAUSE = 0x10
    }

    fun setButton(bit: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or bit else buttons and bit.inv()
    }

    fun neutral() {
        steer = 0f
        throttle = 0f
        brake = 0f
        buttons = 0
    }

    fun snapshot() = Snapshot(steer, throttle, brake, buttons)

    data class Snapshot(
        val steer: Float,
        val throttle: Float,
        val brake: Float,
        val buttons: Int
    )
}
