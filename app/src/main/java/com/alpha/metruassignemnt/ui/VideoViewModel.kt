package com.alpha.metruassignemnt.ui

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VideoViewModel : ViewModel() {


    private val _eventBus: MutableLiveData<VideoViewEvent> = MutableLiveData()
    val eventBus: LiveData<VideoViewEvent> = _eventBus
    private var isRecordingStop = false

    private var storeUri: Uri? = null

    fun startCountDown(delay: Int) {
        var startDelayDuration = delay
        viewModelScope.launch(Dispatchers.IO) {
            while (startDelayDuration > 0) {
                _eventBus.postValue(VideoViewEvent.StartingCountDown(delay = startDelayDuration))
                delay(1000)
                startDelayDuration--
            }
            isRecordingStop = false
            _eventBus.postValue(VideoViewEvent.StartVideoRecording)

        }
    }

    fun stopVideoRecording(delay: Int) {
        var startDelayDuration = delay
        viewModelScope.launch(Dispatchers.IO) {
            while (startDelayDuration >= 0 && isRecordingStop.not()) {
                _eventBus.postValue(VideoViewEvent.VideoRecordingCountDown(delay = startDelayDuration))
                delay(1000)
                startDelayDuration--
            }
            _eventBus.postValue(VideoViewEvent.StopVideoRecording)
        }
    }

    fun playRecordedVideo() {
        storeUri?.let {
            _eventBus.postValue(VideoViewEvent.PlayRecordedView(uri = it))
        }
    }

    fun storeRecordedView(outputUri: Uri) {
        storeUri = outputUri
    }

    fun stopRecording() {
        isRecordingStop = true
        _eventBus.postValue(VideoViewEvent.StopVideoRecording)
    }
}

sealed class VideoViewEvent() {
    data class StartingCountDown(val delay: Int) : VideoViewEvent()
    data class VideoRecordingCountDown(val delay: Int) : VideoViewEvent()
    object StartVideoRecording : VideoViewEvent()
    object StopVideoRecording : VideoViewEvent()
    data class PlayRecordedView(val uri: Uri) : VideoViewEvent()
}