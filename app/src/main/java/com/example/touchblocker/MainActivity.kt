package com.example.touchblocker

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.touchblocker.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ZoneRepository
    private lateinit var adapter: ZoneAdapter

    // false = browsing the saved-zone list, true = actively drawing a new zone
    private var isDrawingMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ZoneRepository(this)

        setupZoneList()
        setupControls()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        refreshZoneList()
        updateBlockingButtons()
    }

    private fun setupZoneList() {
        adapter = ZoneAdapter(
            zones = repository.getZones(),
            onToggle = { zone, enabled ->
                zone.enabled = enabled
                // The adapter's list holds the mutated Zone objects directly,
                // so save that list rather than re-reading (stale) storage.
                repository.saveZones(adapter.currentZones())
                persistAndRefreshOverlay(alreadySaved = true)
            },
            onDelete = { zone ->
                repository.removeZone(zone.id)
                refreshZoneList()
                persistAndRefreshOverlay(alreadySaved = true)
            }
        )
        binding.zoneList.layoutManager = LinearLayoutManager(this)
        binding.zoneList.adapter = adapter
        binding.zoneList.visibility = View.VISIBLE
        binding.zoneEditorView.visibility = View.GONE
    }

    private fun setupControls() {
        binding.zoneEditorView.onZoneDrawn = { rect -> onNewZoneDrawn(rect) }

        binding.toggleModeButton.setOnClickListener {
            isDrawingMode = !isDrawingMode
            updateModeUi()
        }

        binding.toggleBlockingButton.setOnClickListener {
            if (repository.isBlockingEnabled()) {
                OverlayService.stop(this)
                repository.setBlockingEnabled(false)
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                    return@setOnClickListener
                }
                repository.setBlockingEnabled(true)
                repository.setPaused(false)
                OverlayService.start(this)
            }
            updateBlockingButtons()
        }

        binding.togglePauseButton.setOnClickListener {
            // The service owns the actual write (it also has to redraw overlays
            // and refresh the notification), so we only ask it to flip state here.
            // We update the button label optimistically using the state we know
            // *before* asking — startForegroundService returns before the service
            // finishes handling the intent, so reading repository right after the
            // call could still show the old value.
            val willBePaused = !repository.isPaused()
            OverlayService.togglePause(this)
            binding.togglePauseButton.text =
                getString(if (willBePaused) R.string.resume_blocking else R.string.pause_blocking)
        }

        binding.permissionBanner.setOnClickListener { requestOverlayPermission() }

        binding.debugVisibilitySwitch.isChecked = repository.isDebugVisibleZones()
        binding.debugVisibilitySwitch.setOnCheckedChangeListener { _, checked ->
            repository.setDebugVisibleZones(checked)
            // Only a running service actually has overlay views on screen to redraw;
            // if blocking isn't active, the new setting just takes effect next start.
            if (repository.isBlockingEnabled()) {
                OverlayService.refresh(this)
            }
        }
    }

    /** Keeps the start/stop and pause/resume buttons in sync with actual service state. */
    private fun updateBlockingButtons() {
        val isBlocking = repository.isBlockingEnabled()
        val isPaused = repository.isPaused()

        binding.toggleBlockingButton.text =
            getString(if (isBlocking) R.string.stop_blocking else R.string.start_blocking)

        binding.togglePauseButton.isEnabled = isBlocking
        binding.togglePauseButton.text =
            getString(if (isPaused) R.string.resume_blocking else R.string.pause_blocking)
    }

    private fun updateModeUi() {
        binding.zoneEditorView.visibility = if (isDrawingMode) View.VISIBLE else View.GONE
        binding.zoneList.visibility = if (isDrawingMode) View.GONE else View.VISIBLE
        (binding.toggleModeButton as MaterialButton).text =
            if (isDrawingMode) "Cancel" else getString(R.string.add_zone)
    }

    private fun onNewZoneDrawn(rect: Rect) {
        val orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Zone.Orientation.LANDSCAPE
        } else {
            Zone.Orientation.PORTRAIT
        }

        val zone = Zone(
            x = rect.left,
            y = rect.top,
            width = rect.width(),
            height = rect.height(),
            orientation = orientation,
            label = "Zone ${repository.getZones().size + 1}"
        )
        repository.addZone(zone)
        binding.zoneEditorView.reset()

        isDrawingMode = false
        updateModeUi()
        refreshZoneList()
        persistAndRefreshOverlay(alreadySaved = true)
    }

    private fun refreshZoneList() {
        adapter.updateZones(repository.getZones())
    }

    /** Saves current zone list state (unless already saved) and tells a running service to redraw. */
    private fun persistAndRefreshOverlay(alreadySaved: Boolean = false) {
        if (!alreadySaved) {
            repository.saveZones(repository.getZones())
        }
        if (repository.isBlockingEnabled()) {
            OverlayService.refresh(this)
        }
    }

    private fun refreshPermissionBanner() {
        val hasPermission = Settings.canDrawOverlays(this)
        binding.permissionBanner.visibility = if (hasPermission) View.GONE else View.VISIBLE
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}
