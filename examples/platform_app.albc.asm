; AppLang bytecode disassembly
; version 3.0

.constants
  0: String "WindowRef"
  1: String "ListenerRef"
  2: String "ConnectionRef"
  3: String "PhotoRef"
  4: String "Location"
  5: String "latitudeE6"
  6: String "longitudeE6"
  7: String "Status"
  8: String "ok"
  9: String "errorCode"
  10: String "errorMessage"
  11: String "WindowResult"
  12: String "value"
  13: String "ListenerResult"
  14: String "ConnectionResult"
  15: String "PhotoResult"
  16: String "LocationResult"
  17: String "TextResult"
  18: String "IntResult"
  19: String "AppState"
  20: String "window"
  21: String "clicks"
  22: String "AppState.init"
  23: String "main"
  24: String "onButtonClick"
  25: String "onConnectionAccepted"
  26: String "onMessageReceived"
  27: String "onConnectionClosed"
  28: Int32 0
  29: String "Gon&On"
  30: Int32 1
  31: String "Показать текст"
  32: Int32 2
  33: Int32 9000
  34: String ": "
  35: String "ready"
  36: String "Соединение закрыто: "

.class WindowRef  ; class 0

.class ListenerRef  ; class 1

.class ConnectionRef  ; class 2

.class PhotoRef  ; class 3

.class Location  ; class 4
  field 0: latitudeE6 Int
  field 1: longitudeE6 Int

.class Status  ; class 5
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String

.class WindowResult  ; class 6
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String
  field 3: value WindowRef

.class ListenerResult  ; class 7
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String
  field 3: value ListenerRef

.class ConnectionResult  ; class 8
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String
  field 3: value ConnectionRef

.class PhotoResult  ; class 9
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String
  field 3: value PhotoRef

.class LocationResult  ; class 10
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String
  field 3: value Location

.class TextResult  ; class 11
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String
  field 3: value String

.class IntResult  ; class 12
  field 0: ok Bool
  field 1: errorCode Int
  field 2: errorMessage String
  field 3: value Int

.class AppState  ; class 13
  field 0: window WindowRef
  field 1: clicks Int

.events state=13
  event 1: function 2
  event 2: function 3
  event 3: function 4
  event 4: function 5

.function AppState.init(params=2, locals=2)
  0000: LOAD_LOCAL 0
  0002: LOAD_CONST 28
  0005: SET_FIELD 13 1  ; AppState.clicks
  0010: LOAD_LOCAL 0
  0012: LOAD_LOCAL 1
  0014: SET_FIELD 13 0  ; AppState.window
  0019: RETURN_VOID

.function main(params=0, locals=4)  ; entry point
  0000: LOAD_CONST 29
  0003: OPEN_WINDOW
  0004: STORE_LOCAL 0
  0006: LOAD_LOCAL 0
  0008: GET_FIELD 6 0  ; WindowResult.ok
  0013: JUMP_IF_FALSE 0109
  0018: LOAD_LOCAL 0
  0020: GET_FIELD 6 3  ; WindowResult.value
  0025: ALLOC_OBJECT 13  ; AppState
  0028: STORE_LOCAL 3
  0030: LOAD_LOCAL 3
  0032: CALL 0
  0035: LOAD_LOCAL 3
  0037: STORE_LOCAL 1
  0039: LOAD_LOCAL 1
  0041: BIND_APP_STATE
  0042: LOAD_CONST 30
  0045: LOAD_LOCAL 1
  0047: GET_FIELD 13 0  ; AppState.window
  0052: ADD_TEXT_INPUT
  0053: POP
  0054: LOAD_CONST 31
  0057: LOAD_CONST 32
  0060: LOAD_LOCAL 1
  0062: GET_FIELD 13 0  ; AppState.window
  0067: ADD_BUTTON
  0068: POP
  0069: LOAD_CONST 33
  0072: LISTEN
  0073: STORE_LOCAL 2
  0075: LOAD_LOCAL 2
  0077: GET_FIELD 7 0  ; ListenerResult.ok
  0082: NOT
  0083: JUMP_IF_FALSE 0104
  0088: LOAD_LOCAL 2
  0090: GET_FIELD 7 2  ; ListenerResult.errorMessage
  0095: LOAD_LOCAL 1
  0097: GET_FIELD 13 0  ; AppState.window
  0102: SET_TEXT
  0103: POP
  0104: JUMP 0117
  0109: LOAD_LOCAL 0
  0111: GET_FIELD 6 2  ; WindowResult.errorMessage
  0116: PRINTLN
  0117: RETURN_VOID

.function onButtonClick(params=2, locals=3)
  0000: LOAD_LOCAL 1
  0002: LOAD_CONST 32
  0005: CMP_EQ
  0006: JUMP_IF_FALSE 0104
  0011: LOAD_LOCAL 0
  0013: LOAD_LOCAL 0
  0015: GET_FIELD 13 1  ; AppState.clicks
  0020: LOAD_CONST 30
  0023: ADD
  0024: SET_FIELD 13 1  ; AppState.clicks
  0029: LOAD_CONST 30
  0032: LOAD_LOCAL 0
  0034: GET_FIELD 13 0  ; AppState.window
  0039: GET_TEXT
  0040: STORE_LOCAL 2
  0042: LOAD_LOCAL 2
  0044: GET_FIELD 11 0  ; TextResult.ok
  0049: JUMP_IF_FALSE 0088
  0054: LOAD_LOCAL 0
  0056: GET_FIELD 13 1  ; AppState.clicks
  0061: FORMAT_INT
  0062: LOAD_CONST 34
  0065: CONCAT
  0066: LOAD_LOCAL 2
  0068: GET_FIELD 11 3  ; TextResult.value
  0073: CONCAT
  0074: LOAD_LOCAL 0
  0076: GET_FIELD 13 0  ; AppState.window
  0081: SET_TEXT
  0082: POP
  0083: JUMP 0104
  0088: LOAD_LOCAL 2
  0090: GET_FIELD 11 2  ; TextResult.errorMessage
  0095: LOAD_LOCAL 0
  0097: GET_FIELD 13 0  ; AppState.window
  0102: SET_TEXT
  0103: POP
  0104: RETURN_VOID

.function onConnectionAccepted(params=3, locals=3)
  0000: LOAD_CONST 35
  0003: LOAD_LOCAL 2
  0005: SEND_TEXT
  0006: POP
  0007: RETURN_VOID

.function onMessageReceived(params=3, locals=3)
  0000: LOAD_LOCAL 2
  0002: LOAD_LOCAL 0
  0004: GET_FIELD 13 0  ; AppState.window
  0009: SET_TEXT
  0010: POP
  0011: RETURN_VOID

.function onConnectionClosed(params=3, locals=3)
  0000: LOAD_CONST 36
  0003: LOAD_LOCAL 2
  0005: FORMAT_INT
  0006: CONCAT
  0007: LOAD_LOCAL 0
  0009: GET_FIELD 13 0  ; AppState.window
  0014: SET_TEXT
  0015: POP
  0016: RETURN_VOID

