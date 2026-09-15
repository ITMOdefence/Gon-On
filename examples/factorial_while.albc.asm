; AppLang bytecode disassembly
; version 1.0

.constants
  0: Int32 1
  1: String "factorial"
  2: Int32 5
  3: Int32 0
  4: String "main"

.function factorial(params=1, locals=3)
  0000: LOAD_CONST 0
  0003: STORE_LOCAL 1
  0005: LOAD_CONST 0
  0008: STORE_LOCAL 2
  0010: LOAD_LOCAL 2
  0012: LOAD_LOCAL 0
  0014: CMP_LE
  0015: JUMP_IF_FALSE 0040
  0020: LOAD_LOCAL 1
  0022: LOAD_LOCAL 2
  0024: MUL
  0025: STORE_LOCAL 1
  0027: LOAD_LOCAL 2
  0029: LOAD_CONST 0
  0032: ADD
  0033: STORE_LOCAL 2
  0035: JUMP 0010
  0040: LOAD_LOCAL 1
  0042: RETURN

.function main(params=0, locals=0)  ; entry point
  0000: LOAD_CONST 2
  0003: CALL 0
  0006: PRINTLN
  0007: LOAD_CONST 3
  0010: RETURN

