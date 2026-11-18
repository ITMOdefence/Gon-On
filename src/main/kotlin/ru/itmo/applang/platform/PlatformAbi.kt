package ru.itmo.applang.platform

import ru.itmo.applang.ast.*
import ru.itmo.applang.bytecode.*
import ru.itmo.applang.diagnostics.SourcePosition

/** Фиксированная часть ABI 3.0; сигнатуры операций читаются из platform-api.json. */
object PlatformAbi {
    val instructions: Map<String, Instruction> = linkedMapOf(
        "openWindow" to Instruction.OpenWindow,
        "setText" to Instruction.SetText,
        "addButton" to Instruction.AddButton,
        "addTextInput" to Instruction.AddTextInput,
        "getText" to Instruction.GetText,
        "drawImage" to Instruction.DrawImage,
        "drawMarker" to Instruction.DrawMarker,
        "listen" to Instruction.Listen,
        "connect" to Instruction.Connect,
        "sendText" to Instruction.SendText,
        "closeConnection" to Instruction.CloseConnection,
        "closeListener" to Instruction.CloseListener,
        "capturePhoto" to Instruction.CapturePhoto,
        "uploadPhoto" to Instruction.UploadPhoto,
        "getDeviceLocation" to Instruction.GetDeviceLocation,
        "releasePhoto" to Instruction.ReleasePhoto,
        "readText" to Instruction.ReadText,
        "writeText" to Instruction.WriteText,
        "formatInt" to Instruction.FormatInt,
        "parseInt" to Instruction.ParseInt,
        "getField" to Instruction.GetTextField,
    )

    data class BuiltinClass(val name: String, val fields: List<Pair<String, Type>> = emptyList())
    private val common = listOf("ok" to Type.BOOL, "errorCode" to Type.INT, "errorMessage" to Type.STRING)
    val classes = listOf(
        BuiltinClass("WindowRef"), BuiltinClass("ListenerRef"),
        BuiltinClass("ConnectionRef"), BuiltinClass("PhotoRef"),
        BuiltinClass("Location", listOf("latitudeE6" to Type.INT, "longitudeE6" to Type.INT)),
        BuiltinClass("Status", common),
        BuiltinClass("WindowResult", common + ("value" to Type.ClassType("WindowRef"))),
        BuiltinClass("ListenerResult", common + ("value" to Type.ClassType("ListenerRef"))),
        BuiltinClass("ConnectionResult", common + ("value" to Type.ClassType("ConnectionRef"))),
        BuiltinClass("PhotoResult", common + ("value" to Type.ClassType("PhotoRef"))),
        BuiltinClass("LocationResult", common + ("value" to Type.ClassType("Location"))),
        BuiltinClass("TextResult", common + ("value" to Type.STRING)),
        BuiltinClass("IntResult", common + ("value" to Type.INT)),
    )
    val classNames = classes.map { it.name }.toSet()
    val reservedFunctions = instructions.keys + "bindAppState"

    data class Event(val kind: Int, val name: String, val arguments: List<Type>)
    private val state = Type.ClassType("AppState")
    val events = listOf(
        Event(1, "onButtonClick", listOf(state, Type.INT)),
        Event(2, "onConnectionAccepted", listOf(state, Type.ClassType("ListenerRef"), Type.ClassType("ConnectionRef"))),
        Event(3, "onMessageReceived", listOf(state, Type.ClassType("ConnectionRef"), Type.STRING)),
        Event(4, "onConnectionClosed", listOf(state, Type.ClassType("ConnectionRef"), Type.INT)),
    )

    fun type(name: String): Type = when (name) {
        "Int" -> Type.INT
        "Bool" -> Type.BOOL
        "String" -> Type.STRING
        else -> {
            require(name in classNames) { "неизвестный тип каталога: $name" }
            Type.ClassType(name)
        }
    }

    fun classIndex(name: String): Int = classes.indexOfFirst { it.name == name }.also { require(it >= 0) }

    fun fieldType(type: Type): FieldType = when (type) {
        Type.INT -> FieldType.IntType
        Type.BOOL -> FieldType.BoolType
        Type.STRING -> FieldType.StringType
        is Type.ClassType -> FieldType.ObjectType(classIndex(type.name))
        Type.UNIT -> error("Unit не является типом поля")
    }

    /** Синтетические объявления участвуют в разрешении полей, но не порождают init-функций. */
    fun declarations(): List<ClassDecl> = classes.map { cls ->
        val position = SourcePosition(1, 1)
        ClassDecl(cls.name, cls.fields.map { (name, type) ->
            FieldDecl(name, false, type, null, position)
        }, emptyList(), emptyList(), position)
    }

    fun stackArguments(opcode: Opcode): Int? = when (opcode) {
        Opcode.BIND_APP_STATE -> 1
        else -> PlatformCatalog.functions.firstOrNull { it.instruction.opcode == opcode }?.parameters?.size
    }
}
