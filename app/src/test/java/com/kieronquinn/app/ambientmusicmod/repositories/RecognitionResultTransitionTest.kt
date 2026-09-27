package com.kieronquinn.app.ambientmusicmod.repositories

import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.ui.screens.recognition.RecognitionViewModel.State
import com.kieronquinn.app.ambientmusicmod.ui.screens.recognition.recognitionResultTransition
import com.kieronquinn.app.pixelambientmusic.model.RecognitionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionResultTransitionTest {
    @Test fun immediateFailureHasARouteForBothSources() {
        RecognitionSource.values().forEach {
            assertEquals(R.id.loading_to_recognising_icon,
                State.StartRecognising(0, it).recognitionResultTransition())
        }
    }

    @Test fun skippedRecordingHasARouteForBothSources() {
        RecognitionSource.values().forEach {
            assertEquals(R.id.recording_to_recognising_icon,
                State.Recording(0, 0L, it).recognitionResultTransition())
        }
    }

    @Test fun normalRecognitionHasARoute() {
        assertEquals(R.id.recognising_to_recognising_icon,
            State.Recognising(0, RecognitionSource.NNFP).recognitionResultTransition())
    }

    @Test fun lateResultCannotReopenDismissedOrClosingDialog() {
        assertNull(null.recognitionResultTransition())
        assertNull(State.Fab.recognitionResultTransition())
        assertNull(State.Initial(0, true).recognitionResultTransition())
    }
}
