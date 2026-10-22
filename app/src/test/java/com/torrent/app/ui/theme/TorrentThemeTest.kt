package com.torrent.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Compose blue palette to the WebUI tokens in `ControlServer.WEBUI_CSS`
 * (pinned on the CSS side by `ControlServerWebuiThemeTest`): the hex strings are
 * the shared contract between the dashboard and the app screens, so a change on
 * either side must show up here as a failure.
 *
 * Plain JUnit4: `TorrentPalette`/`torrentColorScheme()` are pure Compose data
 * (no Android framework calls), so no Robolectric is needed.
 */
class TorrentThemeTest {

    /** token -> the hex string the WebUI CSS uses (no leading `#`, no alpha). */
    private val webuiTokens = mapOf(
        TorrentPalette.BLUE to "1a73e8",
        TorrentPalette.BLUE_DARK to "1557b0",
        TorrentPalette.CHIP to "e8f0fe",
        TorrentPalette.TEXT to "1f2937",
        TorrentPalette.MUTED to "5f6368",
        TorrentPalette.CARD to "ffffff",
        TorrentPalette.GRADIENT_TOP to "eef4fd",
        TorrentPalette.GRADIENT_BOTTOM to "f5f7fa",
        TorrentPalette.OK to "188038",
        TorrentPalette.ERROR to "c5221f",
        TorrentPalette.WARN to "f9ab00",
    )

    @Test
    fun palette_tokensMatchWebuiHex() {
        for ((token, hex) in webuiTokens) {
            assertEquals("palette token drift from WebUI #$hex", hex, token.toString(16))
        }
    }

    @Test
    fun palette_colorAddsOpaqueAlpha() {
        for ((token, hex) in webuiTokens) {
            assertEquals(
                "color(#$hex) must be opaque",
                ("ff$hex").toLong(16).toInt(),
                TorrentPalette.color(token).toArgb(),
            )
        }
    }

    @Test
    fun gradient_isTopThenBottom() {
        // The WebUI body gradient is linear-gradient(180deg,#eef4fd,#f5f7fa): light at
        // the top, slightly grey at the bottom.
        assertEquals(
            listOf(TorrentPalette.GRADIENT_TOP, TorrentPalette.GRADIENT_BOTTOM),
            TorrentPalette.GRADIENT,
        )
    }

    @Test
    fun scheme_mapsPaletteOntoMaterialRoles() {
        val scheme = torrentColorScheme()
        assertEquals(TorrentPalette.color(TorrentPalette.BLUE).toArgb(), scheme.primary.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.BLUE_DARK).toArgb(), scheme.onPrimaryContainer.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.BLUE_DARK).toArgb(), scheme.secondary.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.CHIP).toArgb(), scheme.primaryContainer.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.OK).toArgb(), scheme.tertiary.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.ERROR).toArgb(), scheme.error.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.CARD).toArgb(), scheme.surface.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.TEXT).toArgb(), scheme.onSurface.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.TEXT).toArgb(), scheme.onBackground.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.MUTED).toArgb(), scheme.onSurfaceVariant.toArgb())
        assertEquals(TorrentPalette.color(TorrentPalette.GRADIENT_TOP).toArgb(), scheme.background.toArgb())
        assertEquals(Color.White.toArgb(), scheme.onPrimary.toArgb())
    }
}
