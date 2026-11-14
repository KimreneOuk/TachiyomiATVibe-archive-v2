package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference

internal class ReadingModePreference(private val delegate: Preference<Int>) : Preference<Int> by delegate {
    override fun get(): Int = normalize(delegate.get())

    override fun set(value: Int) = delegate.set(normalize(value))

    override fun defaultValue(): Int = ReadingMode.LEFT_TO_RIGHT.flagValue

    override fun changes(): Flow<Int> = delegate.changes().map(::normalize).distinctUntilChanged()

    override fun stateIn(scope: CoroutineScope): StateFlow<Int> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())

    private fun normalize(value: Int): Int = ReadingMode.defaultFromPreference(value).flagValue
}
