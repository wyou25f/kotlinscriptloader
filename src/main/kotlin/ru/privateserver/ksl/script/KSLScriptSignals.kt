package ru.privateserver.ksl

/** Deliberate control-flow signal raised by a script's error()/fatal() helpers. */
sealed class KSLScriptSignal(message: String, val level: String) : RuntimeException(message)

class KSLScriptError(message: String) : KSLScriptSignal(message, "error")

class KSLFatalScriptError(message: String) : KSLScriptSignal(message, "fatal")
