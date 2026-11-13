package eu.kanade.translation.util

/** Errors that must never be swallowed by rendering, engine, or pipeline fallbacks. */
internal fun Throwable.isFatal(): Boolean = this is VirtualMachineError || this is ThreadDeath
