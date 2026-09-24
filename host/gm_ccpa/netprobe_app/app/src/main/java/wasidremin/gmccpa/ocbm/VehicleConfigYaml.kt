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
 * That is a recorded incident, not a hypothetical: `tools/regen_app_yaml_fixture.py:1-14` describes a
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
) {
    init {
        require(maxFps == 30 || maxFps == 60) { "maxFPS must be 30 or 60; the box ignores anything else" }
        require(width > 0 && height > 0) { "pixelDimensions must be positive" }
        require(metadataTier in LEGAL_TIERS) { "metadata tier must be one of $LEGAL_TIERS" }
    }

    companion object {
        /**
         * `rx-only` is deliberately absent: it is a refuted dead end that the box's app-pushed path
         * rejects outright. `all` is refuted on this hardware — it draws an unrecoverable `0x1D03`
         * Identify reject that costs the whole CarPlay session, not just the metadata.
         */
        val LEGAL_TIERS = setOf("proven", "extended", "all")
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
        line("  altDisplayPanels: []")
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
        line("    hidConfig:")
        line("      dPadSupport: ${s.dPadSupport}")
        line("      knobSupport: false")
        line("      knobSupportsHomeAndBackButton: false")
        line("      knobSupportsNudge: false")
        line("      mediaButtonsSupport: ${s.mediaButtonsSupport}")
        line("      telephonyButtonsSupport: false")
        line("      touchpadSupport: false")
        line("      touchpadButtonsSupport: false")
        line("      touchScreenMode: High Fidelty")
        line("      touchScreenSupportsCancel: true")
        line("      touchScreenSupportsMultiTouch: ${s.touchScreenSupportsMultiTouch}")
        // Emitted LAST in hidConfig, matching the reference emitter's order.
        line("      steeringWheelSupport: false")
        line("    primaryInput: Touchpad")
        line("  altVideoStreams: []")
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
        line("  enablesViewAreas: false")
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
     * reference emitter (`host/MacHost/carlink_macOS/App/VehicleConfig.swift`, `YamlEmit`), whose
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
     * Kept equal to CarlinkAndroid's `VehicleConfigYamlTest` expected literal (oem icon omitted,
     * which the default emitter already skips).
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
    ): ByteArray {
        require(passphrase.length >= 8 && passphrase.all { it.isLetterOrDigit() }) {
            "adapter passphrase must be at least 8 letters or digits"
        }
        require(ssid == AdapterWifi.SSID_PLACEHOLDER || Regex("ccpa-[0-9a-f]{4}").matches(ssid)) {
            "adapter ssid must be the placeholder or ccpa-<4 hex>"
        }
        require(width % 2 == 0 && height % 2 == 0 && width in 2..2400 && height in 2..960) {
            "adapter video size must be a positive even size inside 2400x960"
        }
        val pinned = render(VehicleConfigSpec())
        check(pinned == PINNED_DEFAULT) { "vehicle config drifted from the pinned document — refusing to subscribe" }
        // The pin guards the emitter. A sidebar session is the same document with a narrower main
        // panel, produced by that emitter after the pin has passed — not a hand-edited copy.
        val doc = if (width == 2400 && height == 960) pinned
            else render(VehicleConfigSpec(name = "CarLink GM ${width}x${height}", width = width, height = height))
        val extra = "wifi_ap: true\nwifi_ssid: $ssid\nwifi_pass: $passphrase\nwifi_channel: ${AdapterWifi.CHANNEL}\n"
        return (doc + extra).toByteArray(Charsets.UTF_8)
    }
}
