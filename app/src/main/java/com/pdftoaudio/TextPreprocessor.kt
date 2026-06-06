package com.pdftoaudio

object TextPreprocessor {

    fun clean(raw: String): String {
        var text = raw

        // Fix hyphenated line breaks from typesetting (e.g. "appli-\ncation" → "application")
        text = text.replace(Regex("-\n([a-z])")) { it.groupValues[1] }

        // Remove lines that are only a page number
        text = text.replace(Regex("(?m)^[ \t]*\\d{1,4}[ \t]*$"), "")

        // Remove URLs — TTS reads them letter by letter
        text = text.replace(Regex("https?://\\S+"), "")

        // Replace em/en dashes with commas for natural pauses
        text = text.replace("—", ", ").replace("–", ", ")

        // Replace ellipsis character
        text = text.replace("…", "...")

        // Replace non-breaking and other exotic spaces with regular space
        text = text.replace(" ", " ").replace("​", "")

        // Normalize whitespace within lines
        text = text.replace(Regex("[ \t]+"), " ")

        // Trim each line
        text = text.lines().joinToString("\n") { it.trim() }

        // Collapse 3+ consecutive blank lines down to two
        text = text.replace(Regex("\n{3,}"), "\n\n")

        return text.trim()
    }
}
