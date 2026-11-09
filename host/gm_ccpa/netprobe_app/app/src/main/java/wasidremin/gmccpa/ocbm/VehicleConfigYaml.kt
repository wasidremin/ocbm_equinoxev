package wasidremin.gmccpa.ocbm

/**
 * The vehicle config pushed on `CT_SUBSCRIBE`.
 *
 * ## Why this file is dangerous out of proportion to its size
 *
 * A malformed document does not fail loudly. serde rejects the WHOLE document and the box falls back to
 * its compiled defaults — 1920x720, H.264, `wired_pcm`, the baseline metadata tier — and nothing tells
 * the host. Video simply comes up at the wrong resolution with HEVC off.
 *
 * That is a recorded incident, not a hypothetical: `tools/regen_app_yaml_fixture.py:1-14` (removed with the
 * macOS app on 2026-10-27; see git history) described a
 * hand-typed fixture certifying a broken emitter because one Swift literal lacked a trailing newline,
 * gluing the next key onto `drawUIOutsideSafeArea: false` and "silently revert[ing] resolution, HEVC,
 * appDrivenSetup, audio and the metadata tier at once."
 *
 * So: the shape below is byte-derived from the schema fixture `APP_DOC` in
 * `crates/vendor/receiver/tests/r4_c2_schema.rs:20-103`, and `VehicleConfigYamlTest` pins the rendered
 * output byte for byte. **If you edit the emitter, update the golden test in the same commit** — a
 * fixture nobody regenerates is hand-typed by definition, which is exactly how the incident happened.
 *
 * ## Values that are load-bearing
 *
 * - `touchScreenMode: High Fidelty` — Apple's own misspelling. Matched literally, unquoted.
 * - `primaryInput: Touchpad` — Apple's configs only ever use `Touchpad` or `Knobs`. `Touchscreen`
 *   appears nowhere in their bundle and a strict decoder would throw on it.
 * - `dPadSupport: false` — touch still binds (it rides the `hidDevices[]` Digitizer, not the D-Pad).
 *   Advertising a HID device we do not serve has broken session reconnect before, so every unused
 *   input device stays false. The cost is that `INPUT_NAV` becomes a box-side no-op.
 * - `enablesMainBufferedAudio: false` — advertising a buffered stream we do not serve silences
 *   wireless media entirely.
 * - `wireless: true` — this build uses the adapter's own radios, which is also why the negotiated media
 *   format is AAC-LC 48 kHz stereo (`preset: wireless_8`) rather than the wired PCM path.
 */
