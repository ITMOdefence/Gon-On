package ru.itmo.applang.bytecode

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Контракт строковых констант libstr PUNos, который проверяет загрузчик GVM: корректный UTF-8
 * по RFC 3629, без байта NUL внутри текста, не длиннее [FormatLimits.MAX_STRING_BYTES] байт.
 * Пустая строка допустима; терминатор в файл не пишется.
 *
 * Кодирование строгое: некорректная пара суррогатов Kotlin-строки — это отказ, а не тихая замена
 * на `?` или U+FFFD, как у `String.toByteArray()`. Один и тот же контракт применяется к литералам,
 * именам функций/классов/полей и к модулям, созданным программно в обход парсера (BASE-03).
 */
object StringConstraints {

    /** Причина, по которой строка не может быть константой, или null, если она допустима. */
    fun violation(value: String): String? = try {
        encode(value)
        null
    } catch (e: BytecodeFormatException) {
        e.message
    }

    /** Кодирует строку в UTF-8 для записи в файл; при нарушении контракта бросает [BytecodeFormatException]. */
    fun encode(value: String): ByteArray {
        val nulIndex = value.indexOf('\u0000')
        if (nulIndex >= 0) {
            throw BytecodeFormatException(BytecodeIssue.STRING, "строка содержит символ NUL (позиция $nulIndex)")
        }
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try {
            encoder.encode(CharBuffer.wrap(value))
        } catch (e: CharacterCodingException) {
            throw BytecodeFormatException(
                BytecodeIssue.STRING,
                "строка не представима в UTF-8 (некорректная пара суррогатов UTF-16)",
            )
        }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        checkLength(bytes.size.toLong())
        return bytes
    }

    /** Строго декодирует байты константы из файла: некорректный UTF-8, NUL и превышение длины — отказ. */
    fun decode(bytes: ByteArray): String {
        checkLength(bytes.size.toLong())
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw BytecodeFormatException(BytecodeIssue.STRING, "строковая константа не является корректным UTF-8")
        }
        if ('\u0000' in text) {
            throw BytecodeFormatException(BytecodeIssue.STRING, "строковая константа содержит байт NUL")
        }
        return text
    }

    /** Проверяет длину в байтах UTF-8; вызывается до выделения памяти под строку при чтении. */
    fun checkLength(byteLength: Long) {
        if (byteLength > FormatLimits.MAX_STRING_BYTES) {
            throw BytecodeFormatException(
                BytecodeIssue.STRING,
                "длина строки $byteLength байт UTF-8 превышает предел libstr ${FormatLimits.MAX_STRING_BYTES} байт",
            )
        }
    }
}
