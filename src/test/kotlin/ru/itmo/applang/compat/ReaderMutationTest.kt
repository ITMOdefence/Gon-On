package ru.itmo.applang.compat

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.fail
import ru.itmo.applang.bytecode.BytecodeFormatException
import ru.itmo.applang.bytecode.BytecodeReader
import ru.itmo.applang.bytecode.BytecodeWriter
import kotlin.random.Random

private const val MUTATIONS_PER_FILE = 3_000

/** Вид порчи: детерминирован по [Random], чтобы любой упавший случай воспроизводился по seed. */
private enum class Mutation {
    FLIP_BIT, RANDOM_BYTE, TRUNCATE, APPEND, DELETE_RANGE, DUPLICATE_RANGE, EXTREME_FIELD;

    fun apply(bytes: ByteArray, random: Random): ByteArray = when (this) {
        FLIP_BIT -> bytes.copyOf().also {
            val i = random.nextInt(it.size)
            it[i] = (it[i].toInt() xor (1 shl random.nextInt(8))).toByte()
        }
        RANDOM_BYTE -> bytes.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
        TRUNCATE -> bytes.copyOf(random.nextInt(bytes.size))
        APPEND -> bytes + random.nextBytes(1 + random.nextInt(8))
        DELETE_RANGE -> {
            val from = random.nextInt(bytes.size)
            val to = minOf(bytes.size, from + 1 + random.nextInt(8))
            bytes.copyOfRange(0, from) + bytes.copyOfRange(to, bytes.size)
        }
        DUPLICATE_RANGE -> {
            val from = random.nextInt(bytes.size)
            val to = minOf(bytes.size, from + 1 + random.nextInt(8))
            bytes.copyOfRange(0, to) + bytes.copyOfRange(from, bytes.size)
        }
        // Поле длины/количества/индекса, выставленное в крайнее значение: 0x00.. или 0xFF..
        EXTREME_FIELD -> bytes.copyOf().also {
            val start = random.nextInt(it.size)
            val value = if (random.nextBoolean()) 0xFF else 0x00
            for (i in start until minOf(it.size, start + 1 + random.nextInt(4))) it[i] = value.toByte()
        }
    }
}

/**
 * Испорченный файл, как и корректный, — обычный вход загрузчика. Строгий reader обязан либо
 * отклонить его [BytecodeFormatException] с категорией, либо принять — и тогда результат обязан
 * быть каноничным: повторная запись даёт ровно те же байты. Любое другое исключение
 * (IndexOutOfBounds, NegativeArraySize, OutOfMemory, ClassCast...) — дефект reader'а.
 */
class ReaderMutationTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["hello_world", "factorial_while", "gcd_demo", "counter", "objects"])
    @DisplayName("Любая порча байтов примера даёт отказ формата или каноничный корректный файл")
    fun `mutated file is rejected cleanly or read canonically`(name: String) {
        // Arrange
        val original = Examples.compile(name).bytes
        val random = Random(name.hashCode())
        var rejected = 0

        repeat(MUTATIONS_PER_FILE) { iteration ->
            val mutation = Mutation.entries[random.nextInt(Mutation.entries.size)]
            val mutated = mutation.apply(original, random)

            // Act
            val module = try {
                BytecodeReader.readFromBytes(mutated)
            } catch (e: BytecodeFormatException) {
                rejected++
                return@repeat
            } catch (e: Throwable) {
                fail("$name, итерация $iteration, $mutation: вместо ошибки формата ${e::class.simpleName}: ${e.message}", e)
            }

            // Assert: принятый файл каноничен
            assertArrayEquals(mutated, BytecodeWriter.writeToBytes(module), "$name, итерация $iteration, $mutation")
        }
        assertTrue(rejected > MUTATIONS_PER_FILE / 2, "подозрительно мало отказов: $rejected из $MUTATIONS_PER_FILE")
    }
}
