package com.savetube.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.savetube.app.*
import java.io.File
import java.util.Locale

private val Bg = Color(0xFF680007)
private val Surface = Color(0xFF4E0005)
private val Field = Color(0xFF4A0005)
private val Option = Color(0xFF7A1B21)
private val Cream = Color(0xFFFBF0DC)
private val Outline = Cream.copy(alpha = .25f)
private val TextDim = Cream.copy(alpha = .65f)
private val OnCream = Color.Black

private enum class Screen { HOME, HISTORY, LIBRARY, SETTINGS }

@Composable
fun SavetubeApp(vm: MainViewModel, onPaste: () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cream, onPrimary = OnCream, background = Bg, surface = Surface,
            onSurface = Cream, onBackground = Cream, surfaceContainerHigh = Surface,
        )
    ) {
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
                Screen.LIBRARY -> {
                    BackHandler { screen = Screen.HOME }
                    LibraryScreen(vm, onBack = { screen = Screen.HOME })
                }
                Screen.SETTINGS -> {
                    BackHandler { screen = Screen.HOME }
                    SettingsScreen(vm.settings, onBack = { screen = Screen.HOME })
                }
            }
            if (vm.showSheet && vm.track != null) DownloadSheet(vm)
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, onPaste: () -> Unit, onOpen: (Screen) -> Unit) {
    var folderDialog by remember { mutableStateOf(false) }
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
                "Savetube",
                color = Cream,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { folderDialog = true }) {
                Icon(Icons.Outlined.FolderOpen, tr("Папка сохранения", "Save folder"), tint = Cream)
            }
            IconButton(onClick = { onOpen(Screen.LIBRARY) }) {
                Icon(Icons.Outlined.LibraryMusic, tr("Библиотека", "Library"), tint = Cream)
            }
            IconButton(onClick = { onOpen(Screen.SETTINGS) }) {
                Icon(Icons.Outlined.Settings, tr("Настройки", "Settings"), tint = Cream)
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
                    items(vm.historyItems.take(10), key = { it.id }) {
                        HistoryThumb(it, Modifier.width(130.dp).aspectRatio(16f / 10f)) { vm.openHistory(it) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onOpen(Screen.HISTORY) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Outline),
            ) {
                Icon(Icons.Outlined.History, null, tint = Cream)
                Spacer(Modifier.width(10.dp))
                Text(tr("Просмотреть всю историю", "View full history"), color = Cream, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))
        UrlRow(vm, onPaste)
        vm.error?.let {
            Text(it, color = Color(0xFFFFB4A8), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
        vm.message?.let {
            Text(it, color = Cream, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
    if (folderDialog) FolderDialog(vm) { folderDialog = false }
}

@Composable
private fun FolderDialog(vm: MainViewModel, onClose: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Surface,
        title = { Text(tr("Папка сохранения", "Save folder"), color = Cream) },
        text = { Text(vm.folderLabel, color = TextDim) },
        confirmButton = {
            TextButton(onClick = { picker.launch(null) }) { Text(tr("Выбрать", "Choose"), color = Cream) }
        },
        dismissButton = {
            TextButton(onClick = { vm.setFolder(null) }) { Text(tr("По умолчанию", "Default"), color = TextDim) }
        },
    )
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
                if (vm.input.isEmpty()) Text(tr("Ссылка на YouTube", "YouTube link"), color = TextDim, fontSize = 16.sp)
                BasicTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Cream, fontSize = 16.sp),
                    cursorBrush = SolidColor(Cream),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.fetch() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (vm.input.isNotEmpty()) {
                IconButton(onClick = { vm.input = "" }) { Icon(Icons.Filled.Cancel, null, tint = TextDim) }
            }
            IconButton(onClick = onPaste) { Icon(Icons.Outlined.ContentPaste, null, tint = Cream) }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.fetch() },
            enabled = !vm.loading && vm.progress == null,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cream, contentColor = OnCream,
                disabledContainerColor = Cream.copy(alpha = .5f), disabledContentColor = OnCream,
            ),
        ) {
            if (vm.loading || vm.progress != null) CircularProgressIndicator(Modifier.size(22.dp), color = OnCream, strokeWidth = 2.dp)
            else Text(tr("Загрузить", "Download"), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PreviewCard(vm: MainViewModel) {
    val track = vm.track
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(16.dp))
            .background(Field)
            .clickable(enabled = track != null && vm.progress == null) { vm.showSheet = true }
    ) {
        if (track == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Download, null, tint = TextDim, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(tr("Здесь появится видео", "The video will appear here"), color = TextDim)
            }
            return@Box
        }
        AsyncImage(track.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .8f))
            )
        )
        if (track.live != null) Chip("● LIVE", Modifier.align(Alignment.TopStart).padding(10.dp))
        else if (track.durationSec > 0) Chip(formatDuration(track.durationSec), Modifier.align(Alignment.TopStart).padding(10.dp))
        val p = vm.progress
        if (p != null) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = .6f)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${(p * 100).toInt()}%", color = Cream, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.width(160.dp), color = Cream, trackColor = Cream.copy(alpha = .2f))
                TextButton(onClick = { vm.cancelDownload() }) { Text(tr("Отмена", "Cancel"), color = Cream) }
            }
        } else {
            Box(
                Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Download, null, tint = Cream, modifier = Modifier.size(30.dp)) }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Cream, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.uploader, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, track.url)
                context.startActivity(Intent.createChooser(send, null))
            }) { Icon(Icons.Filled.Share, null, tint = Cream) }
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.url)))
            }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Cream) }
        }
    }
}

