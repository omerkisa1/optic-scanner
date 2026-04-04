package com.omrreader.ui.screens.scan

import android.Manifest
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch

// ── Colours ──────────────────────────────────────────────────────────────────
private val ColorFound   = Color(0xFF4CAF50)   // green  – corner detected
private val ColorMissing = Color(0xFFFFFFFF)   // white  – corner not yet found
private val ColorWarn    = Color(0xFFFFC107)   // amber  – some found
private val ColorDim     = Color(0x66000000)   // dark overlay
private val ColorReady   = Color(0xFF00E676)   // bright green – all 4 found

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScanScreen(
    examId: Long,
    onBack: () -> Unit,
    onScanSuccess: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel()
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val scanState by viewModel.scanState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var markerState by remember { mutableStateOf<MarkerGuideState>(MarkerGuideState.NotFound) }

    LaunchedEffect(examId) { viewModel.setActiveExam(examId) }

    LaunchedEffect(Unit) {
        if (cameraPermissionState.status !is PermissionStatus.Granted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(scanState) {
        when (val state = scanState) {
            is ScanState.ReviewReady -> onScanSuccess()
            is ScanState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearError()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Form Tara") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        if (cameraPermissionState.status is PermissionStatus.Granted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                CameraPreview(
                    onImageCaptured = viewModel::processImage,
                    onMarkerStateChanged = { state -> markerState = state },
                    onError = { message ->
                        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                    },
                    captureEnabled = scanState !is ScanState.Processing,
                    modifier = Modifier.fillMaxSize()
                )

                if (scanState !is ScanState.Processing) {
                    MarkerGuideOverlay(
                        markerState = markerState,
                        modifier = Modifier.fillMaxSize()
                    )
                    MarkerGuideHint(
                        markerState = markerState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )
                    CornerStatusRow(
                        markerState = markerState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp)
                    )
                }

                if (scanState is ScanState.Processing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Kağıt işleniyor...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Kamera izni gerekli. Ayarlardan izin verin.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
                Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() },
                    modifier = Modifier.padding(top = 16.dp)
                ) { Text("İzni Tekrar İste") }
                Button(
                    onClick = onBack,
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text("Geri Dön") }
            }
        }
    }
}

// ── Overlay ───────────────────────────────────────────────────────────────────

/**
 * Camera overlay for OMR form detection.
 *
 * Design: four FIXED "target squares" show exactly where each black corner
 * marker of the form must land.
 *
 *   ■───────────────────────■
 *   │  (Sol Üst)  (Sağ Üst) │
 *   │                       │  ← guide area
 *   │  (Sol Alt)  (Sağ Alt) │
 *   ■───────────────────────■
 *
 * Each target square:
 *  • Hollow white square with crosshair  → marker not yet found here
 *  • Solid green square with checkmark  → camera detected the black marker
 *
 * When all 4 are found the guide border turns bright green and pulses.
 */
