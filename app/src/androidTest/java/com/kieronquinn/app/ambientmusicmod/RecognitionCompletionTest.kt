package com.kieronquinn.app.ambientmusicmod

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kieronquinn.app.ambientmusicmod.components.navigation.RootNavigationImpl
import com.kieronquinn.app.ambientmusicmod.repositories.*
import com.kieronquinn.app.ambientmusicmod.repositories.RecognitionRepository.RecognitionState
import com.kieronquinn.app.ambientmusicmod.ui.screens.recognition.RecognitionViewModel
import com.kieronquinn.app.ambientmusicmod.ui.screens.recognition.RecognitionViewModelImpl
import com.kieronquinn.app.pixelambientmusic.model.*
import kotlinx.coroutines.flow.asFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Exercises the actual ViewModel collector on Android's main dispatcher. */
@RunWith(AndroidJUnit4::class)
class RecognitionCompletionTest {
    private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            call(method.name)
        } as T

    private fun complete(source: RecognitionSource, events: List<RecognitionState>): RecognitionViewModel.State? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var viewModel: RecognitionViewModelImpl
        instrumentation.runOnMainSync {
            viewModel = RecognitionViewModelImpl(
                proxy<RecognitionRepository> { name ->
                    when(name) {
                        "requestRecognition", "requestOnDemandRecognition" -> events.asFlow()
                        else -> error("Unexpected repository call: $name")
                    }
                },
                RootNavigationImpl(),
                proxy<WidgetRepository> { name ->
                    check(name == "notifyRecognitionState")
                    null
                },
                proxy<RemoteSettingsRepository> { error("Unexpected remote settings call: $it") },
                instrumentation.targetContext
            )
            viewModel.onStateChanged(RecognitionViewModel.State.StartRecognising(0, source))
            viewModel.runRecognition(source)
        }
        instrumentation.waitForIdleSync()
        return viewModel.state.value
    }

    @Test fun skippedRecordingShowsFailureInsteadOfRemainingInRecording() {
        val source = RecognitionSource.NNFP
        val result = complete(source, listOf(
            RecognitionState.Recording(source),
            RecognitionState.Failed(RecognitionFailure(RecognitionFailureReason.NoMatch, source, null))
        ))
        assertTrue(result is RecognitionViewModel.State.RecognisingIcon)
        result as RecognitionViewModel.State.RecognisingIcon
        assertEquals(R.id.recording_to_recognising_icon, result.transitionId)
        assertTrue(result.result is RecognitionViewModel.RecogniseResult.Failed)
    }

    @Test fun immediateOnDemandFailureShowsFailureWithoutAStartCallback() {
        val source = RecognitionSource.ON_DEMAND
        val result = complete(source, listOf(
            RecognitionState.Failed(RecognitionFailure(RecognitionFailureReason.NoMatch, source, null))
        ))
        assertTrue(result is RecognitionViewModel.State.RecognisingIcon)
        assertEquals(R.id.loading_to_recognising_icon,
            (result as RecognitionViewModel.State.RecognisingIcon).transitionId)
    }

    @Test fun timeoutAfterRecordingShowsError() {
        val source = RecognitionSource.NNFP
        val result = complete(source, listOf(
            RecognitionState.Recording(source),
            RecognitionState.Error(RecognitionState.ErrorReason.TIMEOUT)
        ))
        assertTrue(result is RecognitionViewModel.State.RecognisingIcon)
        assertTrue((result as RecognitionViewModel.State.RecognisingIcon).result
            is RecognitionViewModel.RecogniseResult.Error)
    }

    @Test fun joiningAnAlreadyRecognisingServiceKeepsTheCollectorAlive() {
        val source = RecognitionSource.NNFP
        val result = complete(source, listOf(
            RecognitionState.Recognising(source),
            RecognitionState.Failed(RecognitionFailure(RecognitionFailureReason.NoMatch, source, null))
        ))
        assertTrue(result is RecognitionViewModel.State.RecognisingIcon)
        assertTrue((result as RecognitionViewModel.State.RecognisingIcon).result
            is RecognitionViewModel.RecogniseResult.Failed)
    }

    @Test fun repeatedOnDemandProgressDoesNotCancelTheTimeout() {
        val source = RecognitionSource.ON_DEMAND
        val result = complete(source, listOf(
            RecognitionState.Recognising(source),
            RecognitionState.Recognising(source),
            RecognitionState.Error(RecognitionState.ErrorReason.ON_DEMAND_TIMEOUT)
        ))
        assertTrue(result is RecognitionViewModel.State.RecognisingIcon)
        assertTrue((result as RecognitionViewModel.State.RecognisingIcon).result
            is RecognitionViewModel.RecogniseResult.Error)
    }
}