@Composable
private fun HistoryThumb(entry: HistoryEntry, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Field).clickable(onClick = onClick)) {
        AsyncImage(entry.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Cream) }
        Text(title, color = Cream, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (HistoryEntry) -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar(tr("История", "History"), onBack) {
            if (vm.historyItems.isNotEmpty()) {
                IconButton(onClick = { vm.clearHistory() }) { Icon(Icons.Outlined.DeleteOutline, null, tint = Cream) }
            }
        }
        if (vm.historyItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("Пока пусто", "Nothing yet"), color = TextDim) }
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.historyItems, key = { it.id }) {
                    HistoryThumb(it, Modifier.aspectRatio(16f / 10f)) { onOpen(it) }
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<LibraryItem?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar(tr("Библиотека", "Library"), onBack)
        vm.error?.let { Text(it, color = Color(0xFFFFB4A8), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        if (vm.libraryItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("Скачанная музыка появится здесь", "Downloaded music will appear here"), color = TextDim)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                items(vm.libraryItems, key = { it.id }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val play = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(Uri.parse(item.uri), "audio/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(play) }
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(item.cover, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = Cream, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(item.artist, formatDuration(item.durationSec)).filter { it.isNotBlank() }.joinToString(" · "),
                                color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("audio/mp4")
                                .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Icon(Icons.Filled.Share, tr("Отправить", "Share"), tint = Cream) }
                        IconButton(onClick = { editing = item }) { Icon(Icons.Outlined.Edit, tr("Изменить", "Edit"), tint = Cream) }
                    }
                }
            }
        }
    }
    editing?.let { item -> EditDialog(vm, item) { editing = null } }
}

@Composable
private fun EditDialog(vm: MainViewModel, item: LibraryItem, onClose: () -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    var artist by remember { mutableStateOf(item.artist) }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cover = uri
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        containerColor = Surface,
        title = { Text(tr("Изменить трек", "Edit track"), color = Cream) },
        text = {
            Column {
                Box(
                    Modifier.size(120.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp))
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                ) {
                    if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Cover(item.cover, 120.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(6.dp).size(30.dp).clip(CircleShape).background(Cream),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Image, null, tint = OnCream, modifier = Modifier.size(18.dp)) }
                }
                Spacer(Modifier.height(14.dp))
                EditField(tr("Название", "Title"), title) { title = it }
                Spacer(Modifier.height(10.dp))
                EditField(tr("Исполнитель / композитор", "Artist / composer"), artist) { artist = it }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFFFB4A8))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Удалить файл", "Delete file"), color = Color(0xFFFFB4A8))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saving = true
                    vm.editTrack(item, title.trim(), artist.trim(), cover) { onClose() }
                },
                enabled = !saving && title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = OnCream),
            ) { Text(if (saving) tr("Сохранение…", "Saving…") else tr("Сохранить", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !saving) { Text(tr("Отмена", "Cancel"), color = TextDim) }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface,
            title = { Text(tr("Удалить", "Delete") + " «${item.title}»?", color = Cream) },
            text = { Text(tr("Файл будет удалён с телефона.", "The file will be deleted from the phone."), color = TextDim) },
            confirmButton = {
                TextButton(onClick = { vm.deleteTrack(item); confirmDelete = false; onClose() }) {
                    Text(tr("Удалить", "Delete"), color = Color(0xFFFFB4A8))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Отмена", "Cancel"), color = TextDim) } },
        )
    }
}

@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Cream, unfocusedTextColor = Cream,
            focusedBorderColor = Cream, unfocusedBorderColor = Outline,
            focusedLabelColor = Cream, unfocusedLabelColor = TextDim, cursorColor = Cream,
        ),
    )
}

