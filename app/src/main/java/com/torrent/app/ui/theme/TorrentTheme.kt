package com.torrent.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The blue palette shared with the WebUI.
 *
 * Tokens mirror `ControlServer.WEBUI_CSS` (`--blue`, `--blue-dark`, `--chip`,
 * `--text`, `--muted`, `--card` plus the body gradient and the status colours)
 * so the Compose screens and the control dashboard look like one product.
 * Values are stored WITHOUT the alpha channel: the hex strings are the shared
 * contract with the CSS, and [color] adds the opaque alpha back for Compose.
 * `ControlServerWebuiThemeTest` pins the same strings on the CSS side; do not
 * change one without the other.
 */
object TorrentPalette {

    /** `--blue`: primary actions, links, headings. */
    const val BLUE: Long = 0x1A73E8L

    /** `--blue-dark`: pressed/hover primary, on-primary-container text. */
    const val BLUE_DARK: Long = 0x1557B0L

    /** `--chip`: selected chip and container fill. */
    const val CHIP: Long = 0xE8F0FEL

    /** `--text`: body copy. */
    const val TEXT: Long = 0x1F2937L

    /** `--muted`: secondary/label copy. */
    const val MUTED: Long = 0x5F6368L

    /** `--card`: surfaces and cards. */
    const val CARD: Long = 0xFFFFFFL

    /** Top stop of the WebUI `body` gradient. */
    const val GRADIENT_TOP: Long = 0xEEF4FDL

    /** Bottom stop of the WebUI `body` gradient. */
    const val GRADIENT_BOTTOM: Long = 0xF5F7FAL

    /** WebUI success green (`#188038`, `#live.on`). */
    const val OK: Long = 0x188038L

    /** WebUI error red (`#c5221f`, `.banner.red`). */
    const val ERROR: Long = 0xC5221FL

    /** WebUI warning amber (`#f9ab00`, `#live.wait`). */
    const val WARN: Long = 0xF9AB00L

    /** The background gradient stops, top first. */
    val GRADIENT: List<Long> = listOf(GRADIENT_TOP, GRADIENT_BOTTOM)

    /**
     * A palette token as an opaque Compose [Color]. The tokens carry no alpha,
     * so the opaque channel is supplied here (a bare `Color(0x1A73E8)` would be
     * fully transparent).
     */
    fun color(token: Long): Color = Color(0xFF000000L or token)
}

/**
 * The app-wide Material3 scheme: the WebUI blue palette in light mode only
 * (the device runs light; there is no dark palette).
 */
fun torrentColorScheme(): ColorScheme = lightColorScheme(
    primary = TorrentPalette.color(TorrentPalette.BLUE),
    onPrimary = Color.White,
    primaryContainer = TorrentPalette.color(TorrentPalette.CHIP),
    onPrimaryContainer = TorrentPalette.color(TorrentPalette.BLUE_DARK),
    secondary = TorrentPalette.color(TorrentPalette.BLUE_DARK),
    onSecondary = Color.White,
    secondaryContainer = TorrentPalette.color(TorrentPalette.CHIP),
    onSecondaryContainer = TorrentPalette.color(TorrentPalette.BLUE_DARK),
    tertiary = TorrentPalette.color(TorrentPalette.OK),
    error = TorrentPalette.color(TorrentPalette.ERROR),
    onError = Color.White,
    background = TorrentPalette.color(TorrentPalette.GRADIENT_TOP),
    onBackground = TorrentPalette.color(TorrentPalette.TEXT),
    surface = TorrentPalette.color(TorrentPalette.CARD),
    onSurface = TorrentPalette.color(TorrentPalette.TEXT),
    onSurfaceVariant = TorrentPalette.color(TorrentPalette.MUTED),
)

/**
 * The WebUI `body` gradient (`linear-gradient(180deg,#eef4fd,#f5f7fa)`) as a
 * modifier. Used behind the root [androidx.compose.material3.Surface], which is
 * transparent, so both the wizard and the main screen share the background.
 */
fun Modifier.torrentBackground(): Modifier = background(
    Brush.verticalGradient(TorrentPalette.GRADIENT.map { TorrentPalette.color(it) }),
)