data class VehicleConfigSpec(
    val name: String = "CarLink GM 2400x960",
    val width: Int = 2400,
    val height: Int = 960,
    val physicalWidthMm: Int = 0,
    val physicalHeightMm: Int = 0,
    /** The box accepts 30 or 60 only; anything else is dropped and the compiled default stands. */
    val maxFps: Int = 60,
    /** Safe area, defaulting to the full panel. Derive from display-cutout insets when they exist. */
    val safeOriginX: Int = 0,
    val safeOriginY: Int = 0,
    val safeWidth: Int = width,
    val safeHeight: Int = height,
    val drawUIOutsideSafeArea: Boolean = false,
    val wireless: Boolean = true,
    val pairing: String = "just_works",
    val enablesHevc: Boolean = true,
    /** Steering-wheel media keys reach CarPlay through this HID, not a steering-wheel device. */
    val mediaButtonsSupport: Boolean = true,
    val dPadSupport: Boolean = false,
    val telephonyButtonsSupport: Boolean = false,
    /**
     * `touchScreenSupportsMultiTouch` — advertise Apple's TWO-finger touchscreen descriptor
     * (`HIDTouchScreenMultiCreateDescriptor`) instead of the single-finger one.
     *
     * This is what makes pinch/zoom/rotate work. It changes the uid-1 HID descriptor and therefore
     * the report LAYOUT (12 bytes vs 5), so the box gates its report builder on the same flag — the
     * two must never disagree.
     */
    val touchScreenSupportsMultiTouch: Boolean = true,
    val appDrivenSetup: Boolean = false,
    val metadataTier: String = "proven",
    /**
     * Apple `limitedUIConfig` — WHICH elements iOS restricts once `setLimitedUI(true)` is sent.
     *
     * This is a DECLARATION, not the switch. The runtime on/off is `CMD_LIMITED_UI_ON/OFF` on
     * CH_INPUT. Leaving this section out entirely does NOT mean "restrict everything" — the box
     * then omits `limitedUIElements` from `/info` and iOS falls back to its own default set, which
     * was measured on device to leave the Maps search keyboard visible. So the drive-state feature
     * is inert without these.
     *
     * Only these six reach the wire; `pairedDevices`/`themeCustomization`/`automakerSettings`/
     * `automakerSettingsInfoButton` are real Apple CodingKeys that `airPlayElements` never emits.
     */
    val limitedUiSoftKeyboard: Boolean = true,
    val limitedUiSoftPhoneKeypad: Boolean = true,
    val limitedUiNonMusicLists: Boolean = true,
    val limitedUiMusicLists: Boolean = true,
    /** Japan-market Maps restriction; off outside that market. */
    val limitedUiJapanMaps: Boolean = false,
    val limitedUiLongAlerts: Boolean = true,
    /**
     * CarPlay home-screen tile that returns to this app. Empty means the section is omitted, so the
     * pinned document stays byte-identical. A partial size set is what renders as a blank tile.
     */
    val oemIconImages: List<OemIcon.Image> = emptyList(),
    val oemIconLabel: String = "",
    val oemIconVisible: Boolean = true,
    /**
     * A second main view area, CarPlay's part of the split layout ([wasidremin.gmccpa.pane.SplitPane]),
     * or null for one area. Emitted as `viewAreas[1]`, with the box's `initial: true` extension key
     * when [viewArea2Initial] so the session STARTS in it (`vehicle_config.rs::ViewAreaEntry`; without
     * the key it starts full screen), and it arms `accessoryConfig.enablesViewAreas`; the box derives
     * `viewAreaTransitionControl`, `initialViewArea` and `adjacentViewAreas` for `/info`. Null keeps
     * the pinned document.
     */
    val viewArea2: PixelRect? = null,
    /** The session starts in [viewArea2] (the pane showing) rather than full screen. */
    val viewArea2Initial: Boolean = true,
    /**
     * The box's top-level `view_area_anim_ms` extension key: the `animationDurationMillis` of its
     * `updateViewArea`. Null omits the key (the box then sends 3000).
     */
    val viewAreaAnimMs: Int? = null,
    /**
     * The driver display's CarPlay map ([wasidremin.gmccpa.nav.DriverDisplay]), or null for none.
     * Emitted as Apple's instrument-cluster pair, `altDisplayPanels[0]` (`showsInstruments`) and
     * `altVideoStreams[0]` (30 fps, `initialURL: maps:/car/instrumentcluster/map`); the box then
     * advertises the type-111 display and iOS sets the stream up by itself
     * (`docs/carplay/06_AV_PIPELINE.md` "Instrument-cluster video"). Null keeps both lists empty.
     */
    val cluster: PixelSize? = null,
    /**
     * The part of the driver display CarPlay keeps its directions, arrival time and other non-map
     * content inside ([wasidremin.gmccpa.nav.DriverDisplay.safeArea]), in [cluster] pixels; null for
     * the whole display. The map still fills [cluster]. The box carries a real inset into `/info` as
     * the type-111 screen's `safeArea` (`vehicle_config.rs` `alt_safe_area`) and arms the
     * `viewAreas` feature for it.
     */
    val clusterSafe: PixelRect? = null,
) {
    init {
        require(maxFps == 30 || maxFps == 60) { "maxFPS must be 30 or 60; the box ignores anything else" }
        require(width > 0 && height > 0) { "pixelDimensions must be positive" }
        require(metadataTier in LEGAL_TIERS) { "metadata tier must be one of $LEGAL_TIERS" }
        viewAreaAnimMs?.let { require(it in VIEW_AREA_ANIM_MS_RANGE) { "view_area_anim_ms must be in $VIEW_AREA_ANIM_MS_RANGE; the box clamps anything else" } }
        // The split's CarPlay area: a teardown rule ends the session, the floor locks it out black.
        viewArea2?.let { a ->
            ViewAreaRule.teardownVerdict(a, width, height)?.let { throw IllegalArgumentException("viewArea 2: $it") }
            ViewAreaRule.floorVerdict(a.width, a.height)?.let { throw IllegalArgumentException("viewArea 2: $it") }
        }
        cluster?.let { c ->
            require(c.width % 2 == 0 && c.height % 2 == 0 && c.width in 2..3840 && c.height in 2..1600) {
                "cluster screen must be a positive even size inside 3840x1600"
            }
        }
        clusterSafe?.let { a ->
            val c = requireNotNull(cluster) { "a cluster safe area needs a cluster screen" }
            require(a.x >= 0 && a.y >= 0 && a.width > 0 && a.height > 0 && a.right <= c.width && a.y + a.height <= c.height) {
                "cluster safe area must sit inside the cluster screen"
            }
        }
    }

    companion object {
        /**
         * `rx-only` is deliberately absent: it is a refuted dead end that the box's app-pushed path
         * rejects outright. `all` is refuted on this hardware — it draws an unrecoverable `0x1D03`
         * Identify reject that costs the whole CarPlay session, not just the metadata.
         */
        val LEGAL_TIERS = setOf("proven", "extended", "all")

        /** The box's range for `view_area_anim_ms` (`levers::VIEW_AREA_ANIM_MS_RANGE`; floor 100 since 2026-11-07). */
        val VIEW_AREA_ANIM_MS_RANGE = 100..10_000
    }
}

