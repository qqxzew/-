package com.meemaw.assist.guide

/**
 * One step of a floating guide flow shown on top of system screens.
 *
 * @param title  Large heading shown at the top of the bubble.
 * @param body   One-line body copy telling the user exactly what to tap.
 * @param advanceKeywords  When the accessibility service reports any of these
 *                         strings as visible on-screen (case-insensitive
 *                         contains match) the step is considered complete and
 *                         the flow advances to the next step.
 * @param finalStep        If true, closing this step dismisses the overlay.
 */
data class GuideStep(
    val title: String,
    val body: String,
    val advanceKeywords: List<String> = emptyList(),
    val finalStep: Boolean = false
)

data class GuideFlow(
    val id: String,
    val steps: List<GuideStep>
)
