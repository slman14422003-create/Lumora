package com.lumora.flashlight

import androidx.annotation.StringRes

enum class LightMode(@StringRes val label: Int) {
    STEADY(R.string.light_mode_steady),
    STROBE(R.string.light_mode_strobe),
    SOS(R.string.light_mode_sos)
}

data class Step(val on: Boolean, val millis: Long)

object LightPatterns {
    const val DOT = 200L
    const val DASH = 600L
    const val SYMBOL_GAP = 200L
    const val LETTER_GAP = 600L
    const val WORD_GAP = 1400L
    const val STROBE_HALF_PERIOD = 125L

    fun steps(mode: LightMode): List<Step> = when (mode) {
        LightMode.STEADY -> listOf(Step(true, 1000L))
        LightMode.STROBE -> listOf(Step(true, STROBE_HALF_PERIOD), Step(false, STROBE_HALF_PERIOD))
        LightMode.SOS -> sos()
    }

    private fun sos(): List<Step> {
        val letters = listOf(
            listOf(DOT, DOT, DOT),
            listOf(DASH, DASH, DASH),
            listOf(DOT, DOT, DOT)
        )
        val steps = ArrayList<Step>()
        letters.forEachIndexed { letterIndex, letter ->
            letter.forEachIndexed { symbolIndex, length ->
                steps.add(Step(true, length))
                val gap = when {
                    symbolIndex < letter.lastIndex -> SYMBOL_GAP
                    letterIndex == letters.lastIndex -> WORD_GAP
                    else -> LETTER_GAP
                }
                steps.add(Step(false, gap))
            }
        }
        return steps
    }
}