/** A rectangle in advertised pixels. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
}

/** A size in pixels. */
data class PixelSize(val width: Int, val height: Int)

/**
 * What a view area may be. Ported from enigy/carlink `ocbm/VehicleConfigYaml.kt` (Unlicense), itself
 * from the macOS host's `ViewArea2Rule`, where every rule below was device-proven.
 *
 * Severity: containment, parity and positivity END THE SESSION; the product floor only locks the
 * area out black ("CarPlay does not support this display resolution") with the session alive.
 */
object ViewAreaRule {
    /** PRODUCT FLOOR: 800 x 480 landscape, 480 x 800 portrait, the documented CarPlay minimum. */
    const val LANDSCAPE_FLOOR_W = 800
    const val LANDSCAPE_FLOOR_H = 480
    const val PORTRAIT_FLOOR_W = 480
    const val PORTRAIT_FLOOR_H = 800

    /** The floor for an area of this aspect: `w >= h` is landscape. */
    fun minimumSize(w: Int, h: Int): Pair<Int, Int> =
        if (w >= h) LANDSCAPE_FLOOR_W to LANDSCAPE_FLOOR_H else PORTRAIT_FLOOR_W to PORTRAIT_FLOOR_H

    /**
     * Null = legal for the session. Otherwise the FIRST teardown rule violated: containment in the
     * panel, all four values even (HEVC 4:2:0 cannot express an odd extent), positive size and a
     * non-negative origin.
     */
    fun teardownVerdict(a: PixelRect, panelW: Int, panelH: Int): String? {
        val right = a.x.toLong() + a.width
        val bottom = a.y.toLong() + a.height
        if (right > panelW || bottom > panelH) {
            return "area extends to ${right}x$bottom px, outside the ${panelW}x$panelH panel — CarPlay tears the session down"
        }
        val odd = listOf("X" to a.x, "Y" to a.y, "width" to a.width, "height" to a.height).filter { it.second and 1 != 0 }
        if (odd.isNotEmpty()) return "odd value: ${odd.joinToString { "${it.first} ${it.second}" }} — all four must be even"
        if (a.width <= 0 || a.height <= 0) return "width and height must be positive"
        if (a.x < 0 || a.y < 0) return "the origin must be non-negative"
        return null
    }

    /** Null = at or above the product floor. Otherwise the lockout (not teardown) message. */
    fun floorVerdict(w: Int, h: Int): String? {
        val (mw, mh) = minimumSize(w, h)
        if (w >= mw && h >= mh) return null
        return "area ${w}x$h is below the ${if (w >= h) "landscape" else "portrait"} floor of ${mw}x$mh — CarPlay locks it out black"
    }
}

object VehicleConfigYaml {
    /** Render the document. Ends with a trailing newline — see the class doc for why that matters. */
    fun render(s: VehicleConfigSpec): String {
        val b = StringBuilder(2048)
        header(b, s)
        displayPanels(b, s)
        videoStreams(b, s)
        accessoryConfig(b, s)
        limitedUiConfig(b, s)
        oemIconConfig(b, s)
        audioAndMetadata(b, s)
        return b.toString()
    }

    private fun StringBuilder.line(v: String) = append(v).append('\n')

    private fun header(
        b: StringBuilder,
        s: VehicleConfigSpec,
    ) = with(b) {
        line("name: \"${escape(s.name)}\"")
        line("version: 1")
        line("wireless: ${s.wireless}")
        line("hot_handover: false")
        line("pairing: ${s.pairing}")
        line("rightHandDrive: false")
        // Emitted for shape parity with the pinned fixture; the box does not read it. Night mode is a
        // runtime /command (CMD_NIGHT_MODE), not a config value.
        line("nightMode: false")
        s.viewAreaAnimMs?.let { line("view_area_anim_ms: $it") }
    }

