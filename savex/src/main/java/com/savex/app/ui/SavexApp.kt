package com.savex.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.savex.app.*
import java.util.Locale

private val Bg = Color(0xFF0B0B0B)
private val Surface = Color(0xFF1C1C1E)
private val Field = Color(0xFF1E1E20)
private val Option = Color(0xFF3A3A3C)
private val Outline = Color(0xFF3A3A3C)
private val Accent = Color(0xFF3B9AE8)
private val TextDim = Color(0xFFB0B0B5)

private enum class Screen { HOME, HISTORY, SETTINGS }

@Composable
fun SavexApp(vm: MainViewModel, onPaste: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface)) {
        var screen by remember { mutableStateOf(Screen.HOME) }
        Surface(Modifier.fillMaxSize(), color = Bg) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm, onPaste, onOpen = { screen = it })
                Screen.HISTORY -> {
                    BackHandler { screen = Screen.HOME }
                    HistoryScreen(vm, onBack = { screen = Screen.HOME }, onOpen = {
                        screen = Screen.HOME
                        vm.openHistory(it)
                    })
                }
                Screen.SETTINGS -> {
                    BackHandler { screen = Screen.HOME }
                    SettingsScreen(vm.settings, onBack = { screen = Screen.HOME })
                }
            }
            if (vm.showSheet && vm.post != null) DownloadSheet(vm)
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, onPaste: () -> Unit, onOpen: (Screen) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Savex",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onOpen(Screen.SETTINGS) }) {
                Icon(Icons.Outlined.Settings, tr("Настройки", "Settings"), tint = Color.White)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Surface)
                .padding(14.dp)
        ) {
            PreviewCard(vm)
            if (vm.historyItems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.historyItems.take(10), key = { it.id }) { HistoryThumb(it, Modifier.size(92.dp)) { vm.openHistory(it) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onOpen(Screen.HISTORY) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Outline),
            ) {
                Icon(Icons.Outlined.History, null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(tr("Просмотреть всю историю", "View full history"), color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))
        UrlRow(vm, onPaste)
        vm.error?.let {
            Text(it, color = Color(0xFFFF6B6B), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
        vm.message?.let {
            Text(it, color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
        vm.progress?.let { p ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f), color = Accent, trackColor = Outline)
                TextButton(onClick = { vm.cancelAudio() }) { Text(tr("Отмена", "Cancel"), color = Accent) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UrlRow(vm: MainViewModel, onPaste: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Field)
                .border(1.dp, Outline, RoundedCornerShape(18.dp))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (vm.input.isEmpty()) Text(tr("Ссылка на пост", "Post link"), color = TextDim, fontSize = 16.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 16.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.fetch() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (vm.input.isNotEmpty()) {
                IconButton(onClick = { vm.input = "" }) { Icon(Icons.Filled.Cancel, null, tint = TextDim) }
            }
            IconButton(onClick = onPaste) { Icon(Icons.Outlined.ContentPaste, null, tint = Color.White) }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.fetch() },
            enabled = !vm.loading && vm.progress == null,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Accent.copy(alpha = .5f)),
        ) {
            if (vm.loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(tr("Скачать", "Download"), fontSize = 17.sp, color = Color.White)
        }
    }
}

@Composable
private fun PreviewCard(vm: MainViewModel) {
    val post = vm.post
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2A2A2C))
            .clickable(enabled = post != null) { vm.showSheet = true }
    ) {
        if (post == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Download, null, tint = TextDim, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(tr("Здесь появится пост", "The post will appear here"), color = TextDim)
            }
            return@Box
        }
        val first = post.media.first()
        AsyncImage(first.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = .7f))
            )
        )
        first.variants.firstOrNull()?.sizeBytes?.let {
            Chip(formatSize(it), Modifier.align(Alignment.TopStart).padding(10.dp))
        }
        TypeBadge(first.type, Modifier.align(Alignment.TopEnd).padding(10.dp))
        if (first.type != MediaType.PHOTO) {
            Box(
                Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
        if (post.media.size > 1) Chip("1/${post.media.size}", Modifier.align(Alignment.TopCenter).padding(10.dp))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(post.avatar, 30.dp)
            Spacer(Modifier.width(8.dp))
            Text(post.authorName, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, post.url)
                context.startActivity(Intent.createChooser(send, null))
            }) { Icon(Icons.Filled.Share, null, tint = Color.White) }
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(post.url)))
            }) { Icon(Icons.Filled.OpenInNew, null, tint = Color.White) }
        }
    }
}

