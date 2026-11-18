class AppState {
    val window: WindowRef;
    var clicks: Int = 0;
    constructor(window: WindowRef) { this.window = window; }
}

fun main() {
    val opened = openWindow("Gon&On");
    if (opened.ok) {
        val state = AppState(opened.value);
        bindAppState(state);
        addTextInput(state.window, 1);
        addButton(state.window, 2, "Показать текст");
        val listener = listen(9000);
        if (!listener.ok) { setText(state.window, listener.errorMessage); }
    } else {
        println(opened.errorMessage);
    }
}

fun onButtonClick(state: AppState, buttonId: Int) {
    if (buttonId == 2) {
        state.clicks = state.clicks + 1;
        val text = getText(state.window, 1);
        if (text.ok) {
            setText(state.window, formatInt(state.clicks) + ": " + text.value);
        } else { setText(state.window, text.errorMessage); }
    }
}

fun onConnectionAccepted(state: AppState, listener: ListenerRef, connection: ConnectionRef) {
    sendText(connection, "ready");
}

fun onMessageReceived(state: AppState, connection: ConnectionRef, text: String) {
    setText(state.window, text);
}

fun onConnectionClosed(state: AppState, connection: ConnectionRef, reason: Int) {
    setText(state.window, "Соединение закрыто: " + formatInt(reason));
}