    private fun displayPanels(
        b: StringBuilder,
        s: VehicleConfigSpec,
    ) = with(b) {
        line("displayPanelsConfig:")
        line("  mainDisplayPanel:")
        line("    displayPanelID: DisplayPanel.Main")
        line("    pixelDimensions:")
        line("      width: ${s.width}")
        line("      height: ${s.height}")
        if (s.physicalWidthMm > 0 && s.physicalHeightMm > 0) {
            line("    physicalDimensions:")
            line("      width: ${s.physicalWidthMm}")
            line("      height: ${s.physicalHeightMm}")
        }
        val c = s.cluster
        if (c == null) {
            line("  altDisplayPanels: []")
        } else {
            line("  altDisplayPanels:")
            line("  - displayPanelID: DisplayPanel.Alt1")
            line("    pixelDimensions:")
            line("      width: ${c.width}")
            line("      height: ${c.height}")
            line("    displayProperties:")
            line("    - showsInstruments")
        }
    }

    private fun videoStreams(
        b: StringBuilder,
        s: VehicleConfigSpec,
    ) = with(b) {
        line("videoStreamsConfig:")
        line("  mainVideoStream:")
        line("    videoStreamID: VideoStream.Main")
        line("    pixelDimensions:")
        line("      width: ${s.width}")
        line("      height: ${s.height}")
        line("    maxFPS: ${s.maxFps}")
        line("    viewAreas:")
        line("    - viewArea:")
        line("        originX: 0")
        line("        originY: 0")
        line("        width: ${s.width}")
        line("        height: ${s.height}")
        line("      safeArea:")
        line("        originX: ${s.safeOriginX}")
        line("        originY: ${s.safeOriginY}")
        line("        width: ${s.safeWidth}")
        line("        height: ${s.safeHeight}")
        line("      drawUIOutsideSafeArea: ${s.drawUIOutsideSafeArea}")
        // The split layout's CarPlay area, index 1, directly after entry 0 and before `hidConfig`,
        // still inside the `viewAreas` list (a list item after `primaryInput` is not valid YAML at
        // this indent, and serde would reject the whole subscribe). Its safe area is the area itself;
        // the box reads only `viewArea` and `initial` from this entry (`second_main_view_area`).
        s.viewArea2?.let { a ->
            line("    - viewArea:")
            line("        originX: ${a.x}")
            line("        originY: ${a.y}")
            line("        width: ${a.width}")
            line("        height: ${a.height}")
            line("      safeArea:")
            line("        originX: ${a.x}")
            line("        originY: ${a.y}")
            line("        width: ${a.width}")
            line("        height: ${a.height}")
            line("      drawUIOutsideSafeArea: false")
            if (s.viewArea2Initial) line("      initial: true")
        }
        line("    hidConfig:")
        line("      dPadSupport: ${s.dPadSupport}")
        line("      knobSupport: false")
        line("      knobSupportsHomeAndBackButton: false")
        line("      knobSupportsNudge: false")
        line("      mediaButtonsSupport: ${s.mediaButtonsSupport}")
        line("      telephonyButtonsSupport: ${s.telephonyButtonsSupport}")
        line("      touchpadSupport: false")
        line("      touchpadButtonsSupport: false")
        line("      touchScreenMode: High Fidelty")
        line("      touchScreenSupportsCancel: true")
        line("      touchScreenSupportsMultiTouch: ${s.touchScreenSupportsMultiTouch}")
        // Emitted LAST in hidConfig, matching the reference emitter's order.
        line("      steeringWheelSupport: false")
        line("    primaryInput: Touchpad")
        val c = s.cluster
        if (c == null) line("  altVideoStreams: []") else clusterStream(b, c, s.clusterSafe)
    }

    /**
     * Apple's "Instrument Cluster" template shape. The whole screen is the map's view area; the safe
     * area is [safe] (the owner's directions area) or the whole screen.
     */
    private fun clusterStream(
        b: StringBuilder,
        c: PixelSize,
        safe: PixelRect?,
    ) = with(b) {
        line("  altVideoStreams:")
        line("  - videoStreamID: VideoStream.Alt1")
        line("    pixelDimensions:")
        line("      width: ${c.width}")
        line("      height: ${c.height}")
        line("    maxFPS: 30")
        line("    viewAreas:")
        line("    - viewArea:")
        line("        originX: 0")
        line("        originY: 0")
        line("        width: ${c.width}")
        line("        height: ${c.height}")
        line("      safeArea:")
        line("        originX: ${safe?.x ?: 0}")
        line("        originY: ${safe?.y ?: 0}")
        line("        width: ${safe?.width ?: c.width}")
        line("        height: ${safe?.height ?: c.height}")
        line("      drawUIOutsideSafeArea: false")
        line("    initialURL: maps:/car/instrumentcluster/map")
    }