@Composable
private fun Cover(path: String?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Option), contentAlignment = Alignment.Center) {
        if (path != null) AsyncImage(File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.MusicNote, null, tint = TextDim)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(vm: MainViewModel) {
    val track = vm.track ?: return
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
                Box(Modifier.width(150.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(14.dp))) {
                    AsyncImage(track.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (track.durationSec > 0) {
                        Chip(formatDuration(track.durationSec), Modifier.align(Alignment.BottomEnd).padding(6.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Cream, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(track.uploader, color = TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.showSheet = false }) { Icon(Icons.Filled.Close, null, tint = Cream) }
            }
            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Outline)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Option).padding(4.dp)) {
                listOf(Kind.AUDIO to tr("Аудио", "Audio"), Kind.VIDEO to tr("Видео", "Video")).forEach { (k, name) ->
                    val on = vm.kind == k
                    val fg = if (on) OnCream else Cream
                    Row(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                            .background(if (on) Cream else Color.Transparent)
                            .clickable { vm.switchKind(k) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (k == Kind.AUDIO) Icons.Filled.MusicNote else Icons.Filled.Movie, null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(name, color = fg, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (track.live != null || track.durationSec > MainViewModel.RANGE_FROM_SEC) {
                RangePicker(vm, track.durationSec, live = track.live != null)
            }
            Text(tr("Опции загрузки", "Download options"), color = TextDim, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            val options = vm.options()
            if (options.isEmpty()) Text(tr("Нет доступных вариантов", "No options available"), color = TextDim)
            options.chunked(3).forEachIndexed { row, chunk ->
            if (row > 0) Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEachIndexed { col, o ->
                    val i = row * 3 + col
                    val chosen = vm.optionIndex == i
                    Column(
                        Modifier
                            .weight(1f)
                            .height(90.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Option)
                            .border(2.dp, if (chosen) Cream else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { vm.optionIndex = i },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                o.badge, color = OnCream, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Cream)
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(o.label, color = Cream, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                        }
                        o.sizeBytes?.let { Text(formatSize(it), color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
            }
            if (vm.kind == Kind.AUDIO) {
                Text(
                    tr("Попадёт в библиотеку — с обложкой, названием и исполнителем", "Goes to the library with cover, title and artist"),
                    color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)
                )
            }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { vm.download() },
                enabled = vm.options().isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = OnCream),
            ) { Text(tr("Загрузить", "Download"), fontSize = 18.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Cream) }
            Text(tr("Настройки", "Settings"), color = Cream, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            SettingGroup(tr("Язык", "Language")) {
                listOf(Lang.SYSTEM to tr("Системный", "System"), Lang.RU to "Русский", Lang.EN to "English").forEach { (v, name) ->
                    ChoiceChip(name, settings.lang == v) { settings.updateLang(v) }
                }
            }
            SettingGroup(tr("Скачивать по умолчанию", "Download by default")) {
                ChoiceChip(tr(tr("Видео", "Video"), "Video"), settings.defaultKind == Kind.VIDEO) { settings.updateDefaultKind(Kind.VIDEO) }
                ChoiceChip(tr(tr("Аудио", "Audio"), "Audio"), settings.defaultKind == Kind.AUDIO) { settings.updateDefaultKind(Kind.AUDIO) }
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
    Text(title, color = Cream, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) OnCream else Cream,
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Cream else Option)
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

/** Lets long videos be cut to the part that matters before downloading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(vm: MainViewModel, duration: Long, live: Boolean) {
    // For a live stream, times count back from the current moment.
    val label = if (live) {
        "−" + formatDuration(duration - vm.rangeStart).ifEmpty { "0:00" } + " – " +
            (if (vm.rangeEnd >= duration) tr("сейчас", "now") else "−" + formatDuration(duration - vm.rangeEnd))
    } else {
        formatDuration(vm.rangeStart).ifEmpty { "0:00" } + " – " + formatDuration(vm.rangeEnd)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (live) tr("Эфир, доступно ", "Live, available ") + formatDuration(duration) else tr("Фрагмент", "Clip"),
            color = TextDim, fontSize = 16.sp, modifier = Modifier.weight(1f)
        )
        Text(
            label,
            color = Cream, fontSize = 15.sp, fontWeight = FontWeight.Medium
        )
        if (vm.isPartial) {
            TextButton(onClick = { vm.rangeStart = 0; vm.rangeEnd = duration }) {
                Text(tr("Весь", "Full"), color = Cream)
            }
        }
    }
    RangeSlider(
        value = vm.rangeStart.toFloat()..vm.rangeEnd.toFloat(),
        onValueChange = { r ->
            // Keep at least a second between the handles.
            val start = r.start.toLong().coerceIn(0, duration - 1)
            vm.rangeStart = start
            vm.rangeEnd = r.endInclusive.toLong().coerceIn(start + 1, duration)
        },
        valueRange = 0f..duration.toFloat(),
        colors = SliderDefaults.colors(
            thumbColor = Cream, activeTrackColor = Cream, inactiveTrackColor = Cream.copy(alpha = .25f)
        ),
    )
    Text(
        if (live) tr("Скачиваются только выбранные минуты эфира. Насколько далеко можно отмотать назад, решает YouTube.",
            "Only the chosen minutes of the stream are downloaded. YouTube decides how far back you can go.")
        else tr("Обрезка идёт по ближайшему ключевому кадру, поэтому начало может сдвинуться на пару секунд раньше.",
            "Cuts snap to the nearest keyframe, so the start may move a couple of seconds earlier."),
        color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 14.dp)
    )
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Cream, fontSize = 13.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .5f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f %s", bytes / 1_048_576.0, tr("МБ", "MB"))
    else -> "${bytes / 1024} " + tr("КБ", "KB")
}

private fun formatDuration(sec: Long): String =
    if (sec <= 0) "" else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
