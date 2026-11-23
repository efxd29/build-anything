package app.scrcpy.scrcpy

import android.util.Log
import android.view.MotionEvent
import app.scrcpy.adb.AdbStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Sends scrcpy control messages to the device.
 *
 * Responsiveness strategy: a single writer thread owns the stream. Discrete
 * messages (taps, keys, buttons) go through a FIFO queue, while touch MOVE
 * events live in a per-pointer "latest value" slot that the writer drains
 * after the queue — a move that was superseded before being written is
 * never sent at all, so the device always works on the freshest position
 * and the pipe never carries a stale backlog.
 */
class ControlSender(
    private val controlStream: AdbStream,
    @Volatile var deviceWidth: Int,
    @Volatile var deviceHeight: Int
) {
    private val out: OutputStream = controlStream.output
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val pendingMoves = ConcurrentHashMap<Long, ByteArray>()

    @Volatile
    private var running = true

    private val writer = Thread({
        while (running) {
            try {
                val first = queue.poll(2, TimeUnit.MILLISECONDS)
                if (first != null) {
                    writeRaw(first)
                    // Drain everything else that is already queued.
                    while (true) {
                        val next = queue.poll() ?: break
                        writeRaw(next)
                    }
                }
                if (pendingMoves.isNotEmpty()) {
                    val moves = pendingMoves.values.toList()
                    pendingMoves.clear()
                    for (move in moves) writeRaw(move)
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "control writer: ${t.message}")
            }
        }
    }, "scrcpy-control").apply {
        isDaemon = true
        start()
    }

    fun close() {
        running = false
        writer.interrupt()
    }

    // ------------------------------------------------------------- touches

    fun sendTouchEvent(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> ScrcpyProtocol.TOUCH_ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                ScrcpyProtocol.TOUCH_ACTION_UP
            MotionEvent.ACTION_MOVE -> ScrcpyProtocol.TOUCH_ACTION_MOVE
            else -> return
        }
        val index = event.actionIndex
        val pointerId = event.getPointerId(index).toLong()
        val x = scaleX(event.getX(index), surfaceWidth)
        val y = scaleY(event.getY(index), surfaceHeight)
        val pressure = if (action == ScrcpyProtocol.TOUCH_ACTION_UP) 0f
        else event.getPressure(index).coerceIn(0f, 1f)
        val message = buildTouch(action, pointerId, x, y, pressure, event.buttonState)
        if (action == ScrcpyProtocol.TOUCH_ACTION_MOVE) {
            pendingMoves[pointerId] = message
        } else {
            pendingMoves.remove(pointerId)
            queue.offer(message)
        }
    }

    /** One pointer's movement (ACTION_MOVE carries every active pointer). */
    fun sendTouchMove(
        pointerId: Long,
        x: Float,
        y: Float,
        pressure: Float,
        surfaceWidth: Int,
        surfaceHeight: Int
    ) {
        val message = buildTouch(
            ScrcpyProtocol.TOUCH_ACTION_MOVE,
            pointerId,
            scaleX(x, surfaceWidth),
            scaleY(y, surfaceHeight),
            pressure.coerceIn(0f, 1f),
            0
        )
        pendingMoves[pointerId] = message
    }

    fun sendScrollEvent(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
        val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (hScroll == 0f && vScroll == 0f) return
        val x = scaleX(event.x, surfaceWidth)
        val y = scaleY(event.y, surfaceHeight)
        queue.offer(buildScroll(x, y, hScroll, vScroll))
    }

    // ----------------------------------------------------------- key & misc

    /** A full key press (down + up), e.g. Back, Home, Power, volume. */
    fun sendKeyPress(keycode: Int) {
        queue.offer(buildKey(ScrcpyProtocol.KEY_ACTION_DOWN, keycode))
        queue.offer(buildKey(ScrcpyProtocol.KEY_ACTION_UP, keycode))
    }

    fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(5 + bytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(ScrcpyProtocol.CONTROL_INJECT_TEXT.toByte())
        buffer.putInt(bytes.size)
        buffer.put(bytes)
        queue.offer(buffer.array())
    }

    fun sendBackOrScreenOn() {
        // The message carries one action byte; a press is down + up.
        queue.offer(byteArrayOf(ScrcpyProtocol.CONTROL_BACK_OR_SCREEN_ON.toByte(), 0))
        queue.offer(byteArrayOf(ScrcpyProtocol.CONTROL_BACK_OR_SCREEN_ON.toByte(), 1))
    }

    fun sendExpandNotifications() {
        queue.offer(byteArrayOf(ScrcpyProtocol.CONTROL_EXPAND_NOTIFICATION.toByte()))
    }

    fun sendCollapsePanels() {
        queue.offer(byteArrayOf(ScrcpyProtocol.CONTROL_COLLAPSE_PANELS.toByte()))
    }

    fun sendRotateDevice() {
        queue.offer(byteArrayOf(ScrcpyProtocol.CONTROL_ROTATE_DEVICE.toByte()))
    }

    fun sendResetVideo() {
        queue.offer(byteArrayOf(ScrcpyProtocol.CONTROL_RESET_VIDEO.toByte()))
    }

    fun sendSetDisplayPower(on: Boolean) {
        queue.offer(byteArrayOf(ScrcpyProtocol.CONTROL_SET_DISPLAY_POWER.toByte(), if (on) 1 else 0))
    }

    // -------------------------------------------------------------- builders

    private fun scaleX(x: Float, surfaceWidth: Int): Int {
        if (surfaceWidth <= 0) return 0
        return (x / surfaceWidth * deviceWidth).roundToInt().coerceIn(0, deviceWidth)
    }

    private fun scaleY(y: Float, surfaceHeight: Int): Int {
        if (surfaceHeight <= 0) return 0
        return (y / surfaceHeight * deviceHeight).roundToInt().coerceIn(0, deviceHeight)
    }

    private fun buildTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        pressure: Float,
        buttons: Int
    ): ByteArray {
        // scrcpy uses pointer id -1 for the primary ("mouse") pointer.
        val wirePointerId = if (pointerId == 0L) -1L else pointerId
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buffer.put(ScrcpyProtocol.CONTROL_INJECT_TOUCH.toByte())
        buffer.put(action.toByte())
        buffer.putLong(wirePointerId)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(deviceWidth.toShort())
        buffer.putShort(deviceHeight.toShort())
        buffer.putShort((pressure * 65535f).roundToInt().coerceIn(0, 65535).toShort())
        buffer.putInt(0) // action button
        buffer.putInt(buttons)
        return buffer.array()
    }

    private fun buildScroll(x: Int, y: Int, hScroll: Float, vScroll: Float): ByteArray {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buffer.put(ScrcpyProtocol.CONTROL_INJECT_SCROLL.toByte())
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(deviceWidth.toShort())
        buffer.putShort(deviceHeight.toShort())
        buffer.putShort(toFixed16(hScroll))
        buffer.putShort(toFixed16(vScroll))
        buffer.putInt(0) // buttons
        return buffer.array()
    }

    /**
     * Scroll encoding per the server (ControlMessageReader): the wire value
     * is an i16 fixed-point in [-1, 1] which the server multiplies by 16,
     * so a scroll step of v is encoded as v / 16 * 32768 = v * 2048.
     */
    private fun toFixed16(value: Float): Short {
        return (value * 2048f).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    private fun buildKey(action: Int, keycode: Int): ByteArray {
        val buffer = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        buffer.put(ScrcpyProtocol.CONTROL_INJECT_KEYCODE.toByte())
        buffer.put(action.toByte())
        buffer.putInt(keycode)
        buffer.putInt(0) // repeat
        buffer.putInt(0) // metastate
        return buffer.array()
    }

    private fun writeRaw(message: ByteArray) {
        synchronized(out) {
            out.write(message)
            out.flush()
        }
    }

    companion object {
        private const val TAG = "ScrcpyControl"
    }
}
