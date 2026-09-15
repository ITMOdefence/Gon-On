; AppLang bytecode disassembly
; version 1.0

.constants
  0: String "Hello, world!"
  1: Int32 0
  2: String "main"

.function main(params=0, locals=0)  ; entry point
  0000: LOAD_CONST 0
  0003: PRINTLN
  0004: LOAD_CONST 1
  0007: RETURN

