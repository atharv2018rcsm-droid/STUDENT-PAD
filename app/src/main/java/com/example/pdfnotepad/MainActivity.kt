package com.example.pdfnotepad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

enum class ActiveTool {
    PEN,
    ERASER,
    SCROLL_POINTER,
    SCRIBBLE_TEXT
}

data class DrawPoint(val x: Float, val y: Float)

data class InkStroke(
    val points: List<DrawPoint>,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false,
    val isNeatCalligraphy: Boolean = true
)

data class TextNoteItem(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Color,
    val fontSize: Float
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PdfNotepadApp()
                }
            }
        }
    }
}

@Composable
fun PdfNotepadApp() {
    val context = LocalContext.current

    // Multi-page state
    val pageBitmaps = remember { mutableStateListOf<Bitmap>(createBlankPageBitmap()) }
    var currentPageIndex by remember { mutableIntStateOf(0) }

    // Per-page Undo & Redo stroke stacks
    val pageStrokes = remember { mutableStateMapOf<Int, MutableList<InkStroke>>() }
    val pageRedoStacks = remember { mutableStateMapOf<Int, MutableList<InkStroke>>() }
    val pageTextNotes = remember { mutableStateMapOf<Int, MutableList<TextNoteItem>>() }

    // Current active stroke points
    var currentPoints by remember { mutableStateOf<List<DrawPoint>>(emptyList()) }

    // Zoom & Scroll State (Per Page)
    var zoomScale by remember(currentPageIndex) { mutableFloatStateOf(1f) }
    var panOffset by remember(currentPageIndex) { mutableStateOf(Offset.Zero) }

    // Tool & Pen Settings
    var activeTool by remember { mutableStateOf(ActiveTool.PEN) }
    var neatHandwritingMode by remember { mutableStateOf(true) }
    var currentColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var currentSize by remember { mutableFloatStateOf(8f) }
    var showColorDialog by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }

    // Always-updated state references for inside pointerInput gesture loop
    val currentZoomScale by rememberUpdatedState(zoomScale)
    val currentPanOffset by rememberUpdatedState(panOffset)
    val currentCanvasSize by rememberUpdatedState(canvasSize)

    // Scribble-to-Text Dialog State
    var pendingTextOffset by remember { mutableStateOf<Offset?>(null) }
    var scribbleInputText by remember { mutableStateOf("") }

    val currentStrokes = pageStrokes.getOrPut(currentPageIndex) { mutableStateListOf() }
    val currentRedo = pageRedoStacks.getOrPut(currentPageIndex) { mutableStateListOf() }
    val currentTexts = pageTextNotes.getOrPut(currentPageIndex) { mutableStateListOf() }

    val openPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val loadedPages = loadPdfPages(context, it)
            if (loadedPages.isNotEmpty()) {
                pageBitmaps.clear()
                pageBitmaps.addAll(loadedPages)
                pageStrokes.clear()
                pageRedoStacks.clear()
                pageTextNotes.clear()
                currentPageIndex = 0
                zoomScale = 1f
                panOffset = Offset.Zero
                Toast.makeText(context, "Loaded ${loadedPages.size} PDF page(s)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not read PDF file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let {
            val success = exportAnnotatedPdf(
                context = context,
                destUri = it,
                pageBitmaps = pageBitmaps,
                pageStrokes = pageStrokes,
                pageTextNotes = pageTextNotes,
                canvasSize = canvasSize
            )
            if (success) {
                Toast.makeText(context, "Saved Annotated PDF successfully!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val presetColors = listOf(
        Color.Black,
        Color(0xFFE53935), // Red
        Color(0xFF1E88E5), // Blue
        Color(0xFF43A047), // Green
        Color(0xFFFB8C00), // Orange
        Color(0xFF8E24AA), // Purple
        Color(0xFFFDD835) // Highlighter Yellow
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // TOP BAR: Open PDF, New Blank Page, Page Navigation, Zoom Controls, Save PDF
        Surface(tonalElevation = 4.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { openPdfLauncher.launch(arrayOf("application/pdf")) }) {
                    Text("Open PDF")
                }

                OutlinedButton(onClick = {
                    pageBitmaps.add(createBlankPageBitmap())
                    currentPageIndex = pageBitmaps.lastIndex
                }) {
                    Text("+ Blank Page")
                }

                // Page switcher
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                        enabled = currentPageIndex > 0
                    ) { Text("< Prev") }

                    Text(
                        text = "Page ${currentPageIndex + 1}/${pageBitmaps.size}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    TextButton(
                        onClick = { if (currentPageIndex < pageBitmaps.lastIndex) currentPageIndex++ },
                        enabled = currentPageIndex < pageBitmaps.lastIndex
                    ) { Text("Next >") }
                }

                // Quick Zoom Out / Reset / Zoom In Buttons
                OutlinedButton(onClick = {
                    zoomScale = (zoomScale - 0.25f).coerceIn(1f, 5f)
                    if (zoomScale == 1f) panOffset = Offset.Zero
                }) { Text("Zoom -") }

                TextButton(onClick = {
                    zoomScale = 1f
                    panOffset = Offset.Zero
                }) { Text("${(zoomScale * 100).toInt()}%") }

                OutlinedButton(onClick = {
                    zoomScale = (zoomScale + 0.25f).coerceIn(1f, 5f)
                }) { Text("Zoom +") }

                Button(
                    onClick = { savePdfLauncher.launch("Annotated_Notes.pdf") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Save PDF")
                }
            }
        }

        // SECOND BAR: Pointer/Scroll Mode, Pen, Good Handwriting Smoothing, Scribble-to-Text, Eraser, Undo/Redo, Colors
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Pointer / Scroll Only Mode
                FilterChip(
                    selected = activeTool == ActiveTool.SCROLL_POINTER,
                    onClick = { activeTool = ActiveTool.SCROLL_POINTER },
                    label = { Text("Pointer (Scroll)") }
                )

                // 2. Pen Mode
                FilterChip(
                    selected = activeTool == ActiveTool.PEN,
                    onClick = { activeTool = ActiveTool.PEN },
                    label = { Text("Pen") }
                )

                // 3. Good Handwriting (Auto-Smooth & Calligraphy) Toggle
                FilterChip(
                    selected = neatHandwritingMode,
                    onClick = { neatHandwritingMode = !neatHandwritingMode },
                    label = { Text("Neat Write") }
                )

                // 4. Scribble to Neat Text Stamp
                FilterChip(
                    selected = activeTool == ActiveTool.SCRIBBLE_TEXT,
                    onClick = { activeTool = ActiveTool.SCRIBBLE_TEXT },
                    label = { Text("Scribble Text") }
                )

                // 5. Eraser Mode
                FilterChip(
                    selected = activeTool == ActiveTool.ERASER,
                    onClick = { activeTool = ActiveTool.ERASER },
                    label = { Text("Eraser") }
                )

                OutlinedButton(
                    onClick = {
                        if (currentStrokes.isNotEmpty()) {
                            val removed = currentStrokes.removeAt(currentStrokes.lastIndex)
                            currentRedo.add(removed)
                        } else if (currentTexts.isNotEmpty()) {
                            currentTexts.removeAt(currentTexts.lastIndex)
                        }
                    },
                    enabled = currentStrokes.isNotEmpty() || currentTexts.isNotEmpty()
                ) { Text("Undo") }

                OutlinedButton(
                    onClick = {
                        if (currentRedo.isNotEmpty()) {
                            val restored = currentRedo.removeAt(currentRedo.lastIndex)
                            currentStrokes.add(restored)
                        }
                    },
                    enabled = currentRedo.isNotEmpty()
                ) { Text("Redo") }

                // Preset Color Circles
                presetColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (currentColor == color && activeTool != ActiveTool.ERASER) 3.dp else 1.dp,
                                color = if (currentColor == color && activeTool != ActiveTool.ERASER) Color.Black else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable {
                                currentColor = color
                                if (activeTool == ActiveTool.ERASER) activeTool = ActiveTool.PEN
                            }
                    )
                }

                OutlinedButton(onClick = { showColorDialog = true }) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(currentColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("All Colors")
                }
            }
        }

        // THIRD BAR: Size Slider + Helpful Mode Hint
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val modeText = when (activeTool) {
                ActiveTool.SCROLL_POINTER -> "Pointer Mode: Drag 1 finger to scroll, pinch 2 fingers to zoom"
                ActiveTool.SCRIBBLE_TEXT -> "Tap anywhere on PDF to scribble/write neat text (${(currentSize * 3).toInt()}pt)"
                ActiveTool.ERASER -> "Eraser Size: ${currentSize.toInt()}px (2 fingers to scroll/zoom)"
                ActiveTool.PEN -> "Pen Size: ${currentSize.toInt()}px (2 fingers to scroll/zoom)"
            }
            Text(
                text = modeText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (activeTool != ActiveTool.SCROLL_POINTER) {
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = currentSize,
                    onValueChange = { currentSize = it },
                    valueRange = 2f..50f,
                    modifier = Modifier.width(150.dp)
                )
            }
        }

        // MAIN CANVAS: Outer container captures gestures so coordinates never jump while zooming!
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFCCCCCC))
                .padding(8.dp)
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .pointerInput(currentPageIndex, activeTool, currentColor, currentSize, neatHandwritingMode) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)

                        if (activeTool == ActiveTool.SCRIBBLE_TEXT) {
                            val unscaledPos = screenToCanvasPoint(
                                screenPt = firstDown.position,
                                canvasSize = currentCanvasSize,
                                scale = currentZoomScale,
                                offset = currentPanOffset
                            )
                            pendingTextOffset = unscaledPos
                            scribbleInputText = ""
                            return@awaitEachGesture
                        }

                        var isMultiTouchZooming = false
                        val activeDrawPoints = mutableListOf<DrawPoint>()

                        if (activeTool == ActiveTool.PEN || activeTool == ActiveTool.ERASER) {
                            val startPt = screenToCanvasPoint(
                                screenPt = firstDown.position,
                                canvasSize = currentCanvasSize,
                                scale = currentZoomScale,
                                offset = currentPanOffset
                            )
                            activeDrawPoints.add(DrawPoint(startPt.x, startPt.y))
                            currentPoints = activeDrawPoints.toList()
                        }

                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }

                            if (pressedCount >= 2 || activeTool == ActiveTool.SCROLL_POINTER) {
                                if (pressedCount >= 2) {
                                    isMultiTouchZooming = true
                                    activeDrawPoints.clear()
                                    currentPoints = emptyList()
                                }

                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()

                                val newScale = (currentZoomScale * zoomChange).coerceIn(1f, 5f)
                                val maxPanX = (currentCanvasSize.width * (newScale - 1f)) / 2f
                                val maxPanY = (currentCanvasSize.height * (newScale - 1f)) / 2f

                                zoomScale = newScale
                                panOffset = Offset(
                                    x = (currentPanOffset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                                    y = (currentPanOffset.y + panChange.y).coerceIn(-maxPanY, maxPanY)
                                )

                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (pressedCount == 1 && !isMultiTouchZooming &&
                                (activeTool == ActiveTool.PEN || activeTool == ActiveTool.ERASER)
                            ) {
                                val change = event.changes.firstOrNull { it.pressed }
                                if (change != null && change.positionChanged()) {
                                    val pt = screenToCanvasPoint(
                                        screenPt = change.position,
                                        canvasSize = currentCanvasSize,
                                        scale = currentZoomScale,
                                        offset = currentPanOffset
                                    )
                                    val last = activeDrawPoints.lastOrNull()
                                    val minDistance = if (neatHandwritingMode) 3.0f / currentZoomScale else 1f
                                    if (last == null || abs(pt.x - last.x) > minDistance || abs(pt.y - last.y) > minDistance) {
                                        activeDrawPoints.add(DrawPoint(pt.x, pt.y))
                                        currentPoints = activeDrawPoints.toList()
