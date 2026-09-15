fun factorial(n: Int): Int {
    var result: Int = 1;
    var i: Int = 1;
    while (i <= n) {
        result = result * i;
        i = i + 1;
    }
    return result;
}

fun main(): Int {
    println(factorial(5));
    return 0;
}
