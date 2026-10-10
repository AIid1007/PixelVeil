package com.pixelveil.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pixelveil.app.data.ExportItem
import com.pixelveil.app.data.ExportsRepository
import com.pixelveil.app.engine.Mode
import com.pixelveil.app.engine.PixelSettings
import com.pixelveil.app.engine.PixelateEffect
import com.pixelveil.app.engine.SettingsHolder
import com.pixelveil.app.engine.Target
import com.pixelveil.app.engine.Voice
import com.pixelveil.app.export.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(
    repo: ExportsRepository,
    refreshKey: Int,
    message: String?,
    onPick: () -> Unit,
    onOpen: (ExportItem) -> Unit,
    onDeleted: () -> Unit,
) {
    val items by produceState(initialValue = emptyList<ExportItem>(), refreshKey) {
        value = withContext(Dispatchers.IO) { repo.list() }
    }
    var confirmDelete by remember { mutableStateOf<ExportItem?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("PixelVeil", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Turn faces and people into moving pixels.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Pick a video", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = MaterialThemeError, fontSize = 13.sp)
        }

        Spacer(Modifier.height(28.dp))
        Text("My exports", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text("Your pixelated videos will appear here.", color = Muted, fontSize = 14.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.uri.toString() }) { item ->
                ExportRow(repo, item, onClick = { onOpen(item) }, onDelete = { confirmDelete = item })
            }
        }
        Text(
            "Works offline. Nothing leaves your phone.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 12.dp).align(Alignment.CenterHorizontally),
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this export?") },
            text = { Text("${item.name} will be removed from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(item.uri)
                    confirmDelete = null
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private val MaterialThemeError = Color(0xFFFF6B6B)

@Composable
private fun ExportRow(repo: ExportsRepository, item: ExportItem, onClick: () -> Unit, onDelete: () -> Unit) {
    val thumb by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = withContext(Dispatchers.IO) { repo.thumbnail(item.uri) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
            thumb?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
            Text(
                DateUtils.getRelativeTimeSpanString(item.dateAddedSec * 1000).toString() +
                    " · " + DateUtils.formatElapsedTime(item.durationMs / 1000),
                color = Muted,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onDelete) { Text("Delete", color = Muted) }
    }
}

// ---------------------------------------------------------------- Editor

@OptIn(UnstableApi::class)
@Composable
fun EditorScreen(
    uri: Uri,
    info: VideoInfo,
    initial: PixelSettings,
    onBack: () -> Unit,
    onExport: (PixelSettings) -> Unit,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(initial) }
    val holder = remember { SettingsHolder(initial) }
    var showNoPersonHint by remember { mutableStateOf(false) }
    var usingFallback by remember { mutableStateOf(false) }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setVideoEffects(listOf(PixelateEffect(holder, info.width, info.height, info.rotation, preview = true)))
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(settings) {
        holder.value = settings
        player.playbackParameters = PlaybackParameters(1f, settings.voice.pitch)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val s = holder.value
            val needsPerson = s.target == Target.HEAD || s.target == Target.PERSON || s.target == Target.CLOTHING
            val analysed = holder.lastAnalysisMs
            showNoPersonHint = needsPerson && analysed > 0 && analysed - holder.lastPersonSeenMs > 3000
            usingFallback = holder.usingFallbackDetector
        }
    }

    Column(Modifier.fillMaxSize().background(Background).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Back", color = Color.White) }
            Spacer(Modifier.weight(1f))
            Text("Pixel", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { player.pause(); onExport(settings) },
                shape = RoundedCornerShape(10.dp),
            ) { Text("Export", fontWeight = FontWeight.SemiBold) }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxSize(),
            )
            if (showNoPersonHint) {
                Text(
                    "No person found yet. Try Full frame.",
                    color = Color.Black,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Accent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (usingFallback) {
                Text(
                    "Basic detector in use: only faces are found.",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Label("Mode")
            ChipRow {
                Mode.values().forEach { m ->
                    Chip(m.label, settings.mode == m) {
                        val amount = if (m == Mode.PRIVACY) {
                            settings.amount.coerceAtLeast(PixelSettings.PRIVACY_MIN_AMOUNT)
                        } else settings.amount
                        settings = settings.copy(mode = m, amount = amount)
                    }
                }
            }
            Text(
                if (settings.mode == Mode.PRIVACY) {
                    "Coarser pixels, extra margin, and the whole frame covers if tracking is lost."
                } else {
                    "Free pixel size for a stylised look. Not meant for hiding identity."
                },
                color = Muted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(14.dp))
            Label("Pixelate")
            ChipRow {
                Target.values().forEach { t ->
                    Chip(t.label, settings.target == t) { settings = settings.copy(target = t) }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Amount")
                Spacer(Modifier.weight(1f))
                Text("${(settings.amount * 100).roundToInt()}%", color = Color.White, fontSize = 14.sp)
            }
            val minAmount = if (settings.mode == Mode.PRIVACY) PixelSettings.PRIVACY_MIN_AMOUNT else 0f
            Slider(
                value = settings.amount,
                onValueChange = { settings = settings.copy(amount = it.coerceIn(minAmount, 1f)) },
                valueRange = minAmount..1f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent),
            )
            if (settings.mode == Mode.PRIVACY) {
                Text(
                    "Privacy mode keeps pixels coarse enough to hide detail.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(
                onClick = { settings = settings.copy(amount = PixelSettings.DEFAULT_AMOUNT) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Reset to default", color = Color.White) }

            Spacer(Modifier.height(14.dp))
            Label("Voice")
            ChipRow {
                Voice.values().forEach { v ->
                    Chip(v.label, settings.voice == v) { settings = settings.copy(voice = v) }
                }
            }
            Text(
                "Pitch change makes a voice harder to recognise, but does not anonymise it.",
                color = Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
            labelColor = Color.White,
        ),
    )
}

// ---------------------------------------------------------------- Exporting

@Composable
fun ExportingScreen(progress: Int, onCancel: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(
        Modifier.fillMaxSize().background(Background).systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pixelating your video…", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        if (progress >= 0) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Accent,
            )
            Spacer(Modifier.height(8.dp))
            Text("$progress%", color = Muted)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = Accent)
        }
        Spacer(Modifier.height(8.dp))
        Text("Keep the app open. Everything happens on your phone.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(10.dp)) { Text("Cancel", color = Color.White) }
    }
}

// ---------------------------------------------------------------- Done

@Composable
fun DoneScreen(
    repo: ExportsRepository,
    output: Uri,
    original: Uri?,
    onShare: (String?) -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(output) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(output))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var confirmOriginal by remember { mutableStateOf(false) }
    var originalNote by remember { mutableStateOf<String?>(null) }
    val hasInstagram = remember { repo.isInstalled(ExportsRepository.INSTAGRAM) }
    val hasYouTube = remember { repo.isInstalled(ExportsRepository.YOUTUBE) }

    Column(Modifier.fillMaxSize().background(Background).systemBarsPadding().padding(16.dp)) {
        Text("Saved to Movies/PixelVeil", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (hasInstagram) {
                ShareButton("Instagram", Modifier.weight(1f)) { player.pause(); onShare(ExportsRepository.INSTAGRAM) }
            }
            if (hasYouTube) {
                ShareButton("YouTube", Modifier.weight(1f)) { player.pause(); onShare(ExportsRepository.YOUTUBE) }
            }
            ShareButton("More / Edits", Modifier.weight(1f)) { player.pause(); onShare(null) }
        }
        Spacer(Modifier.height(8.dp))
        if (original != null) {
            OutlinedButton(
                onClick = { confirmOriginal = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Delete original", color = Color.White) }
        }
        originalNote?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        TextButton(onClick = onHome, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Back to home", color = Muted)
        }
    }

    if (confirmOriginal && original != null) {
        AlertDialog(
            onDismissRequest = { confirmOriginal = false },
            title = { Text("Delete the original video?") },
            text = { Text("The unpixelated original will be removed from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOriginal = false
                    originalNote = if (repo.deleteOriginal(original)) {
                        "Original deleted."
                    } else {
                        "Android didn't allow deleting this file here. Delete it from your gallery instead."
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmOriginal = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShareButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
    ) { Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}
