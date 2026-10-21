; AppLang bytecode disassembly
; version 2.0

.constants
  0: String "Point"
  1: String "x"
  2: String "y"
  3: String "Segment"
  4: String "start"
  5: String "end"
  6: String "Account"
  7: String "owner"
  8: String "balance"
  9: String "operations"
  10: String "Point.init"
  11: String "Point.shift"
  12: String "Segment.init"
  13: String "Segment.lengthSquared"
  14: String "Account.init"
  15: String "Account.deposit"
  16: String "origin"
  17: String "main"
  18: Int32 0
  19: Int32 1
  20: Int32 4
  21: Int32 3
  22: Int32 10
  23: String "Ann"
  24: Int32 5
  25: Int32 20

.class Point  ; class 0
  field 0: x Int
  field 1: y Int
  method 1: Point.shift

.class Segment  ; class 1
  field 0: start Point
  field 1: end Point
  method 3: Segment.lengthSquared

.class Account  ; class 2
  field 0: owner String
  field 1: balance Int
  field 2: operations Int
  method 5: Account.deposit

.function Point.init(params=3, locals=3)
  0000: LOAD_LOCAL 0
  0002: LOAD_LOCAL 1
  0004: SET_FIELD 0 0  ; Point.x
  0009: LOAD_LOCAL 0
  0011: LOAD_LOCAL 2
  0013: SET_FIELD 0 1  ; Point.y
  0018: RETURN_VOID

.function Point.shift(params=3, locals=3)
  0000: LOAD_LOCAL 0
  0002: LOAD_LOCAL 0
  0004: GET_FIELD 0 0  ; Point.x
  0009: LOAD_LOCAL 1
  0011: ADD
  0012: SET_FIELD 0 0  ; Point.x
  0017: LOAD_LOCAL 0
  0019: LOAD_LOCAL 0
  0021: GET_FIELD 0 1  ; Point.y
  0026: LOAD_LOCAL 2
  0028: ADD
  0029: SET_FIELD 0 1  ; Point.y
  0034: RETURN_VOID

.function Segment.init(params=3, locals=3)
  0000: LOAD_LOCAL 0
  0002: LOAD_LOCAL 1
  0004: SET_FIELD 1 0  ; Segment.start
  0009: LOAD_LOCAL 0
  0011: LOAD_LOCAL 2
  0013: SET_FIELD 1 1  ; Segment.end
  0018: RETURN_VOID

.function Segment.lengthSquared(params=1, locals=3)
  0000: LOAD_LOCAL 0
  0002: GET_FIELD 1 1  ; Segment.end
  0007: GET_FIELD 0 0  ; Point.x
  0012: LOAD_LOCAL 0
  0014: GET_FIELD 1 0  ; Segment.start
  0019: GET_FIELD 0 0  ; Point.x
  0024: SUB
  0025: STORE_LOCAL 1
  0027: LOAD_LOCAL 0
  0029: GET_FIELD 1 1  ; Segment.end
  0034: GET_FIELD 0 1  ; Point.y
  0039: LOAD_LOCAL 0
  0041: GET_FIELD 1 0  ; Segment.start
  0046: GET_FIELD 0 1  ; Point.y
  0051: SUB
  0052: STORE_LOCAL 2
  0054: LOAD_LOCAL 1
  0056: LOAD_LOCAL 1
  0058: MUL
  0059: LOAD_LOCAL 2
  0061: LOAD_LOCAL 2
  0063: MUL
  0064: ADD
  0065: RETURN

.function Account.init(params=3, locals=3)
  0000: LOAD_LOCAL 0
  0002: LOAD_CONST 18
  0005: SET_FIELD 2 1  ; Account.balance
  0010: LOAD_LOCAL 0
  0012: LOAD_CONST 18
  0015: SET_FIELD 2 2  ; Account.operations
  0020: LOAD_LOCAL 0
  0022: LOAD_LOCAL 1
  0024: SET_FIELD 2 0  ; Account.owner
  0029: LOAD_LOCAL 2
  0031: LOAD_CONST 18
  0034: CMP_GT
  0035: JUMP_IF_FALSE 0059
  0040: LOAD_LOCAL 0
  0042: LOAD_LOCAL 2
  0044: SET_FIELD 2 1  ; Account.balance
  0049: LOAD_LOCAL 0
  0051: LOAD_CONST 19
  0054: SET_FIELD 2 2  ; Account.operations
  0059: RETURN_VOID

