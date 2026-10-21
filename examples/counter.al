// Эталонная программа контракта .albc 2.0 (docs/backlog/bytecode-contract.md §11):
// создать Counter с полем value = 7, вызвать get и вернуть 7.
// Компилируется ровно в 185 байт эталона.

class Counter {
    val value: Int;

    constructor(value: Int) {
        this.value = value;
    }

    fun get(): Int {
        return this.value;
    }
}

fun main(): Int {
    val counter = Counter(7);
    return counter.get();
}
