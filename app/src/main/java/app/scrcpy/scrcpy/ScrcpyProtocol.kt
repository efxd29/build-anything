package app.scrcpy.scrcpy

/**
 * Constants of the scrcpy client/server protocol (server v3.3.4).
 *
 * Packet header (video & audio streams), 12 bytes big-endian:
 *   8 bytes: PTS with two flag bits — bit 63 = configuration packet,
 *            bit 62 = key frame, remaining bits = PTS in microseconds
 *   4 bytes: payload size
 */
object ScrcpyProtocol {
    const val CODEC_H264 = 0x68323634 // "h264"
    const val CODEC_H265 = 0x68323635 // "h265"
    const val CODEC_AV1 = 0x00617631 // "\0av1"
    const val CODEC_OPUS = 0x6f707573 // "opus"
    const val CODEC_AAC = 0x00616163 // "\0aac"
    const val CODEC_FLAC = 0x666c6163 // "flac"
    const val CODEC_RAW = 0x00726177 // "\0raw"

    const val PACKET_HEADER_SIZE = 12
    const val FLAG_CONFIG = Long.MIN_VALUE
    const val FLAG_KEY_FRAME = 1L shl 62

    // Control message types
    const val CONTROL_INJECT_KEYCODE = 0
    const val CONTROL_INJECT_TEXT = 1
    const val CONTROL_INJECT_TOUCH = 2
    const val CONTROL_INJECT_SCROLL = 3
    const val CONTROL_BACK_OR_SCREEN_ON = 4
    const val CONTROL_EXPAND_NOTIFICATION = 5
    const val CONTROL_EXPAND_SETTINGS = 6
    const val CONTROL_COLLAPSE_PANELS = 7
    const val CONTROL_GET_CLIPBOARD = 8
    const val CONTROL_SET_CLIPBOARD = 9
    const val CONTROL_SET_DISPLAY_POWER = 10
    const val CONTROL_ROTATE_DEVICE = 11
    const val CONTROL_RESET_VIDEO = 17

    // Android key actions / motion actions used by the control protocol
    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1
    const val TOUCH_ACTION_DOWN = 0
    const val TOUCH_ACTION_UP = 1
    const val TOUCH_ACTION_MOVE = 2

    // Android keycodes we inject from the stream toolbar
    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_POWER = 26
    const val KEYCODE_APP_SWITCH = 187
    const val KEYCODE_ENTER = 66
}