@Composable
private fun MarkerGuideOverlay(
    markerState: MarkerGuideState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val isReady = markerState is MarkerGuideState.Ready

    Canvas(modifier = modifier) {
        // ── Guide area bounds ────────────────────────────────────────────────
        val hPad   = 56f
        val vPad   = 80f
        val gLeft  = hPad
        val gTop   = vPad
        val gRight = size.width  - hPad
        val gBot   = size.height - vPad * 1.3f

        // ── Dark curtain outside guide area ──────────────────────────────────
        drawRect(ColorDim, Offset.Zero,           androidx.compose.ui.geometry.Size(size.width, gTop))
        drawRect(ColorDim, Offset(0f, gBot),      androidx.compose.ui.geometry.Size(size.width, size.height - gBot))
        drawRect(ColorDim, Offset(0f, gTop),      androidx.compose.ui.geometry.Size(gLeft, gBot - gTop))
        drawRect(ColorDim, Offset(gRight, gTop),  androidx.compose.ui.geometry.Size(size.width - gRight, gBot - gTop))

        // ── Resolve detected corner positions ────────────────────────────────
        fun mp(p: MarkerPoint?) = p?.let { Offset(it.x * size.width, it.y * size.height) }

        val tlDet: Offset?; val trDet: Offset?; val brDet: Offset?; val blDet: Offset?
        when (markerState) {
            is MarkerGuideState.Ready -> {
                tlDet = mp(markerState.topLeft);     trDet = mp(markerState.topRight)
                brDet = mp(markerState.bottomRight); blDet = mp(markerState.bottomLeft)
            }
            is MarkerGuideState.Partial -> {
                tlDet = mp(markerState.topLeft);     trDet = mp(markerState.topRight)
                brDet = mp(markerState.bottomRight); blDet = mp(markerState.bottomLeft)
            }
            else -> { tlDet = null; trDet = null; brDet = null; blDet = null }
        }

        // ── Guide border: thin dashed line connecting the four target corners ─
        val borderColor = if (isReady) ColorReady.copy(alpha = pulseAlpha)
                          else Color.White.copy(alpha = 0.25f)
        val borderStroke = Stroke(
            width = if (isReady) 2.5f else 1.2f,
            pathEffect = if (isReady) null
                         else PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        )
        val borderPath = Path().apply {
            moveTo(gLeft, gTop); lineTo(gRight, gTop)
            lineTo(gRight, gBot); lineTo(gLeft, gBot); close()
        }
        drawPath(borderPath, borderColor, style = borderStroke)

        // ── If all 4 detected: draw detected quad with green fill ────────────
        if (isReady && tlDet != null && trDet != null && brDet != null && blDet != null) {
            val quadPath = Path().apply {
                moveTo(tlDet.x, tlDet.y); lineTo(trDet.x, trDet.y)
                lineTo(brDet.x, brDet.y); lineTo(blDet.x, blDet.y); close()
            }
            drawPath(quadPath, ColorReady.copy(alpha = pulseAlpha * 0.15f))
            drawPath(quadPath, ColorReady.copy(alpha = pulseAlpha),
                style = Stroke(width = 3f, pathEffect = PathEffect.cornerPathEffect(6f)))
        }

        // ── Target square size (represents the black corner marker) ───────────
        // Size is proportional to the guide area width, similar to the actual
        // marker size on the form (≈ 5% of form width).
        val tSz   = ((gRight - gLeft) * 0.10f).coerceIn(40f, 68f)
        val alpha = if (isReady) pulseAlpha else 1f

        // ── Four target squares ───────────────────────────────────────────────
        // Guide corners (centre of each target square)
        drawTargetSquare(Offset(gLeft,  gTop),  CornerDir.TL, tSz, tlDet, alpha)
        drawTargetSquare(Offset(gRight, gTop),  CornerDir.TR, tSz, trDet, alpha)
        drawTargetSquare(Offset(gRight, gBot),  CornerDir.BR, tSz, brDet, alpha)
        drawTargetSquare(Offset(gLeft,  gBot),  CornerDir.BL, tSz, blDet, alpha)
    }
}

private enum class CornerDir { TL, TR, BR, BL }

/**
 * Draws a single corner target square.
 *
 * [corner] is the exact corner-point of the guide area.
 * The square is drawn INWARD from that point (so it always stays inside the
 * guide area regardless of which corner).
 *
 * States
 *  • detectedPos == null → hollow white square + crosshair (marker not found)
 *  • detectedPos != null → filled green square + white checkmark + glow
 *    A small orange dot also marks the exact detected pixel position so the
 *    user can see how well-centred the marker is.
 */