@Composable
private fun HistoryThumb(entry: HistoryEntry, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A2C)).clickable(onClick = onClick)) {
        AsyncImage(entry.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        TypeBadge(entry.type, Modifier.align(Alignment.TopEnd).padding(6.dp), small = true)
    }
}

@Composable
private fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (HistoryEntry) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(tr("История", "History"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (vm.historyItems.isNotEmpty()) {
                IconButton(onClick = { vm.clearHistory() }) { Icon(Icons.Outlined.DeleteOutline, null, tint = Color.White) }
            }
        }
        if (vm.historyItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("Пока пусто", "Nothing yet"), color = TextDim) }
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(3),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.historyItems, key = { it.id }) {
                    HistoryThumb(it, Modifier.aspectRatio(1f)) { onOpen(it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(vm: MainViewModel) {
    val post = vm.post ?: return
    var current by remember(post.id) { mutableIntStateOf(vm.selectable().firstOrNull() ?: 0) }
    val item = post.media[current]
    val audioMode = vm.kind == Kind.AUDIO
    val hasAudio = post.media.any { it.audio.isNotEmpty() }
    ModalBottomSheet(
        onDismissRequest = { vm.showSheet = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(20.dp)) {
            // Options scroll if the screen is short; the download button below always stays visible.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(130.dp).clip(RoundedCornerShape(16.dp))) {
                        AsyncImage(item.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        TypeBadge(item.type, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        if (item.durationMs > 0) {
                            Chip(formatDuration(item.durationMs), Modifier.align(Alignment.BottomEnd).padding(6.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Row(Modifier.weight(1f).padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(post.avatar, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(post.authorName, color = Color.White, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.showSheet = false }) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                }
                if (hasAudio) {
                    Spacer(Modifier.height(16.dp))
                    KindTabs(vm.kind) { k ->
                        vm.switchKind(k)
                        if (current !in vm.selectable()) current = vm.selectable().firstOrNull() ?: 0
                    }
                }
                if (post.media.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(post.media.size) { i ->
                            val enabled = i in vm.selectable()
                            val on = i in vm.selected
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))
                                    .border(2.dp, if (i == current) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable(enabled = enabled) { current = i }
                            ) {
                                AsyncImage(
                                    post.media[i].thumbnail, null, contentScale = ContentScale.Crop,
                                    alpha = if (enabled) 1f else .35f, modifier = Modifier.fillMaxSize()
                                )
                                if (enabled) Icon(
                                    if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                                    tint = if (on) Accent else Color.White,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp)
                                        .clip(CircleShape).background(Color.Black.copy(alpha = .4f))
                                        .clickable { vm.toggle(i) }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Outline)
                Text(tr("Опции загрузки", "Download options"), color = TextDim, fontSize = 17.sp)
                Spacer(Modifier.height(12.dp))
                if (audioMode) {
                    OptionRow(
                        options = item.audio.map { Triple(it.badge, it.label, it.sizeBytes) },
                        chosen = vm.audioQuality[current] ?: 0,
                    ) { vm.audioQuality = vm.audioQuality + (current to it) }
                } else {
                    OptionRow(
                        options = item.variants.map { v -> Triple(if (v.height > 0) (if (v.isHd) "HD" else "SD") else null, v.label, v.sizeBytes) },
                        chosen = vm.quality[current] ?: 0,
                    ) { vm.quality = vm.quality + (current to it) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { vm.download() },
                enabled = vm.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text(tr("Скачать", "Download") + "(${vm.selected.size})", fontSize = 19.sp, color = Color.White) }
        }
    }
}

@Composable
private fun KindTabs(kind: Kind, onSelect: (Kind) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Option).padding(4.dp)) {
        listOf(Kind.VIDEO to tr("Видео", "Video"), Kind.AUDIO to tr("Аудио", "Audio")).forEach { (k, name) ->
            val on = kind == k
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (on) Accent else Color.Transparent)
                    .clickable { onSelect(k) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (k == Kind.AUDIO) Icons.Filled.MusicNote else Icons.Filled.Movie, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(name, color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

/** Quality cards: (badge, label, size) each; wraps to more rows when there are over three. */
@Composable
private fun OptionRow(options: List<Triple<String?, String, Long?>>, chosen: Int, onChoose: (Int) -> Unit) {
    options.chunked(3).forEachIndexed { row, chunk ->
        if (row > 0) Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            chunk.forEachIndexed { col, (badge, label, size) ->
                val index = row * 3 + col
                Column(
                    Modifier
                        .weight(1f)
                        .height(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Option)
                        .border(2.dp, if (chosen == index) Accent else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { onChoose(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (badge != null) {
                            Text(
                                badge, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Accent.copy(alpha = .2f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    }
                    size?.let { Text(formatSize(it), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(tr("Настройки", "Settings"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            SettingGroup(tr("Язык", "Language")) {
                listOf(Lang.SYSTEM to tr("Системный", "System"), Lang.RU to "Русский", Lang.EN to "English").forEach { (v, name) ->
                    ChoiceChip(name, settings.lang == v) { settings.updateLang(v) }
                }
            }
            SettingGroup(tr("Скачивать по умолчанию", "Download by default")) {
                ChoiceChip(tr("Видео", "Video"), settings.defaultKind == Kind.VIDEO) { settings.updateDefaultKind(Kind.VIDEO) }
                ChoiceChip(tr("Аудио", "Audio"), settings.defaultKind == Kind.AUDIO) { settings.updateDefaultKind(Kind.AUDIO) }
            }
            SettingGroup(tr("Качество видео", "Video quality")) {
                Settings.VIDEO_CHOICES.forEach { q ->
                    ChoiceChip(qualityName(q), settings.videoQuality == q) { settings.updateVideoQuality(q) }
                }
            }
            SettingGroup(tr("Качество аудио", "Audio quality")) {
                Settings.AUDIO_CHOICES.forEach { q ->
                    ChoiceChip(qualityName(q), settings.audioQuality == q) { settings.updateAudioQuality(q) }
                }
            }
            Text(
                tr(
                    "Если выбрано качество, загрузка начнётся сразу, без окна выбора. Когда такого качества нет, берётся ближайшее ниже, а если ниже нет — самое низкое из доступных.",
                    "With a quality chosen, downloads start right away without the options window. If that quality is missing, the nearest lower one is used, or the lowest available."
                ),
                color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingGroup(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent else Option)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

private fun qualityName(q: Int): String = when (q) {
    Settings.ASK -> tr("Спрашивать", "Ask")
    Settings.BEST -> tr("Лучшее", "Best")
    Settings.SMALLEST -> tr("Наименьшее", "Smallest")
    else -> "${q}P"
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .5f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun TypeBadge(type: MediaType, modifier: Modifier, small: Boolean = false) {
    val size = if (small) 22.dp else 26.dp
    val box = modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .85f))
    if (type == MediaType.GIF) {
        Text("GIF", color = Color(0xFF888888), fontSize = if (small) 10.sp else 12.sp, fontWeight = FontWeight.Bold,
            modifier = box.padding(horizontal = 4.dp, vertical = 2.dp))
    } else {
        val icon: ImageVector = if (type == MediaType.VIDEO) Icons.Filled.Movie else Icons.Filled.Image
        Box(box.size(size), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color(0xFF333333), modifier = Modifier.size(size - 6.dp))
        }
    }
}

@Composable
private fun Avatar(url: String?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFCFD8DC)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Person, null, tint = Color(0xFF607D8B))
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f %s", bytes / 1_048_576.0, tr("МБ", "MB"))
    else -> "${bytes / 1024} " + tr("КБ", "KB")
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}
