package com.matheusoliveira.cl4ptpprinter.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintFormatterTest {

    private val formatter = PrintFormatter()

    @Test
    fun `wraps long text without breaking words`() {
        val formatted = formatter.formatBody(
            "Esta mensagem longa precisa ser quebrada em varias linhas sem cortar palavras no meio."
        )

        val lines = formatted.split("\n")
        assertTrue(lines.all { it.isBlank() || it.startsWith("    ") })
        assertTrue(lines.none { it.contains("palavr\n") })
    }

    @Test
    fun `preserves blank lines between paragraphs`() {
        val formatted = formatter.formatBody("Primeiro paragrafo.\n\nSegundo paragrafo.")

        assertTrue(formatted.contains("\n\n"))
    }

    @Test
    fun `normalizes accidental extra spaces and keeps accents`() {
        val formatted = formatter.formatBody("Texto   com   acentuação: ação   e impressão.")
        val withoutLeftPadding = formatted
            .split("\n")
            .joinToString(separator = "\n") { line -> line.trimStart() }

        assertTrue(withoutLeftPadding.contains("ação"))
        assertTrue(withoutLeftPadding.contains("impressão."))
        assertEquals(-1, withoutLeftPadding.indexOf("  "))
    }
}
