package com.pixelveil.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.pixelveil.app.data.ExportsRepository
import com.pixelveil.app.engine.PixelSettings
import com.pixelveil.app.export.Exporter
import com.pixelveil.app.export.VideoInfo
import com.pixelveil.app.ui.DoneScreen
import com.pixelveil.app.ui.EditorScreen
import com.pixelveil.app.ui.ExportingScreen
import com.pixelveil.app.ui.HomeScreen
import com.pixelveil.app.ui.PixelVeilTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
    data object Home : Screen
    data class Editor(val uri: Uri, val info: VideoInfo, val deletable: Boolean) : Screen
    data class Exporting(val source: Uri, val deletable: Boolean) : Screen
    data class Done(val output: Uri, val original: Uri?) : Screen
}

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var repo: ExportsRepository
    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var homeMessage by mutableStateOf<String?>(null)
    private var refreshKey by mutableIntStateOf(0)
    private var progress by mutableIntStateOf(-1)
    private var lastSettings = PixelSettings()
    private var exporter: Exporter? = null
    private var progressJob: Job? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openEditor(uri, deletable = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ExportsRepository(applicationContext)
        handleShareIntent(intent)

        setContent {
            PixelVeilTheme {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        repo = repo,
                        refreshKey = refreshKey,
                        message = homeMessage,
                        onPick = { homeMessage = null; pickVideo.launch(arrayOf("video/*")) },
                        onOpen = { screen = Screen.Done(it.uri, null) },
                        onDeleted = { refreshKey++ },
                    )

                    is Screen.Editor -> {
                        BackHandler { screen = Screen.Home }
                        EditorScreen(
                            uri = s.uri,
                            info = s.info,
                            initial = lastSettings,
                            onBack = { screen = Screen.Home },
                            onExport = { settings ->
                                lastSettings = settings
                                startExport(s.uri, s.info, s.deletable, settings)
                            },
                        )
                    }

                    is Screen.Exporting -> {
                        BackHandler { cancelExport() }
                        ExportingScreen(progress = progress, onCancel = { cancelExport() })
                    }

                    is Screen.Done -> {
                        BackHandler { goHome() }
                        DoneScreen(
                            repo = repo,
                            output = s.output,
                            original = s.original,
                            onShare = { pkg -> startActivity(repo.shareIntent(s.output, pkg)) },
                            onHome = { goHome() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Videos shared into PixelVeil from the gallery or another app. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        openEditor(uri, deletable = false)
    }

    private fun openEditor(uri: Uri, deletable: Boolean) {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { VideoInfo.read(applicationContext, uri) }
            if (info == null) {
                homeMessage = "That file couldn't be opened as a video."
                screen = Screen.Home
            } else {
                screen = Screen.Editor(uri, info, deletable)
            }
        }
    }

    private fun startExport(source: Uri, info: VideoInfo, deletable: Boolean, settings: PixelSettings) {
        progress = -1
        screen = Screen.Exporting(source, deletable)
        val ex = Exporter(
            context = applicationContext,
            source = source,
            info = info,
            settings = settings,
            onDone = { file ->
                progressJob?.cancel()
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) { repo.saveToGallery(file) }
                    exporter = null
                    if (saved == null) {
                        homeMessage = "Couldn't save the video to your gallery."
                        screen = Screen.Home
                    } else {
                        refreshKey++
                        screen = Screen.Done(saved, if (deletable) source else null)
                    }
                }
            },
            onError = { msg ->
                progressJob?.cancel()
                exporter = null
                homeMessage = "Export failed: $msg"
                screen = Screen.Home
            },
        )
        exporter = ex
        ex.start()
        progressJob = lifecycleScope.launch {
            while (true) {
                progress = ex.progress()
                delay(250)
            }
        }
    }

    private fun cancelExport() {
        progressJob?.cancel()
        exporter?.cancel()
        exporter = null
        goHome()
    }

    private fun goHome() {
        refreshKey++
        screen = Screen.Home
    }
}