    private fun accessoryConfig(
        b: StringBuilder,
        s: VehicleConfigSpec,
    ) = with(b) {
        line("accessoryConfig:")
        line("  enablesMainBufferedAudio: false")
        line("  enablesHEVC: ${s.enablesHevc}")
        line("  enablesUIAppearance: true")
        line("  enablesMapAppearance: true")
        line("  enablesCornerMasks: false")
        line("  enablesVideoPlayback: true")
        line("  enablesViewAreas: ${s.viewArea2 != null}")
        line("  enablesEnhancedSiri: false")
        line("  enablesFocusTransfer: false")
        line("  enablesUIContext: false")
        line("  enablesUISync: false")
        line("  enablesFileTransfer: false")
        line("  enablesLogTransfer: false")
        line("  enablesVehicleDataProtocol: false")
        line("  enablesDCX: false")
        line("  appDrivenSetup: ${s.appDrivenSetup}")
    }

    /**
     * `limitedUIConfig` — a top-level sibling of `accessoryConfig`, emitted between it and `audio`
     * to match the box's own struct order (`vehicle_config.rs:62-68`).
     *
     * Key names are the YAML spellings, which differ from the wire strings the box derives: the
     * `longAlerts` key is emitted to iOS as `longUserAlert`, and `musicLists` precedes
     * `nonMusicLists` on the wire even though Apple's own header declares them the other way round.
     * Both are the box's problem, not ours — we emit the config spellings.
     */
    private fun limitedUiConfig(
        b: StringBuilder,
        s: VehicleConfigSpec,
    ) = with(b) {
        line("limitedUIConfig:")
        line("  softKeyboard: ${s.limitedUiSoftKeyboard}")
        line("  softPhoneKeypad: ${s.limitedUiSoftPhoneKeypad}")
        line("  nonMusicLists: ${s.limitedUiNonMusicLists}")
        line("  musicLists: ${s.limitedUiMusicLists}")
        line("  japanMaps: ${s.limitedUiJapanMaps}")
        line("  longAlerts: ${s.limitedUiLongAlerts}")
    }

    /** Between `limitedUIConfig` and `audio`, matching the box struct. Omitted when there is no image. */
    private fun oemIconConfig(
        b: StringBuilder,
        s: VehicleConfigSpec,
    ) = with(b) {
        if (s.oemIconImages.isEmpty()) return@with
        line("oemIconConfig:")
        line("  images:")
        for (img in s.oemIconImages) {
            line("    - width: ${img.width}")
            line("      height: ${img.height}")
            line("      imageBase64: \"${img.base64}\"")
        }
        if (s.oemIconLabel.isNotEmpty()) line("  label: \"${escape(s.oemIconLabel)}\"")
        line("  visible: ${s.oemIconVisible}")
    }

    private fun audioAndMetadata(
        b: StringBuilder,
        s: VehicleConfigSpec,
    ) = with(b) {
        // Both transport arms are always emitted; the box selects by the live transport. `wireless_8`
        // is what makes media AAC-LC 48 kHz stereo on this build.
        line("audio:")
        line("  wired:")
        line("    preset: wired_pcm")
        line("  wireless:")
        line("    preset: wireless_8")

        line("metadata:")
        line("  tier: ${s.metadataTier}")
    }

    fun renderBytes(s: VehicleConfigSpec): ByteArray = render(s).toByteArray(Charsets.UTF_8)

    /**
     * Render [v] as the BODY of a double-quoted YAML scalar (the caller supplies the quotes).
     *
     * Two distinct hazards, and each takes out the WHOLE document rather than just this field, because
     * the box then falls back to its compiled defaults for resolution, HEVC, audio and the metadata
     * tier at once:
     *
     *  1. `"` and `\` must be escaped. An unescaped `"` closes the scalar early. An unescaped `\` is
     *     worse than it looks — `\s` is an invalid escape and fails loudly, but `\b` is a *valid* one
     *     and silently yields a backspace, corrupting the value with no error at all.
     *  2. **Cc control characters are rejected at the YAML stream level, which no escaping here can
     *     fix.** They must be STRIPPED, not escaped.
     *
     * The stripped set is Cc only — C0 (`0x00`-`0x1F`), DEL and C1 (`0x7F`-`0x9F`) — matching the
     * macOS app's reference emitter (`VehicleConfig.swift`, `YamlEmit`; removed 2026-10-27), whose
     * comment records the set as verified codepoint-by-codepoint against the box's own `serde_yaml` 0.9
     * and libyaml rather than reasoned about.
     *
     * Two deliberate properties, both easy to "fix" back into a bug:
     *  - The set is a SUPERSET of what the parser strictly rejects. Tab, LF, CR and NEL parse fine —
     *    they fold to spaces — but these are single-line display fields where a folded newline is never
     *    what the user meant, so they go too. Do not narrow it to the truly fatal list.
     *  - **Cf is preserved.** Every format character (ZWJ, ZWSP, soft hyphen, LRM/RLM, BOM) parses
     *    fine, and stripping them silently mangles real content — an emoji ZWJ sequence collapses into
     *    separate glyphs, an RTL label reorders. Do not reach for a blanket control-plus-format test.
     *
     * Escaping each character in one pass also sidesteps the ordering hazard the Swift documents:
     * with chained replacements, backslash must be escaped before quote or the quote pass
     * double-escapes the backslashes the first pass introduced.
     */
    private fun escape(v: String): String =
        buildString(v.length + 8) {
            for (c in v) {
                val code = c.code
                when {
                    // Cc — STRIP. Escaping cannot save these; see the KDoc.
                    code <= 0x1F || code in 0x7F..0x9F -> Unit
                    c == '\\' -> append("\\\\")
                    c == '"' -> append("\\\"")
                    else -> append(c)
                }
            }
        }

