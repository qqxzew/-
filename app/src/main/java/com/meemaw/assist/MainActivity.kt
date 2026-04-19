package com.meemaw.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.meemaw.assist.databinding.ActivityMainBinding
import com.meemaw.assist.showme.PendingShowMeCapture
import com.meemaw.assist.showme.ShowMeImageStore
import com.meemaw.assist.ui.ChatAdapter
import com.meemaw.assist.ui.ChatHistoryAdapter
import com.meemaw.assist.ui.ChatHistoryEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_PENDING_FILE_PATH = "state_pending_file_path"
        private const val STATE_PENDING_URI = "state_pending_uri"
        private const val STATE_ATTACHED_FILE_PATH = "state_attached_file_path"
        private const val STATE_ATTACHED_URI = "state_attached_uri"
        private const val MIN_CAPTURE_BYTES = 1024L
        private const val CAPTURE_ATTACH_RETRY_COUNT = 8
        private const val CAPTURE_ATTACH_RETRY_DELAY_MS = 250L
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val chatAdapter = ChatAdapter()
    private val historyAdapter = ChatHistoryAdapter { entry ->
        clearAttachedPhoto()
        binding.etMessage.text?.clear()
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        viewModel.openChat(entry.sessionId)
    }
    private var pendingShowMeCapture: PendingShowMeCapture? = null
    private var attachedShowMeCapture: PendingShowMeCapture? = null

    // ── Runtime permission launchers ──

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSpeechRecognizer() else {
            Toast.makeText(this, "Microphone permission is needed for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Bluetooth permission is needed to manage Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    private val contactsPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.READ_CONTACTS] == true) {
            viewModel.preloadContacts(this)
        } else if (grants.containsKey(Manifest.permission.READ_CONTACTS)) {
            Toast.makeText(this, "Contacts permission is needed to call and text people by name", Toast.LENGTH_SHORT).show()
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                binding.etMessage.setText(text)
                binding.etMessage.setSelection(text.length)
                if (attachedShowMeCapture == null) {
                    sendCurrentInput()
                }
            }
        }
    }

    private val showMeCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capture = pendingShowMeCapture
        if (capture == null) {
            return@registerForActivityResult
        }

        Log.d(
            TAG,
            "TakePicture result success=$success path=${capture.filePath} exists=${File(capture.filePath).exists()} size=${File(capture.filePath).length()}"
        )

        if (success || captureHasImage(capture)) {
            pendingShowMeCapture = null
            attachCapturedPhoto(capture)
        } else {
            waitForCaptureAttachment(capture)
        }
    }

    // Gallery fallback — users (and tests) can supply an existing photo
    // instead of taking one with the camera. Triggered by long-pressing
    // the Show-me button.
    private val showMeGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            Log.d(TAG, "Gallery picker returned null")
            return@registerForActivityResult
        }
        try {
            val capture = ShowMeImageStore.createCaptureTarget(this)
            val target = File(capture.filePath)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Null input stream for picked uri" }
                target.outputStream().use { out -> input.copyTo(out) }
            }
            Log.d(TAG, "Gallery pick saved to ${capture.filePath} size=${target.length()}")
            pendingShowMeCapture = null
            attachCapturedPhoto(capture)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import picked image", e)
            Toast.makeText(this, "Couldn't load that picture", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupHistoryDrawer()
        viewModel.restoreChatHistory(this)
        restoreCaptureState(savedInstanceState)
        setupListeners()
        observeState()
        requestStartupPermissions()
        viewModel.preloadInstalledApps(this)
        viewModel.preloadContacts(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCapture(outState, pendingShowMeCapture, STATE_PENDING_FILE_PATH, STATE_PENDING_URI)
        saveCapture(outState, attachedShowMeCapture, STATE_ATTACHED_FILE_PATH, STATE_ATTACHED_URI)
    }

    override fun onResume() {
        super.onResume()

        val pendingCapture = pendingShowMeCapture
        if (attachedShowMeCapture == null && pendingCapture != null && captureHasImage(pendingCapture)) {
            Log.d(TAG, "Attaching pending capture from onResume path=${pendingCapture.filePath}")
            pendingShowMeCapture = null
            attachCapturedPhoto(pendingCapture)
        }
    }

    private fun setupRecycler() {
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupHistoryDrawer() {
        binding.recyclerHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        binding.toolbar.setNavigationOnClickListener {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        binding.btnNewChat.setOnClickListener {
            clearAttachedPhoto()
            binding.etMessage.text?.clear()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            viewModel.startNewChat()
        }
    }

    private fun setupListeners() {
        binding.btnSend.setOnClickListener {
            sendCurrentInput()
        }

        binding.btnShowMe.setOnClickListener {
            launchShowMeCapture()
        }
        binding.btnShowMe.setOnLongClickListener {
            showMeGalleryLauncher.launch("image/*")
            true
        }

        binding.btnRemovePhotoAttachment.setOnClickListener {
            clearAttachedPhoto()
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (canSendCurrentInput()) {
                    sendCurrentInput()
                    true
                } else {
                    false
                }
            } else false
        }

        binding.btnMic.setOnClickListener { startVoiceInput() }
    }

    private fun sendCurrentInput() {
        val text = binding.etMessage.text?.toString()?.trim().orEmpty()
        val photoAttachment = attachedShowMeCapture

        if (photoAttachment != null) {
            attachedShowMeCapture = null
            updatePhotoAttachmentPreview(null)
            binding.etMessage.text?.clear()
            hideKeyboard()
            viewModel.onShowMePhoto(Uri.parse(photoAttachment.uriString), text, this)
            return
        }

        if (text.isBlank()) {
            return
        }

        binding.etMessage.text?.clear()
        hideKeyboard()
        viewModel.onUserMessage(text, this)
    }

    private fun canSendCurrentInput(): Boolean {
        return attachedShowMeCapture != null || !binding.etMessage.text?.toString().isNullOrBlank()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }

    private fun startVoiceInput() {
        // Check RECORD_AUDIO runtime permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchSpeechRecognizer()
    }

    private fun launchShowMeCapture() {
        val capture = ShowMeImageStore.createCaptureTarget(this)
        pendingShowMeCapture = capture
        Log.d(TAG, "Launching show-me capture path=${capture.filePath} uri=${capture.uriString}")
        showMeCaptureLauncher.launch(Uri.parse(capture.uriString))
    }

    private fun waitForCaptureAttachment(capture: PendingShowMeCapture) {
        lifecycleScope.launch {
            repeat(CAPTURE_ATTACH_RETRY_COUNT) { attempt ->
                delay(CAPTURE_ATTACH_RETRY_DELAY_MS)

                if (pendingShowMeCapture?.filePath != capture.filePath || attachedShowMeCapture != null) {
                    return@launch
                }

                if (captureHasImage(capture)) {
                    Log.d(
                        TAG,
                        "Capture file became available on retry=${attempt + 1} path=${capture.filePath} size=${File(capture.filePath).length()}"
                    )
                    pendingShowMeCapture = null
                    attachCapturedPhoto(capture)
                    return@launch
                }
            }

            if (pendingShowMeCapture?.filePath == capture.filePath && attachedShowMeCapture == null) {
                Log.d(TAG, "Capture file never became available, deleting path=${capture.filePath}")
                pendingShowMeCapture = null
                deleteCaptureFile(capture)
            }
        }
    }

    private fun attachCapturedPhoto(capture: PendingShowMeCapture) {
        attachedShowMeCapture?.let { existing ->
            if (existing.filePath != capture.filePath) {
                deleteCaptureFile(existing)
            }
        }
        attachedShowMeCapture = capture
        updatePhotoAttachmentPreview(capture)
        binding.etMessage.requestFocus()
        binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
    }

    private fun clearAttachedPhoto() {
        attachedShowMeCapture?.let { deleteCaptureFile(it) }
        attachedShowMeCapture = null
        updatePhotoAttachmentPreview(null)
    }

    private fun updatePhotoAttachmentPreview(capture: PendingShowMeCapture?) {
        if (capture == null) {
            binding.cardPhotoAttachment.visibility = View.GONE
            binding.viewPhotoAttachmentDivider.visibility = View.GONE
            binding.ivPhotoAttachment.setImageDrawable(null)
            binding.tvPhotoAttachment.text = ""
            binding.etMessage.hint = getString(R.string.hint_type_message)
            return
        }

        binding.cardPhotoAttachment.visibility = View.VISIBLE
        binding.viewPhotoAttachmentDivider.visibility = View.VISIBLE
        val bitmap = BitmapFactory.decodeFile(capture.filePath)
        if (bitmap != null) {
            binding.ivPhotoAttachment.setImageBitmap(bitmap)
        } else {
            binding.ivPhotoAttachment.setImageURI(Uri.parse(capture.uriString))
        }
        binding.tvPhotoAttachment.text = getString(R.string.photo_attachment_ready)
        binding.etMessage.hint = getString(R.string.hint_photo_description)
    }

    private fun deleteCaptureFile(capture: PendingShowMeCapture) {
        runCatching {
            File(capture.filePath).delete()
        }
    }

    private fun captureHasImage(capture: PendingShowMeCapture): Boolean {
        val file = File(capture.filePath)
        return file.exists() && file.length() > MIN_CAPTURE_BYTES
    }

    private fun saveCapture(
        bundle: Bundle,
        capture: PendingShowMeCapture?,
        fileKey: String,
        uriKey: String
    ) {
        if (capture == null) {
            return
        }

        bundle.putString(fileKey, capture.filePath)
        bundle.putString(uriKey, capture.uriString)
    }

    private fun restoreCaptureState(savedInstanceState: Bundle?) {
        pendingShowMeCapture = savedInstanceState.readCapture(STATE_PENDING_FILE_PATH, STATE_PENDING_URI)
        attachedShowMeCapture = savedInstanceState.readCapture(STATE_ATTACHED_FILE_PATH, STATE_ATTACHED_URI)
        updatePhotoAttachmentPreview(attachedShowMeCapture)
    }

    private fun Bundle?.readCapture(fileKey: String, uriKey: String): PendingShowMeCapture? {
        val filePath = this?.getString(fileKey)?.takeIf { it.isNotBlank() } ?: return null
        val uriString = this.getString(uriKey)?.takeIf { it.isNotBlank() } ?: return null
        if (!File(filePath).exists()) {
            return null
        }
        return PendingShowMeCapture(filePath = filePath, uriString = uriString)
    }

    private fun launchSpeechRecognizer() {
        // Use the phone's current locale so users can speak in any European language
        // configured on their device (EN, RU, SK, DE, FR, ES, IT, PL, …).
        val deviceLocale = java.util.Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, deviceLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, deviceLocale)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now…")
        }
        // Check if a speech recognizer exists on this device
        if (intent.resolveActivity(packageManager) != null) {
            speechLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Speech recognition is not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStartupPermissions() {
        // Request SYSTEM_ALERT_WINDOW so we can show the floating step-by-step
        // guide on top of Settings screens.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {
                // ignore — user can grant later
            }
        }

        // Request BLUETOOTH_CONNECT on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        val contactPermissionsToRequest = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_CONTACTS)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.CALL_PHONE)
            }
        }

        if (contactPermissionsToRequest.isNotEmpty()) {
            contactsPermissionsLauncher.launch(contactPermissionsToRequest.toTypedArray())
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        chatAdapter.submitList(messages) {
                            if (messages.isNotEmpty()) {
                                binding.recyclerChat.post {
                                    binding.recyclerChat.smoothScrollToPosition(messages.size - 1)
                                }
                            }
                        }
                    }
                }
                launch {
                    viewModel.chatHistoryEntries.collect { entries ->
                        historyAdapter.submitList(entries)
                        binding.tvHistoryEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.status.collect { status ->
                        if (status != null) {
                            binding.tvStatus.text = status
                            binding.tvStatus.visibility = android.view.View.VISIBLE
                        } else {
                            binding.tvStatus.visibility = android.view.View.GONE
                        }
                    }
                }
            }
        }
    }
}
