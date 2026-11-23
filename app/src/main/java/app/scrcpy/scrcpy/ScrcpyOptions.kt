package app.scrcpy.scrcpy

import android.content.Context

/**
 * User-tunable streaming parameters. Persisted in SharedPreferences so the
 * home screen sliders *are* the settings — there is no separate settings
 * page to hunt through.
 *
 * The defaults target the smoothest possible local mirroring: native
 * resolution, 48 Mbps, up to 144 fps.
 */
data class ScrcpyOptions(
    val bitrateMbps: Int = 48,
    val maxFps: Int = 144,
    /** Longest edge cap in px; 0 = native resolution. */
    val maxSize: Int = 0,
    /** "h264", "h265" or "av1". */
    val videoCodec: String = "h264",
    val audio: Boolean = true,
    /** "opus", "aac", "flac" or "raw". */
    val audioCodec: String = "opus",
    val stayAwake: Boolean = true,
    val turnScreenOff: Boolean = false,
    val showTouches: Boolean = false
) {
    val bitrateBps: Int
        get() = bitrateMbps * 1_000_000

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("bitrate_mbps", bitrateMbps)
            .putInt("max_fps", maxFps)
            .putInt("max_size", maxSize)
            .putString("video_codec", videoCodec)
            .putBoolean("audio", audio)
            .putString("audio_codec", audioCodec)
            .putBoolean("stay_awake", stayAwake)
            .putBoolean("turn_screen_off", turnScreenOff)
            .putBoolean("show_touches", showTouches)
            .apply()
    }

    companion object {
        private const val PREFS = "stream_options"

        fun load(context: Context): ScrcpyOptions {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ScrcpyOptions(
                bitrateMbps = p.getInt("bitrate_mbps", 48),
                maxFps = p.getInt("max_fps", 144),
                maxSize = p.getInt("max_size", 0),
                videoCodec = p.getString("video_codec", "h264") ?: "h264",
                audio = p.getBoolean("audio", true),
                audioCodec = p.getString("audio_codec", "opus") ?: "opus",
                stayAwake = p.getBoolean("stay_awake", true),
                turnScreenOff = p.getBoolean("turn_screen_off", false),
                showTouches = p.getBoolean("show_touches", false)
            )
        }
    }
}
