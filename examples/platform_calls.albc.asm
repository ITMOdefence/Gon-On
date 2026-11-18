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
  19: String "operations"
  20: String "main"
  21: String "Gon&On"
  22: String "Текст"
  23: String "Фото"
  24: Int32 1
  25: Int32 2
  26: String "map.ppm"
  27: Int32 20
  28: Int32 10
  29: Int32 9000
  30: String "127.0.0.1"
  31: String "hello"
  32: String "http://127.0.0.1/photo"
  33: String "group.txt"
  34: String "saved"
  35: Int32 42
  36: String "42"
  37: String ";"
  38: String "a;b"
  39: Int32 0

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

.events state=65535

.function operations(params=4, locals=4)
  0000: LOAD_CONST 21
  0003: OPEN_WINDOW
  0004: POP
  0005: LOAD_CONST 22
  0008: LOAD_LOCAL 0
  0010: SET_TEXT
  0011: POP
  0012: LOAD_CONST 23
  0015: LOAD_CONST 24
  0018: LOAD_LOCAL 0
  0020: ADD_BUTTON
  0021: POP
  0022: LOAD_CONST 25
  0025: LOAD_LOCAL 0
  0027: ADD_TEXT_INPUT
  0028: POP
  0029: LOAD_CONST 25
  0032: LOAD_LOCAL 0
  0034: GET_TEXT
  0035: POP
  0036: LOAD_CONST 26
  0039: LOAD_LOCAL 0
  0041: DRAW_IMAGE
  0042: POP
  0043: LOAD_CONST 27
  0046: LOAD_CONST 28
  0049: LOAD_LOCAL 0
  0051: DRAW_MARKER
  0052: POP
  0053: LOAD_CONST 29
  0056: LISTEN
  0057: POP
  0058: LOAD_CONST 29
  0061: LOAD_CONST 30
  0064: CONNECT
  0065: POP
  0066: LOAD_CONST 31
  0069: LOAD_LOCAL 2
  0071: SEND_TEXT
  0072: POP
  0073: LOAD_LOCAL 2
  0075: CLOSE_CONNECTION
  0076: POP
  0077: LOAD_LOCAL 1
  0079: CLOSE_LISTENER
  0080: POP
  0081: CAPTURE_PHOTO
  0082: POP
  0083: LOAD_LOCAL 3
  0085: LOAD_CONST 32
  0088: UPLOAD_PHOTO
  0089: POP
  0090: GET_DEVICE_LOCATION
  0091: POP
  0092: LOAD_LOCAL 3
  0094: RELEASE_PHOTO
  0095: POP
  0096: LOAD_CONST 33
  0099: READ_TEXT
  0100: POP
  0101: LOAD_CONST 34
  0104: LOAD_CONST 33
  0107: WRITE_TEXT
  0108: POP
  0109: LOAD_CONST 35
  0112: FORMAT_INT
  0113: POP
  0114: LOAD_CONST 36
  0117: PARSE_INT
  0118: POP
  0119: LOAD_CONST 24
  0122: LOAD_CONST 37
  0125: LOAD_CONST 38
  0128: GET_TEXT_FIELD
  0129: POP
  0130: RETURN_VOID

.function main(params=0, locals=0)  ; entry point
  0000: LOAD_CONST 39
  0003: RETURN

