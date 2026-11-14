package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test

class ReaderSettingsNavigationTest {
    @Test
    fun `each settings entry opens its requested pane and can be dismissed`() {
        val state = MutableStateFlow(ReaderViewModel.State(menuVisible = true))
        val viewModel = mockk<ReaderViewModel>()
        every { viewModel.mutableState } returns state
        val navigation = ReaderNavigationController(viewModel)

        ReaderSettingsDestination.entries.forEach { destination ->
            navigation.openReaderSettings(destination)
            state.value.dialog shouldBe ReaderViewModel.Dialog.ReaderSettings(destination)
            state.value.menuVisible shouldBe true
            navigation.closeDialog()
            state.value.dialog shouldBe null
        }
    }
}