    /**
     * The default document, byte for byte. A missing newline glues the next key onto the previous
     * scalar; serde then rejects the whole file and the adapter falls back to H.264. [HevcRenderer]
     * cannot play that, and the failure is silent on the box.
     *
     * Was kept equal to CarlinkAndroid's `VehicleConfigYamlTest` expected literal (oem icon omitted,
     * which the default emitter already skips) until that app was removed on 2026-10-27.
     */
    const val PINNED_DEFAULT: String =
        "name: \"CarLink GM 2400x960\"\n" +
            "version: 1\n" +
            "wireless: true\n" +
            "hot_handover: false\n" +
            "pairing: just_works\n" +
            "rightHandDrive: false\n" +
            "nightMode: false\n" +
            "displayPanelsConfig:\n" +
            "  mainDisplayPanel:\n" +
            "    displayPanelID: DisplayPanel.Main\n" +
            "    pixelDimensions:\n" +
            "      width: 2400\n" +
            "      height: 960\n" +
            "  altDisplayPanels: []\n" +
            "videoStreamsConfig:\n" +
            "  mainVideoStream:\n" +
            "    videoStreamID: VideoStream.Main\n" +
            "    pixelDimensions:\n" +
            "      width: 2400\n" +
            "      height: 960\n" +
            "    maxFPS: 60\n" +
            "    viewAreas:\n" +
            "    - viewArea:\n" +
            "        originX: 0\n" +
            "        originY: 0\n" +
            "        width: 2400\n" +
            "        height: 960\n" +
            "      safeArea:\n" +
            "        originX: 0\n" +
            "        originY: 0\n" +
            "        width: 2400\n" +
            "        height: 960\n" +
            "      drawUIOutsideSafeArea: false\n" +
            "    hidConfig:\n" +
            "      dPadSupport: false\n" +
            "      knobSupport: false\n" +
            "      knobSupportsHomeAndBackButton: false\n" +
            "      knobSupportsNudge: false\n" +
            "      mediaButtonsSupport: true\n" +
            "      telephonyButtonsSupport: false\n" +
            "      touchpadSupport: false\n" +
            "      touchpadButtonsSupport: false\n" +
            "      touchScreenMode: High Fidelty\n" +
            "      touchScreenSupportsCancel: true\n" +
            "      touchScreenSupportsMultiTouch: true\n" +
            "      steeringWheelSupport: false\n" +
            "    primaryInput: Touchpad\n" +
            "  altVideoStreams: []\n" +
            "accessoryConfig:\n" +
            "  enablesMainBufferedAudio: false\n" +
            "  enablesHEVC: true\n" +
            "  enablesUIAppearance: true\n" +
            "  enablesMapAppearance: true\n" +
            "  enablesCornerMasks: false\n" +
            "  enablesVideoPlayback: true\n" +
            "  enablesViewAreas: false\n" +
            "  enablesEnhancedSiri: false\n" +
            "  enablesFocusTransfer: false\n" +
            "  enablesUIContext: false\n" +
            "  enablesUISync: false\n" +
            "  enablesFileTransfer: false\n" +
            "  enablesLogTransfer: false\n" +
            "  enablesVehicleDataProtocol: false\n" +
            "  enablesDCX: false\n" +
            "  appDrivenSetup: false\n" +
            "limitedUIConfig:\n" +
            "  softKeyboard: true\n" +
            "  softPhoneKeypad: true\n" +
            "  nonMusicLists: true\n" +
            "  musicLists: true\n" +
            "  japanMaps: false\n" +
            "  longAlerts: true\n" +
            "audio:\n" +
            "  wired:\n" +
            "    preset: wired_pcm\n" +
            "  wireless:\n" +
            "    preset: wireless_8\n" +
            "metadata:\n" +
            "  tier: proven\n"

