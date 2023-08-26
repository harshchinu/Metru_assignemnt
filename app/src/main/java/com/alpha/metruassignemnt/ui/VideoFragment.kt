package com.alpha.metruassignemnt.ui

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import com.alpha.metruassignemnt.R
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.alpha.metruassignemnt.databinding.FragmentVideoBinding
import com.alpha.metruassignemnt.ui.utils.REQUIRED_PERMISSIONS
import com.alpha.metruassignemnt.ui.utils.allPermissionsGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoFragment : Fragment() {

    companion object {
        fun newInstance() = VideoFragment()

        private const val TAG = "Metru_Assignment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val START_COUNTDOWN = 5
        private val VIDEO_RECORDING_LIMIT = 30
    }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                onPermissionGranted()
            } else {
                view?.let { v ->
                    Toast.makeText(
                        requireContext(),
                        "Permission request denied",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    private fun onPermissionGranted() {
        startCamera()
        videModel.startCountDown(START_COUNTDOWN)
    }

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val videModel: VideoViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.durationCountDown.text = VIDEO_RECORDING_LIMIT.toString()
        if (allPermissionsGranted(requireContext(), REQUIRED_PERMISSIONS.toList()).not()) {
            permissionRequest.launch(REQUIRED_PERMISSIONS)
        } else {
            observer()
            onPermissionGranted()
            listener()
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
    }

    private fun listener() {
        binding.videoCaptureButton.setOnClickListener { captureVideo() }
        binding.playVideoButton.setOnClickListener {
            videModel.playRecordedVideo()
        }
    }

    private fun observer() {
        videModel.eventBus.observe(viewLifecycleOwner) {
            when (it) {
                VideoViewEvent.StartVideoRecording -> {
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.startCountDown.visibility = View.GONE
                        captureVideo()
                    }
                }

                is VideoViewEvent.StartingCountDown -> {
                    lifecycleScope.launch(Dispatchers.Main) {
                        binding.startCountDown.text = it.delay.toString()
                    }
                }

                VideoViewEvent.StopVideoRecording -> {
                    if (recording != null) {
                        captureVideo()
                    }
                }

                is VideoViewEvent.VideoRecordingCountDown -> {
                    binding.durationCountDown.text = it.delay.toString()
                }

                is VideoViewEvent.PlayRecordedView -> {
                    val fragment = VideoViewFragment.newInstance(it.uri)
                    parentFragmentManager.beginTransaction()
                        .replace(
                            R.id.container,
                            fragment
                        )
                        .commit()
                }
            }
        }
    }

    private fun captureVideo() {
        // Check if the VideoCapture use case has been created: if not, do nothing.
        val videoCapture = this.videoCapture ?: return
        binding.videoCaptureButton.isEnabled = false
        // If there is an active recording in progress, stop it and release the current recording.
        // We will be notified when the captured video file is ready to be used by our application.
        val curRecording = recording
        if (curRecording != null) {
            videModel.stopRecording()
            curRecording.stop()
            recording = null
            return
        }
        // To start recording, we create a new recording session.
        // First we create our intended MediaStore video content object,
        // with system timestamp as the display name(so we could capture multiple videos).
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireActivity().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .apply {
                // Enable Audio for recording
                if (
                    PermissionChecker.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.RECORD_AUDIO
                    ) ==
                    PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                        videModel.stopVideoRecording(VIDEO_RECORDING_LIMIT)
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            videModel.storeRecordedView(recordEvent.outputResults.outputUri)
                            lifecycleScope.launch(Dispatchers.Main) {
                                binding.playVideoButton.isEnabled = true
                            }
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        binding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.videoPreviewView.surfaceProvider)
                }

            // Video
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.LOWEST,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.LOWEST)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}