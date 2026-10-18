package com.example.pdfnotepad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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

data class DrawPoint(val x: Float, val y: Float)

data class InkStroke(
    val points: List<DrawPoint>,
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false
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

    // Multi-page state: Either imported PDF pages or blank notepad pages
    val pageBitmaps = remember { mutableStateListOf<Bitmap>(createBlankPageBitmap()) }
    var currentPageIndex by remember { mutableIntStateOf(0) }

    // Per-page Undo and Redo stroke stacks
    val pageStrokes = remember { mutableStateMapOf<Int, MutableList<InkStroke>>() }
    val pageRedoStacks = remember { mutableStateMapOf<Int, MutableList<InkStroke>>() }

    // Current active stroke points
    var currentPoints by remember { mutableStateOf<List<DrawPoint>>(emptyList()) }

    // Pen & Eraser Settings
    var currentColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var currentSize by remember { mutableFloatStateOf(8f) }
    var isEraser by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }

    // Helper to get strokes for current page
    val currentStrokes = pageStrokes.getOrPut(currentPageIndex) { mutableStateListOf() }
    val currentRedo = pageRedoStacks.getOrPut(currentPageIndex) { mutableStateListOf() }

    // File Picker to Open PDF
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
                Toast.makeText(context, "Loaded ${loadedPages.size} PDF page(s)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not read PDF file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // File Saver to Export Annotated PDF
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
        Color(0xFFE53935), // Red
        Color(0xFF1E88E5), // Blue
        Color(0xFF43A047), // Green
        Color(0xFFFB8C00), // Orange
        Color(0xFF8E24AA), // Purple
        Color(0xFFFDD835)  // Highlighter Yellow
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // TOP BAR: Open PDF, New Blank Page, Page Navigation, Save PDF
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

                Button(
                    onClick = { savePdfLauncher.launch("Annotated_Notes.pdf") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Save PDF")
                }
            }
        }

        // SECOND BAR: Undo, Redo, Pen, Eraser, Quick Colors + All Colors Button
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

                FilterChip(
                    selected = !isEraser,
                    onClick = { isEraser = false },
                    label = { Text("Pen") }
                )

                FilterChip(
                    selected = isEraser,
                    onClick = { isEraser = true },
                    label = { Text("Eraser") }
                )

                // Preset Color Circles
                presetColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (currentColor == color && !isEraser) 3.dp else 1.dp,
                                color = if (currentColor == color && !isEraser) Color.Black else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable {
                                currentColor = color
                                isEraser = false
                            }
                    )
                }

                // Custom RGB Color Picker Button
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

        // THIRD BAR: Pen / Eraser Size Slider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background( Color(0xFFF5F5F5) )
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEraser) "Eraser Size: ${currentSize.toInt()}px" else "Pen Size: ${currentSize.toInt()}px",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Slider(
                value = currentSize,
                onValueChange = { currentSize = it },
                valueRange = 2f..60f,
                modifier = Modifier.weight(1f)
            )
        }

        // MAIN CANVAS: PDF Page + Drawing Layer
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
                            .pointerInput(currentPageIndex, isEraser, currentColor, currentSize) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentRedo.clear()
                                        currentPoints = listOf(DrawPoint(offset.x, offset.y))
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentPoints = currentPoints + DrawPoint(change.position.x, change.position.y)
                                    },
                                    onDragEnd = {
                                        if (currentPoints.isNotEmpty()) {
                                            currentStrokes.add(
                                                InkStroke(
                                                    points = currentPoints,
                                                    color = currentColor,
                                                    strokeWidth = currentSize,
                                                    isEraser = isEraser
                                                )
                                            )
                                            currentPoints = emptyList()
                                        }
                                    }
                                )
                            }
                    ) {
                        // Draw finished strokes for this page
                        currentStrokes.forEach { stroke ->
                            drawStrokePoints(stroke.points, stroke.color, stroke.strokeWidth, stroke.isEraser)
                        }
                        // Draw stroke currently in progress
                        if (currentPoints.isNotEmpty()) {
                            drawStrokePoints(currentPoints, currentColor, currentSize, isEraser)
                        }
                    }
                }
            }
        }
    }

    if (showColorDialog) {
        FullColorPickerDialog(
            initialColor = currentColor,
            onColorSelected = {
                currentColor = it
                isEraser = false
                showColorDialog = false
            },
            onDismiss = { showColorDialog = false }
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePoints(
    points: List<DrawPoint>,
    color: Color,
    strokeWidth: Float,
    isEraser: Boolean
) {
    if (points.isEmpty()) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }
    drawPath(
        path = path,
        color = if (isEraser) Color.Transparent else color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = if (isEraser) BlendMode.Clear else BlendMode.SrcOver
    )
}

@Composable
fun FullColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var red by remember { mutableFloatStateOf(initialColor.red) }
    var green by remember { mutableFloatStateOf(initialColor.green) }
    var blue by remember { mutableFloatStateOf(initialColor.blue) }
    var alpha by remember { mutableFloatStateOf(initialColor.alpha) }
    val previewColor = Color(red, green, blue, alpha)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Pen Color & Opacity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(previewColor)
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                )
                Text("Red: ${(red * 255).toInt()}")
                Slider(value = red, onValueChange = { red = it })
                Text("Green: ${(green * 255).toInt()}")
                Slider(value = green, onValueChange = { green = it })
                Text("Blue: ${(blue * 255).toInt()}")
                Slider(value = blue, onValueChange = { blue = it })
                Text("Opacity (Highlighter mode): ${(alpha * 100).toInt()}%")
                Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.2f..1f)
            }
        },
        confirmButton = {
            Button(onClick = { onColorSelected(previewColor) }) { Text("Use Color") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun createBlankPageBitmap(width: Int = 1200, height: Int = 1600): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)
    // Draw light ruled notebook lines
    val linePaint = AndroidPaint().apply {
        color = AndroidColor.rgb(220, 230, 242)
        strokeWidth = 2f
    }
    for (y in 120 until height step 60) {
        canvas.drawLine(60f, y.toFloat(), (width - 60).toFloat(), y.toFloat(), linePaint)
    }
    return bitmap
}

