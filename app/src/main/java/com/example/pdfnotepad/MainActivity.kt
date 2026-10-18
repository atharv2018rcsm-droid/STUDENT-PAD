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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

data class DrawPoint(val x: Float, val y: Float)

data class InkStroke(
    val points: List<DrawPoint>,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false,
    val isNeatCalligraphy: Boolean = true,
    val textNote: String? = null
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

    // Per-page Undo and Redo stroke stacks
    val pageStrokes = remember { mutableStateMapOf<Int, MutableList<InkStroke>>() }
    val pageRedoStacks = remember { mutableStateMapOf<Int, MutableList<InkStroke>>() }

    // Current active stroke points
    var currentPoints by remember { mutableStateOf<List<DrawPoint>>(emptyList()) }

    // Zoom & Scroll State
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    // Tool Mode: 0 = Pen, 1 = Eraser, 2 = Pointer (Scroll Only), 3 = Scribble Text
    var toolMode by remember { mutableIntStateOf(0) }
    var neatHandwriting by remember { mutableStateOf(true) }

    // Pen & Color Settings
    var currentColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var currentSize by remember { mutableFloatStateOf(8f) }
    var showColorDialog by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }

    // Scribble Text Dialog State
    var showScribbleDialog by remember { mutableStateOf(false) }
    var scribbleX by remember { mutableFloatStateOf(200f) }
    var scribbleY by remember { mutableFloatStateOf(200f) }
    var scribbleText by remember { mutableStateOf("") }

    val currentStrokes = pageStrokes.getOrPut(currentPageIndex) { mutableStateListOf() }
    val currentRedo = pageRedoStacks.getOrPut(currentPageIndex) { mutableStateListOf() }

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
                currentPageIndex = 0
                zoomScale = 1f
                panX = 0f
                panY = 0f
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
        Color(0xFFE53935),
        Color(0xFF1E88E5),
        Color(0xFF43A047),
        Color(0xFFFB8C00),
        Color(0xFF8E24AA),
        Color(0xFFFDD835)
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // TOP BAR: Open PDF, + Blank Page, Page Switcher, Zoom In/Out, Save PDF
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
                    zoomScale = 1f
                    panX = 0f
                    panY = 0f
                }) {
                    Text("+ Blank Page")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (currentPageIndex > 0) {
                                currentPageIndex--
                                zoomScale = 1f
                                panX = 0f
                                panY = 0f
                            }
                        },
                        enabled = currentPageIndex > 0
                    ) { Text("< Prev") }

                    Text(
                        text = "Page ${currentPageIndex + 1}/${pageBitmaps.size}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    TextButton(
                        onClick = {
                            if (currentPageIndex < pageBitmaps.lastIndex) {
                                currentPageIndex++
                                zoomScale = 1f
                                panX = 0f
                                panY = 0f
                            }
                        },
                        enabled = currentPageIndex < pageBitmaps.lastIndex
                    ) { Text("Next >") }
                }

                // Zoom Out / Reset / Zoom In Buttons
                OutlinedButton(onClick = {
                    zoomScale = (zoomScale - 0.25f).coerceIn(1f, 5f)
                    if (zoomScale == 1f) {
                        panX = 0f
                        panY = 0f
                    }
                }) { Text("Zoom -") }

                TextButton(onClick = {
                    zoomScale = 1f
                    panX = 0f
                    panY = 0f
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

        // SECOND BAR: Pointer (Scroll), Pen, Neat Write, Scribble Text, Eraser, Undo/Redo, Colors
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = toolMode == 2,
                    onClick = { toolMode = 2 },
                    label = { Text("Pointer (Scroll)") }
                )

                FilterChip(
                    selected = toolMode == 0,
                    onClick = { toolMode = 0 },
                    label = { Text("Pen") }
                )

                FilterChip(
                    selected = neatHandwriting,
                    onClick = { neatHandwriting = !neatHandwriting },
                    label = { Text("Neat Write") }
                )

                FilterChip(
                    selected = toolMode == 3,
                    onClick = { toolMode = 3 },
                    label = { Text("Scribble Text") }
                )

                FilterChip(
                    selected = toolMode == 1,
                    onClick = { toolMode = 1 },
                    label = { Text("Eraser") }
                )

                OutlinedButton(
                    onClick = {
                        if (currentStrokes.isNotEmpty()) {
                            val removed = currentStrokes.removeAt(currentStrokes.lastIndex)
                            currentRedo.add(removed)
                        }
                    },
                    enabled = currentStrokes.isNotEmpty()
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

                presetColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (currentColor == color && toolMode != 1) 3.dp else 1.dp,
                                color = if (currentColor == color && toolMode != 1) Color.Black else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable {
                                currentColor = color
                                if (toolMode == 1) toolMode = 0
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

        // THIRD BAR: Size Slider & Mode Info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val labelText = when (toolMode) {
                2 -> "Pointer Mode: Drag finger on PDF to scroll around"
                3 -> "Scribble Mode: Touch PDF where you want neat text (${(currentSize * 3).toInt()}pt)"
                1 -> "Eraser Size: ${currentSize.toInt()}px"
                else -> "Pen Size: ${currentSize.toInt()}px"
            }
            Text(
                text = labelText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (toolMode != 2) {
                Spacer(modifier = Modifier.width(12.dp))
                Slider(
                    value = currentSize,
                    onValueChange = { currentSize = it },
                    valueRange = 2f..60f,
                    modifier = Modifier.width(150.dp)
                )
            }
        }

        // MAIN CANVAS
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFCCCCCC))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val activeBitmap = pageBitmaps.getOrNull(currentPageIndex)
            if (activeBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White)
                        .onSizeChanged { canvasSize = it }
                        .graphicsLayer(
                            scaleX = zoomScale,
                            scaleY = zoomScale,
                            translationX = panX,
                            translationY = panY
                        )
                ) {
                    Image(
                        bitmap = activeBitmap.asImageBitmap(),
                        contentDescription = "PDF Page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .pointerInput(currentPageIndex, toolMode, currentColor, currentSize, neatHandwriting, zoomScale) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        if (toolMode == 3) {
                                            scribbleX = offset.x
                                            scribbleY = offset.y
                                            scribbleText = ""
                                            showScribbleDialog = true
                                        } else if (toolMode == 0 || toolMode == 1) {
                                            currentRedo.clear()
                                            currentPoints = listOf(DrawPoint(offset.x, offset.y))
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (toolMode == 2) {
                                            // Pointer Scroll Mode: Pan across the zoomed PDF
                                            val maxPanX = (canvasSize.width * (zoomScale - 1f)) / 2f + 300f
                                            val maxPanY = (canvasSize.height * (zoomScale - 1f)) / 2f + 400f
                                            panX = (panX + dragAmount.x * zoomScale).coerceIn(-maxPanX, maxPanX)
                                            panY = (panY + dragAmount.y * zoomScale).coerceIn(-maxPanY, maxPanY)
                                        } else if (toolMode == 0 || toolMode == 1) {
                                            val pt = change.position
                                            val last = currentPoints.lastOrNull()
                                            val minDist = if (neatHandwriting) 2.5f else 1f
                                            if (last == null || abs(pt.x - last.x) > minDist || abs(pt.y - last.y) > minDist) {
                                                currentPoints = currentPoints + DrawPoint(pt.x, pt.y)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        if ((toolMode == 0 || toolMode == 1) && currentPoints.isNotEmpty()) {
                                            val finalPts = if (neatHandwriting && toolMode == 0) {
                                                smoothHandwritingPoints(currentPoints)
                                            } else {
                                                currentPoints
                                            }
                                            currentStrokes.add(
                                                InkStroke(
                                                    points = finalPts,
                                                    color = currentColor,
                                                    strokeWidth = currentSize,
                                                    isEraser = toolMode == 1,
                                                    isNeatCalligraphy = neatHandwriting
                                                )
                                            )
                                            currentPoints = emptyList()
                                        }
                                    }
                                )
                            }
                    ) {
                        currentStrokes.forEach { stroke ->
                            drawStrokeOrText(stroke)
                        }
                        if (currentPoints.isNotEmpty()) {
                            drawStrokeOrText(
                                InkStroke(
                                    points = currentPoints,
                                    color = currentColor,
                                    strokeWidth = currentSize,
                                    isEraser = toolMode == 1,
                                    isNeatCalligraphy = neatHandwriting
                                )
                            )
                        }
                    }
                }
