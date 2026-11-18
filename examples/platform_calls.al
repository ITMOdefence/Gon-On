// Набор для проверки загрузки и дизассемблирования всех 21 вызовов.
// main не вызывает operations: приложение не обращается к устройствам.
fun operations(window: WindowRef, listener: ListenerRef, connection: ConnectionRef, photo: PhotoRef) {
    openWindow("Gon&On");
    setText(window, "Текст");
    addButton(window, 1, "Фото");
    addTextInput(window, 2);
    getText(window, 2);
    drawImage(window, "map.ppm");
    drawMarker(window, 10, 20);
    listen(9000);
    connect("127.0.0.1", 9000);
    sendText(connection, "hello");
    closeConnection(connection);
    closeListener(listener);
    capturePhoto();
    uploadPhoto("http://127.0.0.1/photo", photo);
    getDeviceLocation();
    releasePhoto(photo);
    readText("group.txt");
    writeText("group.txt", "saved");
    formatInt(42);
    parseInt("42");
    getField("a;b", ";", 1);
}

fun main(): Int { return 0; }
