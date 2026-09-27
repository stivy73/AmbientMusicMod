package com.kieronquinn.app.ambientmusicmod.ui.screens.recognition

import com.kieronquinn.app.ambientmusicmod.R
import com.kieronquinn.app.ambientmusicmod.ui.screens.recognition.RecognitionViewModel.State

/** Terminal callbacks can arrive at any active stage, including before recording starts. */
internal fun State?.recognitionResultTransition(): Int? = when(this) {
    is State.StartRecognising -> R.id.loading_to_recognising_icon
    is State.Recording -> R.id.recording_to_recognising_icon
    is State.Recognising -> R.id.recognising_to_recognising_icon
    else -> null
}
