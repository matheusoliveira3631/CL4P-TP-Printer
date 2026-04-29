package com.matheusoliveira.cl4ptpprinter.printer

class PrintFormatter {

    fun formatBody(text: String): String {
        val contentWidth = PrintLayoutConfig.MAX_LINE_WIDTH - PrintLayoutConfig.LEFT_PADDING_SPACES
        val padding = " ".repeat(PrintLayoutConfig.LEFT_PADDING_SPACES)
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val formattedLines = mutableListOf<String>()

        for (paragraph in paragraphs) {
            if (paragraph.isBlank()) {
                formattedLines += ""
                continue
            }

            val normalizedParagraph = paragraph.trim().replace(WHITESPACE_REGEX, " ")
            val wrappedLines = wrapParagraph(normalizedParagraph, contentWidth)
            for (line in wrappedLines) {
                formattedLines += padding + line
            }
        }

        return formattedLines.joinToString(separator = "\n").trimEnd()
    }

    private fun wrapParagraph(paragraph: String, contentWidth: Int): List<String> {
        val words = paragraph.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine = word
                continue
            }

            val candidate = "$currentLine $word"
            if (candidate.length <= contentWidth) {
                currentLine = candidate
            } else {
                lines += currentLine
                currentLine = word
            }
        }

        if (currentLine.isNotEmpty()) {
            lines += currentLine
        }

        return lines
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
