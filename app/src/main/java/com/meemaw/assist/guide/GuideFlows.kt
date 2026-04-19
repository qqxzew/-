package com.meemaw.assist.guide

/**
 * Predefined floating-guide flows that walk the user through the part of a
 * settings flow that the app cannot automate itself (toggles, password
 * prompts, etc.).
 */
object GuideFlows {

    fun wifiOn() = GuideFlow(
        id = "wifi_on",
        steps = listOf(
            GuideStep(
                title = "Turn on Wi-Fi",
                body = "Tap the Wi-Fi switch at the top so it turns blue.",
                advanceKeywords = listOf("Wi-Fi is on", "Wi‑Fi is on", "Turn off Wi-Fi", "Turn off Wi‑Fi")
            ),
            GuideStep(
                title = "Pick your network",
                body = "Tap your home Wi-Fi name in the list.",
                advanceKeywords = listOf("Password", "Enter password", "Connect"),
            ),
            GuideStep(
                title = "All done",
                body = "Wi-Fi is on — you can close this hint.",
                finalStep = true
            )
        )
    )

    fun wifiOff() = GuideFlow(
        id = "wifi_off",
        steps = listOf(
            GuideStep(
                title = "Turn off Wi-Fi",
                body = "Tap the Wi-Fi switch so it turns grey.",
                advanceKeywords = listOf("Wi-Fi is off", "Wi‑Fi is off", "Turn on Wi-Fi", "Turn on Wi‑Fi")
            ),
            GuideStep(
                title = "All done",
                body = "Wi-Fi is turned off.",
                finalStep = true
            )
        )
    )

    fun bluetoothOn() = GuideFlow(
        id = "bluetooth_on",
        steps = listOf(
            GuideStep(
                title = "Turn on Bluetooth",
                body = "Tap the Bluetooth switch so it turns blue.",
                advanceKeywords = listOf(
                    "Bluetooth is on",
                    "Pair new device",
                    "Available devices",
                    "Turn off"
                )
            ),
            GuideStep(
                title = "All done",
                body = "Bluetooth is on. You can close this hint.",
                finalStep = true
            )
        )
    )

    fun bluetoothOff() = GuideFlow(
        id = "bluetooth_off",
        steps = listOf(
            GuideStep(
                title = "Turn off Bluetooth",
                body = "Tap the Bluetooth switch so it turns grey.",
                advanceKeywords = listOf("Bluetooth is off", "Turn on")
            ),
            GuideStep(
                title = "All done",
                body = "Bluetooth is turned off.",
                finalStep = true
            )
        )
    )

    fun brightnessWriteSettings() = GuideFlow(
        id = "brightness_write_settings",
        steps = listOf(
            GuideStep(
                title = "Allow MeemawAssist",
                body = "Tap the switch to allow changing system settings.",
                advanceKeywords = listOf("Allowed")
            ),
            GuideStep(
                title = "Go back",
                body = "Tap the back arrow at the top-left to return.",
                finalStep = true
            )
        )
    )
}