.function Account.deposit(params=2, locals=2)
  0000: LOAD_LOCAL 0
  0002: LOAD_LOCAL 0
  0004: GET_FIELD 2 1  ; Account.balance
  0009: LOAD_LOCAL 1
  0011: ADD
  0012: SET_FIELD 2 1  ; Account.balance
  0017: LOAD_LOCAL 0
  0019: LOAD_LOCAL 0
  0021: GET_FIELD 2 2  ; Account.operations
  0026: LOAD_CONST 19
  0029: ADD
  0030: SET_FIELD 2 2  ; Account.operations
  0035: LOAD_LOCAL 0
  0037: GET_FIELD 2 1  ; Account.balance
  0042: RETURN

.function origin(params=0, locals=1)
  0000: LOAD_CONST 18
  0003: LOAD_CONST 18
  0006: ALLOC_OBJECT 0  ; Point
  0009: STORE_LOCAL 0
  0011: LOAD_LOCAL 0
  0013: CALL 0
  0016: LOAD_LOCAL 0
  0018: RETURN

.function main(params=0, locals=6)  ; entry point
  0000: CALL 6
  0003: STORE_LOCAL 0
  0005: LOAD_CONST 20
  0008: LOAD_CONST 21
  0011: ALLOC_OBJECT 0  ; Point
  0014: STORE_LOCAL 5
  0016: LOAD_LOCAL 5
  0018: CALL 0
  0021: LOAD_LOCAL 5
  0023: STORE_LOCAL 1
  0025: LOAD_LOCAL 1
  0027: LOAD_LOCAL 0
  0029: ALLOC_OBJECT 1  ; Segment
  0032: STORE_LOCAL 5
  0034: LOAD_LOCAL 5
  0036: CALL 2
  0039: LOAD_LOCAL 5
  0041: STORE_LOCAL 2
  0043: LOAD_LOCAL 2
  0045: STORE_LOCAL 5
  0047: LOAD_LOCAL 5
  0049: CALL 3
  0052: PRINTLN
  0053: LOAD_LOCAL 1
  0055: STORE_LOCAL 3
  0057: LOAD_LOCAL 3
  0059: STORE_LOCAL 5
  0061: LOAD_CONST 20
  0064: LOAD_CONST 21
  0067: LOAD_LOCAL 5
  0069: CALL 1
  0072: LOAD_LOCAL 1
  0074: GET_FIELD 0 0  ; Point.x
  0079: PRINTLN
  0080: LOAD_LOCAL 2
  0082: STORE_LOCAL 5
  0084: LOAD_LOCAL 5
  0086: CALL 3
  0089: PRINTLN
  0090: LOAD_CONST 22
  0093: LOAD_CONST 23
  0096: ALLOC_OBJECT 2  ; Account
  0099: STORE_LOCAL 5
  0101: LOAD_LOCAL 5
  0103: CALL 4
  0106: LOAD_LOCAL 5
  0108: STORE_LOCAL 4
  0110: LOAD_LOCAL 4
  0112: STORE_LOCAL 5
  0114: LOAD_CONST 24
  0117: LOAD_LOCAL 5
  0119: CALL 5
  0122: POP
  0123: LOAD_LOCAL 4
  0125: STORE_LOCAL 5
  0127: LOAD_CONST 25
  0130: LOAD_LOCAL 5
  0132: CALL 5
  0135: PRINTLN
  0136: LOAD_LOCAL 4
  0138: GET_FIELD 2 2  ; Account.operations
  0143: PRINTLN
  0144: LOAD_LOCAL 4
  0146: GET_FIELD 2 0  ; Account.owner
  0151: PRINTLN
  0152: RETURN_VOID

