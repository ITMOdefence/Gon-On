// Классы Gon&On 2.0: поля, конструкторы, методы, поля-объекты и ссылочная семантика.

class Point {
    var x: Int;
    var y: Int;

    constructor(x: Int, y: Int) {
        this.x = x;
        this.y = y;
    }

    fun shift(dx: Int, dy: Int) {
        this.x = this.x + dx;
        this.y = this.y + dy;
    }
}

// Поле другого класса хранит ссылку, а не копию объекта.
class Segment {
    val start: Point;
    val end: Point;

    constructor(start: Point, end: Point) {
        this.start = start;
        this.end = end;
    }

    fun lengthSquared(): Int {
        val dx: Int = this.end.x - this.start.x;
        val dy: Int = this.end.y - this.start.y;
        return dx * dx + dy * dy;
    }
}

// Инициализаторы полей выполняются до тела конструктора; поле заполнено на всех путях.
class Account {
    val owner: String;
    var balance: Int = 0;
    var operations: Int = 0;

    constructor(owner: String, initial: Int) {
        this.owner = owner;
        if (initial > 0) {
            this.balance = initial;
            this.operations = 1;
        }
    }

    fun deposit(amount: Int): Int {
        this.balance = this.balance + amount;
        this.operations = this.operations + 1;
        return this.balance;
    }
}

fun origin(): Point {
    return Point(0, 0);
}

fun main() {
    val a = origin();
    val b = Point(3, 4);
    val segment = Segment(a, b);
    println(segment.lengthSquared());

    // alias и b ссылаются на один объект: изменение через alias видно через b и segment.
    val alias = b;
    alias.shift(3, 4);
    println(b.x);
    println(segment.lengthSquared());

    val account = Account("Ann", 10);
    account.deposit(5);
    println(account.deposit(20));
    println(account.operations);
    println(account.owner);
}
