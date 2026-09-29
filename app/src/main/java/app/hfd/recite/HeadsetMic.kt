package app.hfd.recite

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import app.hfd.diag.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Bluetooth headset's microphone (earbuds) for Recite. Android records from the phone's own
 * microphone even while earbuds are connected: the owner recited with Nothing Ear (3) on and the
 * phone, away from the mouth, heard the voice muffled (almost nothing above 300 Hz) and too faint
 * for the model. The earbuds' microphone needs the headset's call link, opened as a voice-chat app
 * does: communication mode, the headset as the communication device (Android 12+). LE Audio
 * earbuds come first (a wider band than the classic SCO link).
 */
class HeadsetMic(private val audio: AudioManager) {
    private var previousMode = AudioManager.MODE_NORMAL
    private var linked = false

    /** The connected headset that has a microphone, if any. */
    fun available(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val devices = runCatching { audio.availableCommunicationDevices }.getOrNull().orEmpty()
        return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    /** Opens [device]'s call link; true once Android routes to it (a Bluetooth link takes a moment). */
    suspend fun connect(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val started = System.currentTimeMillis()
        previousMode = audio.mode
        runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
        linked = runCatching { audio.setCommunicationDevice(device) }.getOrDefault(false)
        val ok = try {
            linked && withTimeoutOrNull(LINK_MS) {
                while (audio.communicationDevice?.id != device.id) delay(50)
                true
            } == true
        } catch (e: CancellationException) {
            release()
            throw e
        }
        Diag.log(
            "mic.headset", "type" to device.type, "name" to device.productName?.toString(), "ok" to ok,
            "ms" to System.currentTimeMillis() - started, "mode" to audio.mode,
        )
        if (!ok) release()
        return ok
    }

    /** [device]'s microphone among the inputs, to ask the recorder for it. */
    fun input(device: AudioDeviceInfo): AudioDeviceInfo? {
        val inputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }.getOrNull().orEmpty()
        return inputs.firstOrNull { it.type == device.type && it.address == device.address } ?: inputs.firstOrNull { it.type == device.type }
    }

    /** Back to the phone as it was. */
    fun release() {
        if (linked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audio.clearCommunicationDevice() }
        linked = false
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION && previousMode != AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { audio.mode = previousMode }
        }
    }

    companion object {
        /** How long the headset's link may take to come up. */
        private const val LINK_MS = 4_000L

        fun isHeadset(device: AudioDeviceInfo?): Boolean =
            device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}