private fun DrawScope.drawTargetSquare(
    corner: Offset,
    dir: CornerDir,
    size: Float,              // side length of the target square
    detectedPos: Offset?,     // actual detected marker centre (null = not found)
    alpha: Float
) {
    val found = detectedPos != null

    // Inward signs: square is always INSIDE the guide area
    val hSign = if (dir == CornerDir.TL || dir == CornerDir.BL)  1f else -1f
    val vSign = if (dir == CornerDir.TL || dir == CornerDir.TR)  1f else -1f

    val sqLeft = if (hSign > 0) corner.x else corner.x - size
    val sqTop  = if (vSign > 0) corner.y else corner.y - size
    val sqSize = androidx.compose.ui.geometry.Size(size, size)
    val sqTL   = Offset(sqLeft, sqTop)
    val sqCx   = sqLeft + size / 2f
    val sqCy   = sqTop  + size / 2f

    // ── Fill ──────────────────────────────────────────────────────────────────
    if (found) {
        // Bright green fill
        drawRect(ColorFound.copy(alpha = alpha * 0.55f), sqTL, sqSize)
        // Subtle outer glow (larger semi-transparent rect behind)
        drawRect(
            ColorFound.copy(alpha = alpha * 0.18f),
            topLeft = Offset(sqLeft - 8f, sqTop - 8f),
            size    = androidx.compose.ui.geometry.Size(size + 16f, size + 16f)
        )
    }

    // ── Border ────────────────────────────────────────────────────────────────
    val borderColor  = if (found) ColorFound.copy(alpha = alpha) else Color.White.copy(alpha = 0.55f)
    val borderStroke = if (found) 4f else 2f
    drawRect(borderColor, sqTL, sqSize, style = Stroke(width = borderStroke))

    // ── Crosshair (shown when NOT found — guides the user) ────────────────────
    if (!found) {
        val xColor = Color.White.copy(alpha = 0.40f)
        val margin  = size * 0.20f
        // Horizontal bar (gap in centre)
        drawLine(xColor, Offset(sqLeft + margin, sqCy), Offset(sqCx - size * 0.08f, sqCy), 1.5f)
        drawLine(xColor, Offset(sqCx + size * 0.08f, sqCy), Offset(sqLeft + size - margin, sqCy), 1.5f)
        // Vertical bar (gap in centre)
        drawLine(xColor, Offset(sqCx, sqTop + margin), Offset(sqCx, sqCy - size * 0.08f), 1.5f)
        drawLine(xColor, Offset(sqCx, sqCy + size * 0.08f), Offset(sqCx, sqTop + size - margin), 1.5f)
        // Centre dot
        drawCircle(xColor, radius = 3f, center = Offset(sqCx, sqCy))
    }

    // ── Checkmark (shown when found) ─────────────────────────────────────────
    if (found) {
        val ck = size * 0.22f
        val checkPath = Path().apply {
            moveTo(sqCx - ck, sqCy)
            lineTo(sqCx - ck * 0.2f, sqCy + ck * 0.8f)
            lineTo(sqCx + ck, sqCy - ck * 0.6f)
        }
        drawPath(checkPath, Color.White.copy(alpha = alpha),
            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // ── Detected position dot (shows exact camera hit vs. target centre) ──────
    if (detectedPos != null) {
        // Small amber dot at actual detected pixel — shows alignment accuracy
        drawCircle(Color(0xFFFFC107).copy(alpha = alpha * 0.9f), radius = 5f, center = detectedPos)
        drawCircle(Color.White.copy(alpha = alpha * 0.8f),        radius = 2f, center = detectedPos)
    }

    // ── Corner accent lines (short ticks extending outside the square) ────────
    // These mimic the classic "camera viewfinder corner" look and make the
    // target easier to spot against a busy background.
    val tickLen   = size * 0.45f
    val tickColor = borderColor
    val tickW     = if (found) 3.5f else 1.8f

    // Horizontal tick from the outer corner
    drawLine(tickColor, corner, corner + Offset(hSign * tickLen, 0f), tickW, StrokeCap.Round)
    // Vertical tick from the outer corner
    drawLine(tickColor, corner, corner + Offset(0f, vSign * tickLen), tickW, StrokeCap.Round)
}

// ── Hint banner ───────────────────────────────────────────────────────────────

@Composable
private fun MarkerGuideHint(
    markerState: MarkerGuideState,
    modifier: Modifier = Modifier
) {
    val (text, bgColor) = when (markerState) {
        is MarkerGuideState.Ready ->
            "Tüm köşeler algılandı — Çek!" to ColorFound.copy(alpha = 0.9f)
        is MarkerGuideState.Partial ->
            "${markerState.foundCount}/4 köşe bulundu — Formu hizalayın" to ColorWarn.copy(alpha = 0.9f)
        MarkerGuideState.NotFound ->
            "Optik formu çerçeve içine getirin" to Color.Black.copy(alpha = 0.65f)
    }

    val animatedBg by animateColorAsState(targetValue = bgColor, label = "hintBg")

    Box(
        modifier = modifier
            .background(animatedBg, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

// ── Per-corner status indicators ──────────────────────────────────────────────

/**
 * Four coloured dots showing which corners are detected.
 * Layout mirrors the physical corners:
 *   ● ●
 *   ● ●
 */
@Composable
private fun CornerStatusRow(
    markerState: MarkerGuideState,
    modifier: Modifier = Modifier
) {
    val tl: Boolean
    val tr: Boolean
    val br: Boolean
    val bl: Boolean
    when (markerState) {
        is MarkerGuideState.Ready   -> { tl = true; tr = true; br = true; bl = true }
        is MarkerGuideState.Partial -> {
            tl = markerState.topLeft     != null
            tr = markerState.topRight    != null
            br = markerState.bottomRight != null
            bl = markerState.bottomLeft  != null
        }
        else -> { tl = false; tr = false; br = false; bl = false }
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CornerDot(found = tl, label = "Sol Üst")
                CornerDot(found = tr, label = "Sağ Üst")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CornerDot(found = bl, label = "Sol Alt")
                CornerDot(found = br, label = "Sağ Alt")
            }
        }
    }
}

@Composable
private fun CornerDot(found: Boolean, label: String) {
    val color by animateColorAsState(
        targetValue = if (found) ColorFound else Color.White.copy(alpha = 0.3f),
        animationSpec = tween(300),
        label = "dot_$label"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 9.sp
        )
    }
}
