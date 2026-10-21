// Наибольший общий делитель по алгоритму Евклида.
// absolute() — пример if/else, обе ветки которого возвращают значение (см. docs/backlog/base.md, BASE-01).

fun absolute(x: Int): Int {
    if (x < 0) {
        return -x;
    } else {
        return x;
    }
}

fun gcd(a: Int, b: Int): Int {
    var x: Int = absolute(a);
    var y: Int = absolute(b);
    while (y != 0) {
        val rest: Int = x % y;
        x = y;
        y = rest;
    }
    return x;
}

fun main(): Int {
    println(gcd(48, -36));
    println(gcd(17, 5));
    return 0;
}
