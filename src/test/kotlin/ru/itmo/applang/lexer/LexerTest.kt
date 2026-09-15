package ru.itmo.applang.lexer

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.itmo.applang.parser.AppLangLexer

/** Токенизирует исходный текст и возвращает имена типов токенов (без EOF), для удобства сравнения. */
private fun tokenNames(source: String): List<String> {
    val lexer = AppLangLexer(CharStreams.fromString(source))
    val tokens = CommonTokenStream(lexer)
    tokens.fill()
    return tokens.tokens
        .filter { it.type != Token.EOF }
        .map { lexer.vocabulary.getSymbolicName(it.type) }
}

class LexerTest {

    @ParameterizedTest(name = "ключевое слово ''{0}'' распознаётся как отдельный токен")
    @MethodSource("keywordSamples")
    @DisplayName("Ключевые слова распознаются, а не путаются с обычными идентификаторами")
    fun `keyword is recognized as its own token type`(keyword: String, expectedTokenName: String) {
        // Act
        val names = tokenNames(keyword)

        // Assert
        assertEquals(listOf(expectedTokenName), names)
    }

    companion object {
        @JvmStatic
        fun keywordSamples() = listOf(
            Arguments.of("fun", "FUN"),
            Arguments.of("var", "VAR"),
            Arguments.of("val", "VAL"),
            Arguments.of("if", "IF"),
            Arguments.of("else", "ELSE"),
            Arguments.of("while", "WHILE"),
            Arguments.of("return", "RETURN"),
            Arguments.of("true", "TRUE"),
            Arguments.of("false", "FALSE"),
        )
    }

    @DisplayName("Идентификатор, похожий на ключевое слово только частично, остаётся IDENT")
    @Test
    fun `identifier that merely starts like a keyword is not misclassified`() {
        // Arrange
        val source = "funny"

        // Act
        val names = tokenNames(source)

        // Assert
        assertEquals(listOf("IDENT"), names)
    }

    @DisplayName("Строковый литерал с escape-последовательностями токенизируется целиком одним токеном")
    @Test
    fun `string literal with escape sequences is a single token`() {
        // Arrange
        val source = "\"line1\\nline2 with \\\"quotes\\\"\""

        // Act
        val names = tokenNames(source)

        // Assert
        assertEquals(listOf("STRING_LITERAL"), names)
    }

    @DisplayName("Однострочный и блочный комментарии полностью пропускаются лексером")
    @Test
    fun `comments are skipped and produce no tokens`() {
        // Arrange
        val source = """
            // это комментарий до конца строки
            /* а это
               блочный комментарий */
            42
        """.trimIndent()

        // Act
        val names = tokenNames(source)

        // Assert
        assertEquals(listOf("INT_LITERAL"), names)
    }

    @DisplayName("Позиция токена (строка:колонка) считается от начала файла, 1-based")
    @Test
    fun `token position is reported as 1-based line and column`() {
        // Arrange
        val source = "fun\n  foo"

        // Act
        val lexer = AppLangLexer(CharStreams.fromString(source))
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        val identToken = tokens.tokens.first { it.type != Token.EOF && it.text == "foo" }

        // Assert
        assertEquals(2, identToken.line)
        assertEquals(2, identToken.charPositionInLine) // ANTLR отдаёт 0-based колонку на уровне токена
    }
}
