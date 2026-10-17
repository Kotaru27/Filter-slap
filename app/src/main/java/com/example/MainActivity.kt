package com.example

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import com.example.engine.AnalysisResult
import com.example.engine.CropFrame
import com.example.engine.EditPlan
import com.example.engine.ImageAnalyzer
import com.example.engine.cropBitmap
import com.example.db.AppDatabase
import com.example.db.EditHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import com.example.engine.v7.EditRecipe
import com.example.engine.v7.EnhancementOutcome
import com.example.engine.v7.OfflineEnhancer
import com.example.engine.manual.ManualAdjustments
import com.example.engine.manual.ManualPhotoEditor
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                FilterSlapApp()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Design tokens for the editor chrome (kept local; the photo stays the hero).
// ---------------------------------------------------------------------------
private val ScrimBlack = Color.Black
private val GlassSurface = Color(0xCC101418)
private val GlassStroke = Color(0x33FFFFFF)
private val Accent = Color(0xFFE8B44A)      // warm gold — the "pro lab" accent
private val TextPrimary = Color(0xFFF4F6F8)
private val TextSecondary = Color(0xFF9AA7B2)
private val DangerRed = Color(0xFFEF9A9A)
private val InfoBlue = Color(0xFF90CAF9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSlapApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }

    var originalUri by remember { mutableStateOf<Uri?>(null) }
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var automaticBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var manualAdjustments by remember { mutableStateOf(ManualAdjustments()) }
    var committedManual by remember { mutableStateOf(ManualAdjustments()) }
    var manualUndo by remember { mutableStateOf<List<ManualAdjustments>>(emptyList()) }
    var manualRedo by remember { mutableStateOf<List<ManualAdjustments>>(emptyList()) }
    var showManualEditor by remember { mutableStateOf(false) }
    var useAutoBase by remember { mutableStateOf(false) }

    fun commitManual() {
        if (manualAdjustments != committedManual) {
            manualUndo = (manualUndo + committedManual).takeLast(25)
            committedManual = manualAdjustments
            manualRedo = emptyList()
        }
    }
    fun resetManual() {
        commitManual()
        if (!manualAdjustments.isNeutral) {
            manualUndo = (manualUndo + manualAdjustments).takeLast(25)
            manualAdjustments = ManualAdjustments()
            committedManual = manualAdjustments
            manualRedo = emptyList()
        }
    }
    fun undoManual() {
        if (manualUndo.isNotEmpty()) {
            val previous = manualUndo.last()
            manualUndo = manualUndo.dropLast(1)
            manualRedo = manualRedo + manualAdjustments
            manualAdjustments = previous
            committedManual = previous
        }
    }
    fun redoManual() {
        if (manualRedo.isNotEmpty()) {
            val next = manualRedo.last()
            manualRedo = manualRedo.dropLast(1)
            manualUndo = (manualUndo + manualAdjustments).takeLast(25)
            manualAdjustments = next
            committedManual = next
        }
    }
    var analysisNotes by remember { mutableStateOf<List<String>>(emptyList()) }

    var isProcessing by remember { mutableStateOf(false) }
    var stageProgress by remember { mutableStateOf(0f) }
    var isSaving by remember { mutableStateOf(false) }
    var cachedAnalysis by remember { mutableStateOf<AnalysisResult?>(null) }
    var reportAnalysis by remember { mutableStateOf<AnalysisResult?>(null) }
    var activePlan by remember { mutableStateOf<EditPlan?>(null) }
    var activeRecipe by remember { mutableStateOf<EditRecipe?>(null) }
    var activeOutcome by remember { mutableStateOf<EnhancementOutcome?>(null) }
    var photoRequest by remember { mutableLongStateOf(0L) }
    var renderRequest by remember { mutableLongStateOf(0L) }

    var selectedCrop by remember { mutableStateOf(CropFrame.ORIGINAL) }

    var showAnalysis by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var isHolding by remember { mutableStateOf(false) }
    // F9: true between photo-pick and decoded-bitmap-arrival (decode + analysis on IO).
    var isDecoding by remember { mutableStateOf(false) }
    val historyItems by db.editHistoryDao().getAllHistory().collectAsState(initial = emptyList())

    var processingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var decodeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // The single, professional edit: analyze → decide → render. No presets, no filters.
    // The report and applied-edit notes are derived from the RENDERED plan (RenderResult),
    // never from a pre-evaluation — what the user reads is what the engine actually did.
    fun applyEdits(crop: CropFrame, og: Bitmap? = originalBitmap, analysis: AnalysisResult? = cachedAnalysis) {
        if (og == null || analysis == null) return
        processingJob?.cancel()
        val request = ++renderRequest
        val photo = photoRequest
        activeRecipe = null
        activeOutcome = null
        isProcessing = true
        processingJob = coroutineScope.launch(Dispatchers.Default) {
            val jobContext = coroutineContext
            val checkCancelled = { jobContext.ensureActive(); Unit }
            var cropped: Bitmap? = null
            var unpublished: Bitmap? = null
            try {
                val frame = cropBitmap(og, crop.aspectRatio)
                cropped = frame
                val frameAnalysis = if (crop.aspectRatio == null) analysis else ImageAnalyzer.analyze(frame, checkCancelled)
                val render = OfflineEnhancer.process(context, frame, frameAnalysis, checkCancelled)
                unpublished = render.bitmap
                checkCancelled()
                val plan = render.appliedPlan
                val outcome = render.outcome!!
                val details = plan.decisions.map { decision ->
                    "${decision.parameter}${if (decision.regionMask != "Global") " (${decision.regionMask})" else ""}"
                }
                val enriched = frameAnalysis.copy(report = frameAnalysis.report.copy(
                    toneAdjustments = plan.decisions.mapNotNull { decision -> when(decision.parameter) {
                        "AdaptiveColorTone" -> "Adaptive tone with highlight protection"
                        "LocalShadows" -> "Selective subject shadow lift"
                        "Shadows" -> "Global shadow lift"
                        "Exposure" -> "${decision.regionMask} exposure correction"
                        "Contrast" -> "${decision.regionMask} contrast correction"
                        "LocalToneRecovery" -> "Spatial shadow and local-light recovery"
                        else -> null
                    } }.distinct().joinToString(", ").ifEmpty { "Source tone retained" },
                    colorAdjustments = buildList {
                        if (outcome.modelStrength > 0f)
                            add("Adaptive color strength ${(outcome.modelStrength * 100).toInt()}%; natural-color protection")
                        if (plan.decisions.any { it.parameter == "WhiteBalance" })
                            add("Neutral-anchor white balance applied")
                    }.joinToString(", ").ifEmpty { "Source colors retained" }
                ))
                withContext(Dispatchers.Main) {
                    if (request == renderRequest && photo == photoRequest) {
                        automaticBitmap = render.bitmap
                        editedBitmap = render.bitmap
                        unpublished = null
                        activePlan = plan
                        activeRecipe = render.recipe
                        activeOutcome = outcome
                        reportAnalysis = enriched
                        analysisNotes = listOf(outcome.status) + details
                        stageProgress = 1f
                        isProcessing = false
                    }
                }
            } catch (_: CancellationException) {
                // The newest photo/crop owns the UI. Cancellation is never a fallback.
            } catch (oom: OutOfMemoryError) {
                withContext(Dispatchers.Main) {
                    if (request == renderRequest && photo == photoRequest) {
                        isProcessing = false
                        Toast.makeText(context, "Unable to enhance: insufficient memory", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    if (request == renderRequest && photo == photoRequest) {
                        isProcessing = false
                        Toast.makeText(context, "Unable to enhance: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                unpublished?.recycle()
                cropped?.let { if (it !== og && !it.isRecycled) it.recycle() }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { pickedUri ->
            processingJob?.cancel()
            decodeJob?.cancel()
            val request = ++photoRequest
            renderRequest++
            activeRecipe = null
            activeOutcome = null
            analysisNotes = emptyList()
            originalBitmap = null
            automaticBitmap = null
            editedBitmap = null
            manualAdjustments = ManualAdjustments()
            committedManual = manualAdjustments
            manualUndo = emptyList()
            manualRedo = emptyList()
            showManualEditor = false
            useAutoBase = false
            cachedAnalysis = null
            reportAnalysis = null
            activePlan = null
            isProcessing = true
            originalUri = pickedUri
            // Decoding and analysis are cancellable at their suspension boundary.
            isDecoding = true
            decodeJob = coroutineScope.launch(Dispatchers.IO) {
                val jobContext = coroutineContext
                var unpublished: Bitmap? = null
                try {
                    val bitmap = loadBitmapFromUri(context, pickedUri)
                    if (bitmap != null) {
                        unpublished = bitmap
                        val analysis = ImageAnalyzer.analyze(bitmap) { jobContext.ensureActive() }
                        // FIDELITY RULE: output dimensions and aspect ratio are LOCKED to the
                        // original unless the user explicitly picks a crop in the report.
                        // The analyzer's crop recommendation is a SUGGESTION shown there —
                        // never auto-applied.
                        withContext(Dispatchers.Main) {
                            if (photoRequest == request) {
                                isDecoding = false
                                originalBitmap = bitmap
                                unpublished = null
                                cachedAnalysis = analysis
                                reportAnalysis = analysis
                                selectedCrop = CropFrame.ORIGINAL
                                applyEdits(CropFrame.ORIGINAL, bitmap, analysis)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                          if (photoRequest == request) {
                            isProcessing = false
                            isDecoding = false
                            originalUri = null   // failed load must not leave a phantom source
                            Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                          }
                        }
                    }
                } catch (_: CancellationException) {
                    // No stale result/error is allowed to overwrite the new selection.
                } catch (oom: OutOfMemoryError) {
                    withContext(Dispatchers.Main) {
                        if (photoRequest == request) {
                            isProcessing = false
                            isDecoding = false
                            originalUri = null
                            Toast.makeText(context, "Unable to load: insufficient memory", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        if (photoRequest == request) {
                            isProcessing = false
                            isDecoding = false
                            originalUri = null
                            Toast.makeText(context, "Error loading image: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } finally { unpublished?.let { if (!it.isRecycled) it.recycle() } }
            }
        }
    }

    fun discard() {
        photoRequest++
        renderRequest++
        activeRecipe = null
        activeOutcome = null
        decodeJob?.cancel()
        processingJob?.cancel()
        isDecoding = false
        isProcessing = false
        isHolding = false
        originalBitmap = null
        automaticBitmap = null
        editedBitmap = null
        manualAdjustments = ManualAdjustments()
        committedManual = manualAdjustments
        manualUndo = emptyList()
        manualRedo = emptyList()
        showManualEditor = false
        useAutoBase = false
        originalUri = null
        cachedAnalysis = null
        reportAnalysis = null
        activePlan = null
        analysisNotes = emptyList()
    }

    // A save is a transaction. Never silently substitute the screen preview for a
    // failed export; a lower-resolution result must be explicitly requested by the user.
    // Work on an independently decoded bitmap; never recycle UI-owned bitmaps.
    fun performSave() {
        if (isSaving || isProcessing || editedBitmap == null) return
        val uri = originalUri ?: return
        val crop = selectedCrop
        val recipe = activeRecipe ?: return
        val analysis = reportAnalysis ?: return
        val manualForExport = manualAdjustments
        val autoForExport = useAutoBase
        var saveProfile = if(autoForExport) (activePlan?.profileName ?: "AUTO")
            else "MANUAL_CLASSICAL_NO_AI"
        val saveNotes = analysisNotes.toMutableList()
        isSaving = true
        coroutineScope.launch(Dispatchers.IO) {
            val jobContext = coroutineContext
            var savedUri: Uri? = null
            var outputSize = ""
            try {
                val source = loadFullResBitmapFromUri(context, uri)
                    ?: throw java.io.IOException("Unable to decode original photo")
                try {
                    val cropped = cropBitmap(source, crop.aspectRatio)
                    try {
                        val exportRender = if (autoForExport)
                            OfflineEnhancer.render(cropped, recipe, analysis, { jobContext.ensureActive() })
                            else null
                        val rendered = exportRender?.bitmap ?: cropped
                        var finalBitmap:Bitmap? = null
                        try {
                            finalBitmap = if(manualForExport.isNeutral) rendered
                                else ManualPhotoEditor.render(rendered,manualForExport) {
                                    jobContext.ensureActive()
                                }
                            outputSize = "${finalBitmap.width} × ${finalBitmap.height}"
                            saveNotes.clear()
                            if(exportRender!=null) {
                                saveProfile = exportRender.appliedPlan.profileName +
                                    if(manualForExport.isNeutral) "" else " + Manual"
                                saveNotes.add(exportRender.outcome!!.summary())
                                saveNotes.addAll(exportRender.appliedPlan.decisions.map { dec ->
                                    "${dec.parameter}${if(dec.regionMask!="Global") " (${dec.regionMask})" else ""}"
                                })
                            } else {
                                saveProfile="MANUAL_CLASSICAL_NO_AI"
                                saveNotes.add("Original-based classical manual editing")
                            }
                            if(!manualForExport.isNeutral)saveNotes.add("Manual adjustments")
                            savedUri=saveBitmapToGallery(context, finalBitmap)
                                ?: throw java.io.IOException("Gallery export failed")
                        } finally {
                            if(finalBitmap!=null && finalBitmap!==rendered &&
                                !finalBitmap.isRecycled)finalBitmap.recycle()
                            if(rendered!==cropped && !rendered.isRecycled)rendered.recycle()
                        }
                    } finally {
                        if (cropped !== source && !cropped.isRecycled) cropped.recycle()
                    }
                } finally {
                    if (!source.isRecycled) source.recycle()
                }
                val resultUri = savedUri ?: throw java.io.IOException("Gallery export failed")
                // History must not make a successfully saved photo appear to have failed.
                try {
                    db.editHistoryDao().insert(
                        EditHistory(
                            originalUri = uri.toString(),
                            savedUri = resultUri.toString(),
                            timestamp = System.currentTimeMillis(),
                            preset = saveProfile,
                            crop = crop.displayName,
                            summary = saveNotes.joinToString(" | ")
                        )
                    )
                    db.editHistoryDao().trimHistory()
                } catch (historyError: Exception) {
                    android.util.Log.w("FilterSlap", "Image saved but history insertion failed", historyError)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Gallery ($outputSize)", Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (oom: OutOfMemoryError) {
                // No automatic low-res fallback masquerading as the expected export.
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Not saved: photo exceeds available memory", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                android.util.Log.e("FilterSlap", "Failed to export photo", error)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Not saved: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    isSaving = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) performSave()
        else Toast.makeText(context, "Storage permission required to save", Toast.LENGTH_SHORT).show()
    }

    val comparisonBitmap = remember(originalBitmap, selectedCrop) {
        originalBitmap?.let { cropBitmap(it, selectedCrop.aspectRatio) }
    }
    // Preview and full-resolution export use original image unless Auto is opt-in.
    val previewBase = if(useAutoBase) automaticBitmap else comparisonBitmap
    LaunchedEffect(previewBase, automaticBitmap, manualAdjustments, photoRequest) {
        val baseline = previewBase ?: return@LaunchedEffect
        if (manualAdjustments.isNeutral) {
            val old=editedBitmap
            editedBitmap = baseline
            if(old != null && old !== baseline && old !== automaticBitmap &&
                old !== comparisonBitmap && old !== originalBitmap) {
                withFrameNanos { }
                withFrameNanos { }
                if(old !== editedBitmap && !old.isRecycled)old.recycle()
            }
            return@LaunchedEffect
        }
        delay(85)
        val settings = manualAdjustments
        val candidate = withContext(Dispatchers.Default) {
            val active = coroutineContext
            ManualPhotoEditor.render(baseline, settings) { active.ensureActive() }
        }
        try {
            coroutineContext.ensureActive()
            val old=editedBitmap
            editedBitmap = candidate
            if(old != null && old !== candidate && old !== automaticBitmap &&
                old !== comparisonBitmap && old !== originalBitmap) {
                withFrameNanos { }
                withFrameNanos { }
                if(old !== editedBitmap && !old.isRecycled)old.recycle()
            }
        } catch (cancelled: CancellationException) {
            candidate.recycle()
            throw cancelled
        }
    }

    // Hold to compare uses the original image at the same crop.

    if (showHistory) {
        HistorySheet(onDismiss = { showHistory = false }, historyItems = historyItems)
    }
    if (showAnalysis && reportAnalysis != null) {
        AnalysisSheet(
            onDismiss = { showAnalysis = false },
            analysis = reportAnalysis!!,
            notes = (if(useAutoBase) analysisNotes else listOf("Original photo used as editing base")) +
                manualAdjustments.editNotes(),
            plan = if(useAutoBase) activePlan else null,
            outcome = if(useAutoBase) activeOutcome else null,
            isAutoBase = useAutoBase,
            selectedCrop = selectedCrop,
            onCropChanged = { frame ->
                showAnalysis = false
                editedBitmap = null
                automaticBitmap = null
                activePlan = null
                activeRecipe = null
                activeOutcome = null
                reportAnalysis = null
                selectedCrop = frame
                applyEdits(frame)
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(ScrimBlack)) {
        if (originalBitmap == null) {
            WelcomeScreen(
                onOpenPhoto = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onShowHistory = { showHistory = true },
                hasHistory = historyItems.isNotEmpty()
            )
            // F9: decode + analysis feedback while the picked photo loads — the welcome
            // screen previously froze silently for seconds on large photos.
            if (isDecoding) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Loading photo…", color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        } else {
            EditorScreen(
                originalBitmap = comparisonBitmap,
                editedBitmap = editedBitmap,
                isProcessing = isProcessing,
                isHolding = isHolding,
                stageProgress = stageProgress,
                isSaving = isSaving,
                activePlan = activePlan,
                outcome = activeOutcome,
                hasAnalysis = reportAnalysis != null,
                hasManualEdits = !manualAdjustments.isNeutral,
                usingAutoBase = useAutoBase,
                editorOpen = showManualEditor,
                onOpenEditor = { showManualEditor = true },
                onHold = { holding -> isHolding = holding },
                onDiscard = ::discard,
                onShowAnalysis = { showAnalysis = true },
                onSave = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        performSave()
                    }
                }
            )
            if (showManualEditor) {
                ManualEditorTray(
                    values = manualAdjustments,
                    enabled = !isProcessing && !isSaving,
                    useAutoBase = useAutoBase,
                    onAutoBaseChanged = { useAutoBase = it },
                    selectedCrop = selectedCrop,
                    onCropChanged = { frame ->
                        editedBitmap = null
                        automaticBitmap = null
                        activePlan = null
                        activeRecipe = null
                        activeOutcome = null
                        reportAnalysis = null
                        selectedCrop = frame
                        applyEdits(frame)
                    },
                    canUndo = manualUndo.isNotEmpty(),
                    canRedo = manualRedo.isNotEmpty(),
                    onChange = { manualAdjustments = it },
                    onChangeFinished = ::commitManual,
                    onUndo = ::undoManual,
                    onRedo = ::redoManual,
                    onReset = ::resetManual,
                    onDismiss = { commitManual(); showManualEditor = false },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// WELCOME — the app's promise in one screen: bring a photo, get a pro edit.
// ---------------------------------------------------------------------------
@Composable
private fun WelcomeScreen(onOpenPhoto: () -> Unit, onShowHistory: () -> Unit, hasHistory: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0D10), Color(0xFF14181D), Color(0xFF0A0D10)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Filter Slap",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your photo, professionally edited —\nautomatically. No filters to choose.",
                fontSize = 15.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(44.dp))
            Button(
                onClick = onOpenPhoto,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1A1205)),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth(0.62f)
            ) {
                Text("Choose a Photo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (hasHistory) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onShowHistory) {
                    Text("Edit History", fontSize = 14.sp, color = TextSecondary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// EDITOR — photo is the hero; chrome floats over it in glass.
// ---------------------------------------------------------------------------
@Composable
private fun EditorScreen(
    originalBitmap: Bitmap?,
    editedBitmap: Bitmap?,
    isProcessing: Boolean,
    isHolding: Boolean,
    stageProgress: Float,
    isSaving: Boolean,
    activePlan: EditPlan?,
    outcome: EnhancementOutcome?,
    hasAnalysis: Boolean,
    hasManualEdits: Boolean,
    usingAutoBase: Boolean,
    editorOpen: Boolean,
    onOpenEditor: () -> Unit,
    onHold: (Boolean) -> Unit,
    onDiscard: () -> Unit,
    onShowAnalysis: () -> Unit,
    onSave: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(ScrimBlack)) {
        // Image with hold-to-compare.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onHold(true)
                            tryAwaitRelease()
                            onHold(false)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val displayBitmap = if (isHolding) originalBitmap else (editedBitmap ?: originalBitmap)
            displayBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Edited photo preview",
                    modifier = Modifier.fillMaxSize()
                        .padding(bottom = if(editorOpen) 285.dp else 0.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        val holdingAlpha by animateFloatAsState(if (isHolding) 1f else 0f)
        if (holdingAlpha > 0.01f) {
            PillLabel(
                text = "ORIGINAL",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .graphicsLayer { alpha = holdingAlpha }
            )
        } else if (editedBitmap != null && !isProcessing) {
            PillLabel(
                text = if (hasManualEdits) "MANUAL EDIT" else
                    if(usingAutoBase) (outcome?.status?.uppercase() ?: "AUTO CORRECTION")
                    else "ORIGINAL · READY TO EDIT",
                tint = Accent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp)
            )
        }

        // Processing indicator.
        val processingAlpha by animateFloatAsState(if (isProcessing) 1f else 0f)
        if (processingAlpha > 0.01f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = processingAlpha }
            ) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(14.dp))
                Text("Analyzing & grading…", color = TextPrimary, fontSize = 14.sp)
            }
        }

        // Top bar: discard + report.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GlassIconButton(onClick = onDiscard, contentDescription = "Discard") {
                Icon(Icons.Default.Close, contentDescription = "Discard", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
            if (hasAnalysis) {
                GlassIconButton(onClick = onShowAnalysis, contentDescription = "Analysis report") {
                    Icon(Icons.Default.Info, contentDescription = "Analysis report", tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Bottom bar: editing controls replace actions while open.
        if (!editorOpen) Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            activePlan?.let { plan ->
                Text(
                    if(hasManualEdits) "Your edit" else
                        if(usingAutoBase) (outcome?.status ?: "Auto correction")
                        else "Original · no automatic changes",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Hold photo to compare — release to see the edit",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenEditor,
                    enabled = !isSaving && !isProcessing && editedBitmap != null,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor=Accent),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.height(52.dp).weight(1f)
                ) {
                    Text(if (hasManualEdits) "Adjust · Edited" else "Edit Photo",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onSave,
                    enabled = !isSaving && !isProcessing && editedBitmap != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent, contentColor = Color(0xFF1A1205),
                        disabledContainerColor = Accent.copy(alpha = 0.3f),
                        disabledContentColor = Color(0xFF1A1205).copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.height(52.dp).weight(1f)
                ) {
                    if (isSaving) CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), color = Color(0xFF1A1205),
                        strokeWidth = 2.dp
                    )
                    else Text("Save Photo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Real photo editor controls. The photo stays visible until this compact
 * panel is opened; all changes are reversible and rendered offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualEditorTray(
    values: ManualAdjustments,
    enabled: Boolean,
    useAutoBase: Boolean,
    onAutoBaseChanged: (Boolean) -> Unit,
    selectedCrop: CropFrame,
    onCropChanged: (CropFrame) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onChange: (ManualAdjustments) -> Unit,
    onChangeFinished: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeControl by remember { mutableStateOf("Exposure") }
    val controls = listOf(
        ManualTool("Exposure",values.exposure,-2f..2f,"EV"){values.copy(exposure=it)},
        ManualTool("Contrast",values.contrast,-100f..100f){values.copy(contrast=it)},
        ManualTool("Highlights",values.highlights,-100f..100f){values.copy(highlights=it)},
        ManualTool("Shadows",values.shadows,-100f..100f){values.copy(shadows=it)},
        ManualTool("Whites",values.whites,-100f..100f){values.copy(whites=it)},
        ManualTool("Blacks",values.blacks,-100f..100f){values.copy(blacks=it)},
        ManualTool("Warmth",values.temperature,-100f..100f){values.copy(temperature=it)},
        ManualTool("Tint",values.tint,-100f..100f){values.copy(tint=it)},
        ManualTool("Vibrance",values.vibrance,-100f..100f){values.copy(vibrance=it)},
        ManualTool("Saturation",values.saturation,-100f..100f){values.copy(saturation=it)},
        ManualTool("Vignette",values.vignette,0f..100f){values.copy(vignette=it)}
    )
    val selected = controls.firstOrNull { it.name==activeControl } ?: controls.first()
    Column(
        modifier=modifier.fillMaxWidth()
            .background(Color(0xFF12161A),
                RoundedCornerShape(topStart=24.dp,topEnd=24.dp))
            .border(1.dp,GlassStroke,
                RoundedCornerShape(topStart=24.dp,topEnd=24.dp))
            .navigationBarsPadding()
            .padding(horizontal=16.dp,vertical=9.dp)
    ) {
        Row(modifier=Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Edit",fontSize=17.sp,fontWeight=FontWeight.Bold,color=TextPrimary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick=onUndo,enabled=enabled && canUndo,contentPadding=PaddingValues(horizontal=7.dp)) {
                Text("Undo",fontSize=12.sp,color=if(canUndo)Accent else TextSecondary)
            }
            TextButton(onClick=onRedo,enabled=enabled && canRedo,contentPadding=PaddingValues(horizontal=7.dp)) {
                Text("Redo",fontSize=12.sp,color=if(canRedo)Accent else TextSecondary)
            }
            TextButton(onClick=onReset,enabled=enabled && !values.isNeutral,contentPadding=PaddingValues(horizontal=7.dp)) {
                Text("Reset",fontSize=12.sp,color=if(!values.isNeutral)Accent else TextSecondary)
            }
            TextButton(onClick=onDismiss,contentPadding=PaddingValues(horizontal=7.dp)) {
                Text("Done",fontSize=13.sp,color=Accent,fontWeight=FontWeight.SemiBold)
            }
        }
        Row(
            modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            FilterChip(selected=!useAutoBase,onClick={onAutoBaseChanged(false)},enabled=enabled,
                label={Text("Original",fontSize=12.sp)},
                colors=FilterChipDefaults.filterChipColors(
                    selectedContainerColor=Accent,selectedLabelColor=Color(0xFF1A1205)))
            FilterChip(selected=useAutoBase,onClick={onAutoBaseChanged(true)},enabled=enabled,
                label={Text("Auto base",fontSize=12.sp)},
                colors=FilterChipDefaults.filterChipColors(
                    selectedContainerColor=Accent,selectedLabelColor=Color(0xFF1A1205)))
            CropFrame.entries.forEach { frame ->
                FilterChip(selected=frame==selectedCrop,
                    onClick={if(frame!=selectedCrop)onCropChanged(frame)},enabled=enabled,
                    label={Text(frame.displayName,fontSize=11.sp)},
                    colors=FilterChipDefaults.filterChipColors(
                        selectedContainerColor=Color(0xFF3B4B48),
                        selectedLabelColor=TextPrimary))
            }
        }
        Row(
            modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            controls.forEach { control ->
                FilterChip(
                    selected=control.name==activeControl,
                    onClick={activeControl=control.name},
                    label={Text(control.name,fontSize=12.sp)},
                    colors=FilterChipDefaults.filterChipColors(
                        selectedContainerColor=Accent,selectedLabelColor=Color(0xFF1A1205),
                        containerColor=Color(0xFF202830),labelColor=TextSecondary),
                    border=FilterChipDefaults.filterChipBorder(enabled=true,selected=control.name==activeControl,
                        borderColor=GlassStroke,selectedBorderColor=Color.Transparent)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ManualControl(selected.name,selected.value,selected.range,enabled,
            selected.suffix,onValueChange={onChange(selected.change(it))},
            onFinished=onChangeFinished)
        Text("Hold the photo to compare with the original",
            modifier=Modifier.align(Alignment.CenterHorizontally),
            color=TextSecondary,fontSize=11.sp)
    }
}

private data class ManualTool(
    val name:String,
    val value:Float,
    val range:ClosedFloatingPointRange<Float>,
    val suffix:String="",
    val change:(Float)->ManualAdjustments
)

@Composable
private fun ManualControl(
    title: String, value:Float, range: ClosedFloatingPointRange<Float>,
    enabled:Boolean, suffix:String="", onValueChange:(Float)->Unit, onFinished:()->Unit
) {
    Row(modifier=Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(title,fontSize=13.sp,color=TextPrimary,modifier=Modifier.weight(1f))
        val valueLabel = if(suffix=="EV") "%+.2f EV".format(value)
            else "%+.0f".format(value)
        Text(valueLabel,fontSize=13.sp,color=if(value==0f)TextSecondary else Accent)
    }
    Slider(
        value=value,
        onValueChange=onValueChange,
        onValueChangeFinished=onFinished,
        valueRange=range,
        enabled=enabled,
        modifier=Modifier.fillMaxWidth().height(37.dp),
        colors=SliderDefaults.colors(
            thumbColor=Accent,
            activeTrackColor=Accent,
            inactiveTrackColor=Color(0xFF414B54)
        )
    )
}

@Composable
private fun PillLabel(text: String, tint: Color = TextPrimary, modifier: Modifier = Modifier) {
    Text(
        text,
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = modifier
            .background(GlassSurface, RoundedCornerShape(50))
            .border(0.5.dp, GlassStroke, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .background(GlassSurface, CircleShape)
            .border(0.5.dp, GlassStroke, CircleShape)
    ) {
        content()
    }
}

// Status/navigation bar padding via the standard foundation.layout extensions
// (imported through foundation.layout.* wildcard).


// ---------------------------------------------------------------------------
// REPORT SHEET — what the engine saw, decided, and did.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisSheet(
    onDismiss: () -> Unit,
    analysis: AnalysisResult,
    notes: List<String>,
    plan: EditPlan?,
    outcome: EnhancementOutcome?,
    isAutoBase: Boolean,
    selectedCrop: CropFrame,
    onCropChanged: (CropFrame) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12161A),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
        ) {
            Text("Edit Report", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            plan?.let { p ->
                Spacer(Modifier.height(10.dp))
                Text(p.profileReason, fontSize = 13.sp, color = Accent, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Reported percentages are requested edit strengths, not measured percentages of defects removed.",
                fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
            Spacer(Modifier.height(20.dp))

            ReportSection("SCENE") {
                ReportRow("Category", analysis.report.sceneCategory)
                ReportRow("Subject", analysis.report.primarySubject)
                ReportRow("Composition", analysis.report.composition)
                analysis.report.perspectiveCorrection?.let {
                    ReportRow("Edge orientation (estimate)", it)
                }
            }
            ReportSection("LIGHT & TONE") {
                ReportRow("Lighting", analysis.report.lighting)
                ReportRow("Dynamic range", analysis.report.dynamicRange)
                ReportRow("Exposure bias", analysis.report.histogramBias)
                ReportRow("Tone action", if(isAutoBase)analysis.report.toneAdjustments.ifEmpty { "—" }
                    else "Manual only; no automatic corrections",valueTint=InfoBlue)
            }
            ReportSection("COLOR") {
                ReportRow("Temperature", analysis.report.colorTemperature)
                ReportRow("Color action", if(isAutoBase)analysis.report.colorAdjustments.ifEmpty { "—" }
                    else "Manual only; source colors retained until edited",valueTint=InfoBlue)
            }
            ReportSection("LENS") {
                ReportRow("Vignette", when {
                    plan?.decisions?.any { it.parameter == "Vignette" } == true -> "Correction applied"
                    analysis.lensQuality.vignette > 0.15f -> "Detected; correction withheld"
                    else -> "No correction needed"
                })
                ReportRow("Fringing", when {
                    plan?.decisions?.any { it.parameter == "CA" } == true -> "Correction applied"
                    analysis.lensQuality.chromaticAberration > 0.2f -> "Detected; correction withheld"
                    else -> "No correction needed"
                })
            }
            ReportSection("APPLIED EDITS") {
                if (notes.isEmpty()) {
                    Text("Original retained after review.", fontSize = 13.sp, color = TextSecondary)
                } else {
                    notes.forEach { note ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("•  ", color = Accent)
                            Text(note, fontSize = 13.sp, color = TextPrimary)
                        }
                    }
                }
            }
            ReportSection("DIAGNOSTICS") {
                ReportRow("Build", BuildConfig.VERSION_NAME + " · " + BuildConfig.SOURCE_REVISION)
                if(!isAutoBase) {
                    ReportRow("Engine","MANUAL_CLASSICAL_NO_AI")
                    ReportRow("Scope","User controlled, full resolution at export")
                    ReportRow("Base","Unmodified original at chosen crop")
                }
                outcome?.let { result ->
                    ReportRow("Engine", result.engine)
                    ReportRow("Result", result.status)
                    ReportRow("Recipe", result.recipeId)
                    ReportRow("Processing", "Classical offline image processing; no AI")
                    ReportRow("Scope", "Whole photo; guided local light where supported")
                    ReportRow("Mean RGB change", "%.2f / 255".format(result.damage.meanChange))
                    ReportRow("New clipping", "%.3f%%".format(result.damage.newlyClipped * 100))
                    result.reasons.forEach { ReportRow("Processing", it) }
                }
            }
            ReportSection("FRAME") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CropFrame.entries.forEach { frame ->
                            FilterChip(
                                selected = frame == selectedCrop,
                                onClick = { if (frame != selectedCrop) onCropChanged(frame) },
                                label = { Text(frame.displayName, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color(0xFF1C2228),
                                    labelColor = TextSecondary,
                                    selectedContainerColor = Accent,
                                    selectedLabelColor = Color(0xFF1A1205)
                                )
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun ReportSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Accent, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    Column(content = content)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ReportRow(label: String, value: String, valueTint: Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, color = valueTint, textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(0.68f))
    }
}

// ---------------------------------------------------------------------------
// HISTORY SHEET
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(onDismiss: () -> Unit, historyItems: List<EditHistory>) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12161A),
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 40.dp)
        ) {
            Text("Edit History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(14.dp))
            if (historyItems.isEmpty()) {
                Text("No edits yet.", color = TextSecondary)
            } else {
                historyItems.forEach { item ->
                    val date = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(date, fontSize = 12.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                        Text(item.summary, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
                        HorizontalDivider(color = Color(0x11FFFFFF), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Save helpers (full-res render lives in performSave; gallery write below).
// ---------------------------------------------------------------------------

private fun toneProse(parameter: String, magnitude: Float): String = when (parameter) {
    "Exposure" -> if (magnitude > 0) "Exposure lifted ${percent(magnitude)} to recover underexposure" else "Exposure eased ${percent(-magnitude)} to tame overexposure"
    "Contrast" -> if (magnitude >= 0) "Contrast deepened ${percent(magnitude)} (adaptive S-curve)" else "Background contrast eased ${percent(-magnitude)} for subject separation"
    "Shadows" -> "Shadows opened ${percent(magnitude)} to reveal detail"
    "Highlights" -> "Highlights recovered ${percent(magnitude)}"
    "Blacks" -> "Blacks deepened ${percent(magnitude)} toward true black point"
    "Whites" -> "Whites stretched ${percent(magnitude)} toward true white point"
    "Dehaze" -> "Haze lifted ${percent(magnitude)}"
    "Vignette" -> "Corner darkening corrected ${percent(magnitude)}"
    "CA" -> "Localized chroma-fringe correction (strength ${percent(magnitude)}; not measured removal)"
    else -> parameter
}

private fun colorProse(parameter: String, magnitude: Float): String = when (parameter) {
    "WhiteBalance" -> if (magnitude < 0) "Warm cast cooled ${percent(-magnitude)}" else "Cool cast warmed ${percent(magnitude)}"
    "WhiteBalanceTint" -> if (magnitude < 0) "Green cast neutralized ${percent(-magnitude)}" else "Magenta cast neutralized ${percent(magnitude)}"
    "Saturation" -> "Saturation lifted ${percent(magnitude)}"
    "Vibrance" -> if (magnitude >= 0) "Vibrance lifted ${percent(magnitude)} (muted colors first, skin protected)" else "Background vibrance eased ${percent(-magnitude)} — subject separation"
    "SplitToneShadows" -> "Shadows graded ${percent(magnitude)} cool (split-tone depth)"
    "SplitToneHighlights" -> "Highlights graded ${percent(magnitude)} warm (split-tone depth)"
    "SkyHue" -> "Sky hue trued ${"${magnitude.toInt()}°"} toward natural blue"
    "VegHue" -> "Foliage hue trued ${"${magnitude.toInt()}°"} toward natural green"
    else -> parameter
}

private fun percent(mag: Float): String = "${(mag * 100).toInt()}%"


// Android photo renders can allocate >40 bytes per pixel across bitmaps, color
// planes and frequency buffers. Bound export by BOTH device heap and dimensions;
// Export is bounded to the hardware-safe image budget. Never substitute the
// 2048px screen preview on failure; communicate actual export dimensions.
private const val FULL_RES_MAX_DIM = 4096
private fun maxSafePixels(): Long =
    (Runtime.getRuntime().maxMemory() / 72L).coerceIn(1_000_000L, 12_000_000L)

private fun loadFullResBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        // Pass 1: bounds only, to compute the sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        val longSide = max(bounds.outWidth, bounds.outHeight)
        val safePixels = maxSafePixels()
        while (longSide / inSampleSize > FULL_RES_MAX_DIM ||
            (bounds.outWidth.toLong() / inSampleSize) * (bounds.outHeight.toLong() / inSampleSize) > safePixels) {
            inSampleSize *= 2
        }

        // Pass 2: decode at (or under) the cap.
        val options = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (Build.VERSION.SDK_INT >= 26) inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        }
        var bitmap = context.contentResolver.openInputStream(uri)?.let { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        // Stay within the same memory budget after decoder-specific rounding.
        val decodedLong = max(bitmap.width, bitmap.height)
        if (decodedLong > FULL_RES_MAX_DIM || bitmap.width.toLong() * bitmap.height > safePixels) {
            val scale = minOf(FULL_RES_MAX_DIM.toDouble() / decodedLong,
                kotlin.math.sqrt(safePixels.toDouble() / (bitmap.width.toLong() * bitmap.height))).toFloat()
            val scaled = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.postRotate(180f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
        }

        if (matrix.isIdentity) bitmap
        else {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        }
    } catch (e: Exception) {
        e.printStackTrace(); null
    }
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        var inSampleSize = 1
        val reqMaxDim = 2048
        val maxDim = kotlin.math.max(options.outWidth, options.outHeight)
        val previewPixels = minOf(3_000_000L, maxSafePixels())

        if (maxDim > reqMaxDim) {
            val ratio = Math.round(maxDim.toFloat() / reqMaxDim.toFloat())
            inSampleSize = Integer.highestOneBit(ratio)
            if (inSampleSize < 1) inSampleSize = 1
            while (maxDim / inSampleSize > reqMaxDim ||
                (options.outWidth.toLong() / inSampleSize) * (options.outHeight.toLong() / inSampleSize) > previewPixels)
                inSampleSize *= 2
        }

        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        if (Build.VERSION.SDK_INT >= 26) options.inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val currentMaxDim = kotlin.math.max(bitmap.width, bitmap.height)
        if (currentMaxDim > reqMaxDim || bitmap.width.toLong() * bitmap.height > previewPixels) {
            val scale = minOf(reqMaxDim.toDouble() / currentMaxDim,
                kotlin.math.sqrt(previewPixels.toDouble() / (bitmap.width.toLong() * bitmap.height))).toFloat()
            val scaled = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled }
        }

        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.postRotate(180f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
        }

        if (matrix.isIdentity) bitmap
        else {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        }
    } catch (e: Exception) {
        e.printStackTrace(); null
    }
}

internal suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Uri? {
    return withContext(Dispatchers.IO) {
        val filename = "AutoEdit_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val contentResolver = context.contentResolver
        var imageUri: Uri? = null
        try {
            val insertedUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            imageUri = insertedUri
            if (insertedUri != null) {
                val stream = contentResolver.openOutputStream(insertedUri)
                    ?: throw java.io.IOException("Cannot open gallery output stream")
                stream.use {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) {
                        throw java.io.IOException("JPEG encoder failed")
                    }
                }
                contentValues.clear()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    contentResolver.update(insertedUri, contentValues, null, null) < 1) {
                    throw java.io.IOException("Could not finalize gallery image")
                }

                insertedUri
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            imageUri?.let { contentResolver.delete(it, null, null) }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }
}
