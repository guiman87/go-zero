package com.guiman87.gozero

enum class WakePhrase(
    val displayName: String,
    val firstWord: String,
    val secondWord: String,
    val description: String
) {
    GO_ZERO(
        displayName = "Go Zero",
        firstWord = "go",
        secondWord = "zero",
        description = "Tested & working — natural to say"
    ),
    ZERO_GO(
        displayName = "Zero Go",
        firstWord = "zero",
        secondWord = "go",
        description = "Recommended — rarely spoken naturally, low false positives"
    ),
    ZERO_ZERO(
        displayName = "Zero Zero",
        firstWord = "zero",
        secondWord = "zero",
        description = "Double confirmation — Z-sound is very distinct"
    );

    companion object {
        fun fromName(name: String): WakePhrase =
            entries.firstOrNull { it.name == name } ?: GO_ZERO
    }
}
