package app.scrcpy.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire framing of the Android Debug Bridge protocol.
 *
 * Every ADB packet is a 24-byte little-endian header followed by an optional
 * payload:
 *
 *   command      (4 bytes)  one of the A_* constants below
 *   arg0         (4 bytes)  meaning depends on the command
 *   arg1         (4 bytes)
 *   data_length  (4 bytes)
 *   data_checksum(4 bytes)  plain sum of all payload bytes
 *   magic        (4 bytes)  bitwise complement of command
 */
object AdbProtocol {
    const val A_CNXN = 0x4E584E43 // "CNXN"
    const val A_AUTH = 0x48545541 // "AUTH"
    const val A_OPEN = 0x4E45504F // "OPEN"
    const val A_OKAY = 0x59414B4F // "OKAY"
    const val A_CLSE = 0x45534C43 // "CLSE"
    const val A_WRTE = 0x45545257 // "WRTE"
    const val A_STLS = 0x534C5453 // "STLS"

    const val A_VERSION = 0x01000000
    const val ADB_MAX_DATA = 1024 * 1024

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val STLS_VERSION = 0x01000000

    const val HEADER_SIZE = 24

    /** Banner we present in A_CNXN, mirroring what a modern adb host sends. */
    const val CNXN_PAYLOAD = "host::features=stat_v2,ls_v2,apex,abb,fixed_push_symlink_timestamp"
}

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray
) {
    override fun toString(): String =
        "AdbMessage(cmd=0x${command.toString(16)}, arg0=$arg0, arg1=$arg1, len=${payload.size})"
}

/** Serialises one message (header + payload) into [out] and flushes. */
fun writeAdbMessage(out: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
    val header = ByteBuffer.allocate(AdbProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    var checksum = 0
    for (b in payload) checksum += b.toInt() and 0xFF
    header.putInt(command)
    header.putInt(arg0)
    header.putInt(arg1)
    header.putInt(payload.size)
    header.putInt(checksum)
    header.putInt(command.inv())
    out.write(header.array())
    if (payload.isNotEmpty()) out.write(payload)
    out.flush()
}

/** Reads exactly one message. Throws [IOException] on EOF or a corrupt header. */
fun readAdbMessage(input: InputStream): AdbMessage {
    val dis = if (input is DataInputStream) input else DataInputStream(input)
    val headerBytes = ByteArray(AdbProtocol.HEADER_SIZE)
    dis.readFully(headerBytes)
    val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
    val command = header.getInt(0)
    val arg0 = header.getInt(4)
    val arg1 = header.getInt(8)
    val length = header.getInt(12)
    val magic = header.getInt(20)
    if (magic != command.inv()) {
        throw IOException("ADB header magic mismatch (cmd=0x${command.toString(16)})")
    }
    if (length < 0 || length > AdbProtocol.ADB_MAX_DATA) {
        throw IOException("ADB payload length out of range: $length")
    }
    val payload = ByteArray(length)
    if (length > 0) dis.readFully(payload)
    return AdbMessage(command, arg0, arg1, payload)
}