fun loadPdfPages(context: Context, uri: Uri): List<Bitmap> {
    val pages = mutableListOf<Bitmap>()
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = AndroidCanvas(bitmap)
                        canvas.drawColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.add(bitmap)
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return pages
}

fun exportAnnotatedPdf(
    context: Context,
    destUri: Uri,
    pageBitmaps: List<Bitmap>,
    pageStrokes: Map<Int, List<InkStroke>>,
    canvasSize: IntSize
): Boolean {
    return try {
        val pdfDocument = PdfDocument()
        pageBitmaps.forEachIndexed { index, pageBitmap ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageBitmap.width, pageBitmap.height, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val pdfCanvas = page.canvas

            // 1. Draw the underlying PDF/Notepad page
            pdfCanvas.drawBitmap(pageBitmap, 0f, 0f, null)

            // 2. Draw the ink layer scaled to match the bitmap dimensions
            val strokes = pageStrokes[index].orEmpty()
            if (strokes.isNotEmpty() && canvasSize.width > 0 && canvasSize.height > 0) {
                val inkBitmap = Bitmap.createBitmap(pageBitmap.width, pageBitmap.height, Bitmap.Config.ARGB_8888)
                val inkCanvas = AndroidCanvas(inkBitmap)
                val scaleX = pageBitmap.width.toFloat() / canvasSize.width.toFloat()
                val scaleY = pageBitmap.height.toFloat() / canvasSize.height.toFloat()

                strokes.forEach { stroke ->
                    if (stroke.points.isNotEmpty()) {
                        val paint = AndroidPaint().apply {
                            isAntiAlias = true
                            style = AndroidPaint.Style.STROKE
                            strokeCap = AndroidPaint.Cap.ROUND
                            strokeJoin = AndroidPaint.Join.ROUND
                            strokeWidth = stroke.strokeWidth * ((scaleX + scaleY) / 2f)
                            if (stroke.isEraser) {
                                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                            } else {
                                color = stroke.color.toArgb()
                            }
                        }
                        val path = android.graphics.Path().apply {
                            moveTo(stroke.points.first().x * scaleX, stroke.points.first().y * scaleY)
                            for (i in 1 until stroke.points.size) {
                                lineTo(stroke.points[i].x * scaleX, stroke.points[i].y * scaleY)
                            }
                        }
                        inkCanvas.drawPath(path, paint)
                    }
                }
                pdfCanvas.drawBitmap(inkBitmap, 0f, 0f, null)
            }
            pdfDocument.finishPage(page)
        }

        context.contentResolver.openOutputStream(destUri)?.use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