    /** True when [render] of the defaults still matches [PINNED_DEFAULT]. */
    fun defaultIsPinned(): Boolean = render(VehicleConfigSpec()) == PINNED_DEFAULT

    /**
     * Session config for the adapter-Wi-Fi role, plus the three keys
     * [tools/session_supervisor.sh] greps raw. [ssid] is `ccpa-<4hex>` once the app has learned it,
     * and the placeholder `ccpa` only until then. The supervisor writes `wifi_ssid` into
     * `hostapd.conf` on every bring-up, and `0x5703` reads that file. The vehicle schema ignores
     * these keys (`serde` has no `deny_unknown_fields`).
     */
    fun renderAdapter(
        passphrase: String,
        ssid: String = AdapterWifi.SSID_PLACEHOLDER,
        width: Int = 2400,
        height: Int = 960,
        safeRight: Int = 0,
        telephony: Boolean = false,
        physicalWidthMm: Int = 0,
        physicalHeightMm: Int = 0,
        /** The EV identity block from [wasidremin.gmccpa.ev.CarPlayEv.iapConfigYaml]; "" when off. */
        iapConfig: String = "",
        /** CarPlay's part of the split layout ([wasidremin.gmccpa.pane.SplitPane]); null for full screen. */
        viewArea2: PixelRect? = null,
        /** The session starts split (the pane showing) rather than full screen, when [viewArea2] is set. */
        viewArea2Initial: Boolean = true,
        /** How long a switch between the split and full screen animates, when [viewArea2] is set. */
        viewAreaAnimMs: Int? = null,
        /** The driver display's CarPlay map ([wasidremin.gmccpa.nav.DriverDisplay]); null for none. */
        cluster: PixelSize? = null,
        /** The driver display's directions area, in [cluster] pixels; null for the whole display. */
        clusterSafe: PixelRect? = null,
    ): ByteArray {
        require(iapConfig.isEmpty() || (iapConfig.startsWith("iapConfig:\n") && iapConfig.endsWith("\n"))) {
            "iapConfig must be a whole top-level block ending in a newline"
        }
        require(passphrase.length >= 8 && passphrase.all { it.isLetterOrDigit() }) {
            "adapter passphrase must be at least 8 letters or digits"
        }
        require(ssid == AdapterWifi.SSID_PLACEHOLDER || Regex("ccpa-[0-9a-f]{4}").matches(ssid)) {
            "adapter ssid must be the placeholder or ccpa-<4 hex>"
        }
        // 2400 was the Silverado panel, not a protocol limit. The Equinox window measures wider
        // (2914 on 2026-09-24, pid 14696). Capping there aborted subscribe before the phone was
        // ever told a size, so CarPlay never started. 3840 is a 4K-wide ceiling, still even.
        require(width % 2 == 0 && height % 2 == 0 && width in 2..3840 && height in 2..1600) {
            "adapter video size must be a positive even size inside 3840x1600"
        }
        val pinned = render(VehicleConfigSpec())
        check(pinned == PINNED_DEFAULT) { "vehicle config drifted from the pinned document — refusing to subscribe" }
        // The pin guards the emitter. A sidebar session is the same document with a narrower main
        // panel, produced by that emitter after the pin has passed — not a hand-edited copy.
        // safeRight is an advertised-pixel inset on the right. 0 keeps the full-frame safe area
        // the box logs as safe=None. A real inset uses the existing safeArea keys.
        // viewAreas[1] (the split pane's CarPlay area) is emitted by [videoStreams] inside the list,
        // never after primaryInput: a second list item there is not valid YAML at this indent, and
        // serde then rejects the whole subscribe — including wifi_ap — so the phone is never handed
        // the adapter network. [structuralError] checks the result before it is sent.
        val inset = safeRight.coerceIn(0, width - 2) and 1.inv()
        val doc = if (width == 2400 && height == 960 && inset == 0 && !telephony && physicalWidthMm == 0 && physicalHeightMm == 0 &&
            viewArea2 == null && cluster == null) pinned
            else render(VehicleConfigSpec(
                name = "CarLink GM ${width}x${height}",
                width = width,
                height = height,
                physicalWidthMm = physicalWidthMm,
                physicalHeightMm = physicalHeightMm,
                safeWidth = width - inset,
                safeHeight = height,
                telephonyButtonsSupport = telephony,
                viewArea2 = viewArea2,
                viewArea2Initial = viewArea2Initial,
                viewAreaAnimMs = viewArea2?.let { viewAreaAnimMs },
                cluster = cluster,
                clusterSafe = clusterSafe?.takeIf { cluster != null },
            ))
        // After the pinned body so a 2400 session still starts with that document. serde matches
        // keys by name, so the block does not have to sit between limitedUIConfig and audio.
        val oemRaw = oemIconYaml(OemIcon.gear(), "Settings")
        val extra = "wifi_ap: true\nwifi_ssid: $ssid\nwifi_pass: $passphrase\nwifi_channel: ${AdapterWifi.CHANNEL}\n"
        val oem = if ((doc.length + oemRaw.length + extra.length + iapConfig.length) > 60_000) "" else oemRaw
        return (doc + oem + iapConfig + extra).toByteArray(Charsets.UTF_8)
    }

