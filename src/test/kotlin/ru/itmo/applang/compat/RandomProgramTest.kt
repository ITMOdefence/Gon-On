package ru.itmo.applang.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import ru.itmo.applang.bytecode.BytecodeReader
import ru.itmo.applang.bytecode.Disassembler
import ru.itmo.applang.compiler.CompilationResult
import ru.itmo.applang.compiler.Compiler

private const val PROGRAMS = 300

/**
 * Property-тест всего пайплайна на случайных корректных программах ([ProgramGenerator]):
 * компиляция успешна (значит, сгенерированный байткод прошёл верификатор по правилам GVM),
 * записанные байты читаются строгим reader'ом ровно в ту же модель, а модельная машина исполняет
 * программу без ошибок времени выполнения. Ловит ошибки генератора кода, которые GVM отвергла бы
 * при загрузке: неверные переходы, дисбаланс стека, затёртые временные слоты.
 */
class RandomProgramTest {

    @Test
    @DisplayName("Случайные корректные программы компилируются, проходят проверку, round-trip и исполнение")
    fun `random well-typed programs survive the whole pipeline`() {
        var objectPrograms = 0
        for (seed in 0 until PROGRAMS) {
            // Arrange
            val source = ProgramGenerator(seed).generate()

            // Act
            val result = Compiler.compile(source, "seed-$seed.al")

            // Assert
            if (result !is CompilationResult.Success) {
                fail("seed $seed: программа генератора не скомпилировалась: ${result.diagnostics}\n$source")
            }
            val fromBytes = BytecodeReader.readFromBytes(result.bytes)
            assertEquals(result.module, fromBytes, "seed $seed: байты читаются в другую модель")
            assertEquals(Disassembler.disassemble(result.module), Disassembler.disassemble(fromBytes))
            try {
                ReferenceInterpreter(fromBytes).run()
            } catch (e: ExecutionFault) {
                fail("seed $seed: ошибка исполнения ${e.message}\n$source\n${Disassembler.disassemble(fromBytes)}")
            }
            if (fromBytes.isObjectProfile) objectPrograms++
        }
        // Генератор должен покрывать оба профиля, иначе тест незаметно ослабнет.
        check(objectPrograms in (PROGRAMS / 5)..(PROGRAMS * 4 / 5)) { "программ с классами: $objectPrograms из $PROGRAMS" }
    }
}
