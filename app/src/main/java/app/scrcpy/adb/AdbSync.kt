package app.scrcpy.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The file-push half of the ADB SYNC protocol — just enough to upload the
 * scrcpy server to the device.
 */
object AdbSync {

    /**
     * Pushes [data] to [remotePath] on the device behind [connection].
     * [mode] is a unix permission mask (0644 by default).
     */
    @Throws(IOException::class)
    fun push(connection: AdbConnection, data: ByteArray, remotePath: String, mode: Int = 420) {
        val stream = connection.open("sync:")
        try {
            val out = stream.output
            val input = DataInputStream(stream.input)

            writeSyncPacket(out, "SEND", "$remotePath,$mode".toByteArray(Charsets.UTF_8))
            var offset = 0
            while (offset < data.size) {
                val chunkLen = minOf(DATA_CHUNK, data.size - offset)
                writeSyncPacket(out, "DATA", data.copyOfRange(offset, offset + chunkLen))
                offset += chunkLen
            }
            // DONE is special: id + 4-byte mtime (seconds), no length field.
            out.write("DONE".toByteArray(Charsets.US_ASCII))
            val mtime = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((System.currentTimeMillis() / 1000).toInt()).array()
            out.write(mtime)
            out.flush()

            val id = readSyncId(input)
            val length = readSyncInt(input)
            if (id == "FAIL") {
                val msg = ByteArray(length)
                input.readFully(msg)
                throw IOException("Device refused push: ${String(msg, Charsets.UTF_8)}")
            } else if (id != "OKAY") {
                throw IOException("Unexpected SYNC response: $id")
            }
        } finally {
            stream.close()
        }
    }

    private fun writeSyncPacket(out: OutputStream, id: String, payload: ByteArray) {
        writeRaw(out, id, payload)
    }

    private fun writeRaw(out: OutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size).array()
        out.write(len)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readSyncId(input: DataInputStream): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readSyncInt(input: DataInputStream): Int {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
    }

    private const val DATA_CHUNK = 65536
}