    private fun oemIconYaml(images: List<OemIcon.Image>, label: String): String {
        if (images.isEmpty()) return ""
        val b = StringBuilder()
        b.append("oemIconConfig:\n  images:\n")
        for (img in images) {
            b.append("    - width: ${img.width}\n")
            b.append("      height: ${img.height}\n")
            b.append("      imageBase64: \"${img.base64}\"\n")
        }
        if (label.isNotEmpty()) b.append("  label: \"$label\"\n")
        b.append("  visible: true\n")
        return b.toString()
    }

    /**
     * Structural check before `CT_SUBSCRIBE`. serde rejects the whole document — including
     * `wifi_ap` — and the box says nothing. Returns `line N: …`, or null when the document is safe
     * to send. [adapterWifiOn] requires the four AP keys at indent 0; the Silverado document
     * (`wifi_ap: false`) does not.
     *
     * A list item is legal under a bare `key:` (same indent, as `viewAreas:` / `- viewArea:`, or
     * two spaces deeper, as `images:` / `- width:`) and as a further item of a sequence already
     * open at that dash indent. A dash after a scalar (`primaryInput: Touchpad` then `- viewArea`)
     * is the 4.0+scale failure. A deeper line under `key: value` is rejected too.
     */
    fun structuralError(yaml: String, adapterWifiOn: Boolean): String? {
        var prevIndent = -1
        var prevBare = false
        var prevItem = false
        var seqDash: Int? = null
        var mainIndent = Int.MIN_VALUE
        var sawMain = false
        var viewAreas = 0
        val root = HashSet<String>()
        yaml.split('\n').forEachIndexed { index, raw ->
            val lineNo = index + 1
            if (raw.isBlank()) return@forEachIndexed
            if ('\t' in raw) return "line $lineNo: tab in indentation"
            val indent = raw.indexOfFirst { it != ' ' }
            if (indent % 2 != 0) return "line $lineNo: indent is not a multiple of 2"
            val body = raw.substring(indent)
            val isItem = body.startsWith("- ")
            val open = seqDash
            if (open != null && !(isItem && indent == open) && indent <= open) seqDash = null
            if (indent > prevIndent && !prevBare && prevIndent >= 0) {
                val listSibling = prevItem && seqDash != null && indent == prevIndent + 2
                if (!listSibling) return "line $lineNo: child indented under a scalar"
            }
            if (isItem) {
                val continuing = seqDash == indent
                val underBare = prevBare && (indent == prevIndent || indent == prevIndent + 2)
                if (!continuing && !underBare) return "line $lineNo: list item is not under a key"
                if (!continuing) seqDash = indent
            }
            if (mainIndent != Int.MIN_VALUE && indent <= mainIndent) mainIndent = Int.MIN_VALUE
            val content = if (isItem) body.substring(2) else body
            val colon = content.indexOf(':')
            val key = if (colon >= 0) content.substring(0, colon).trim() else ""
            val bare = colon >= 0 && content.substring(colon + 1).isBlank()
            if (indent == 0 && key.isNotEmpty()) root.add(key)
            if (key == "mainVideoStream" && bare) {
                sawMain = true
                mainIndent = indent
            }
            if (key == "viewAreas" && bare && mainIndent != Int.MIN_VALUE && indent > mainIndent) {
                viewAreas++
            }
            prevIndent = indent
            prevBare = bare
            prevItem = isItem
        }
        if (adapterWifiOn) {
            for (k in listOf("wifi_ap", "wifi_ssid", "wifi_pass", "wifi_channel")) {
                if (k !in root) return "line 1: missing $k"
            }
        }
        if (sawMain && viewAreas != 1) {
            return "line 1: expected one viewAreas list under mainVideoStream, found $viewAreas"
        }
        return null
    }
}
