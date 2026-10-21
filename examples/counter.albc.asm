; AppLang bytecode disassembly
; version 2.0

.constants
  0: String "Counter"
  1: String "value"
  2: String "Counter.init"
  3: String "Counter.get"
  4: String "main"
  5: Int32 7

.class Counter  ; class 0
  field 0: value Int
  method 1: Counter.get

.function Counter.init(params=2, locals=2)
  0000: LOAD_LOCAL 0
  0002: LOAD_LOCAL 1
  0004: SET_FIELD 0 0  ; Counter.value
  0009: RETURN_VOID

.function Counter.get(params=1, locals=1)
  0000: LOAD_LOCAL 0
  0002: GET_FIELD 0 0  ; Counter.value
  0007: RETURN

.function main(params=0, locals=2)  ; entry point
  0000: LOAD_CONST 5
  0003: ALLOC_OBJECT 0  ; Counter
  0006: STORE_LOCAL 1
  0008: LOAD_LOCAL 1
  0010: CALL 0
  0013: LOAD_LOCAL 1
  0015: STORE_LOCAL 0
  0017: LOAD_LOCAL 0
  0019: STORE_LOCAL 1
  0021: LOAD_LOCAL 1
  0023: CALL 1
  0026: RETURN

