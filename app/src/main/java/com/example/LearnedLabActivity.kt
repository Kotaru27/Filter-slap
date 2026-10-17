package com.example

import android.net.Uri
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.engine.learned.LearnedOfflineCandidate
import com.example.engine.learned.UserModelStore
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only offline ONNX evaluator. NEVER replaces the proven V9 editor
 * automatically; a future release needs an approved dataset/model and blind
 * photographic review. A separate screen protects the normal gallery path.
 */
class LearnedLabActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(!BuildConfig.DEBUG){finish();return}
        setContent {MyApplicationTheme(darkTheme=true){OfflineLearnedLab(onClose={finish()})}}
    }
}

@Composable
private fun OfflineLearnedLab(onClose:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var model by remember {mutableStateOf(UserModelStore.loadIfVerified(context))}
    var selectedUri by remember {mutableStateOf<Uri?>(null)}
    var source by remember {mutableStateOf<Bitmap?>(null)}
    var learned by remember {mutableStateOf<Bitmap?>(null)}
    var strength by remember {mutableStateOf<Float?>(null)}
    var busy by remember {mutableStateOf(false)}
    var saving by remember {mutableStateOf(false)}
    var holding by remember {mutableStateOf(false)}
    var problem by remember {mutableStateOf<String?>(null)}
    var generation by remember {mutableIntStateOf(0)}
    var worker by remember {mutableStateOf<Job?>(null)}

    val modelPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && !busy && !saving) {
            val request=++generation
            worker?.cancel()
            learned=null;strength=null;busy=true;problem=null
            worker=scope.launch(Dispatchers.IO){
                try {
                    val imported=UserModelStore.importFromDocument(context,uri)
                    withContext(Dispatchers.Main){
                        if(request==generation){model=imported;busy=false}
                    }
                }catch(e:Exception){
                    withContext(Dispatchers.Main){
                        if(request==generation){busy=false;problem="Model import: ${e.message}"}
                    }
                }
            }
        }
    }
    val photoPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()){uri->
        if(uri!=null && !saving) {
            val request=++generation
            worker?.cancel()
            selectedUri=uri
            learned=null;strength=null;source=null;busy=true;problem=null
            worker=scope.launch(Dispatchers.IO) {
                try {
                    val decoded=loadBitmapFromUri(context,uri)
                        ?: throw java.io.IOException("Unable to decode the photo")
                    withContext(Dispatchers.Main){
                        if(request==generation){source=decoded;busy=false}
                        else if(!decoded.isRecycled)decoded.recycle()
                    }
                }catch(e:Exception){
                    withContext(Dispatchers.Main){
                        if(request==generation){problem="Photo: ${e.message}";busy=false}
                    }
                }
            }
        }
    }

    fun preview() {
        val photo=source ?:return
        val predictor=model ?:return
        if(busy||saving)return
        val request=++generation
        worker?.cancel()
        learned=null;strength=null;busy=true;problem=null
        worker=scope.launch(Dispatchers.Default){
            val current=coroutineContext
            var orphan:Bitmap?=null
            try {
                val result=LearnedOfflineCandidate.previewApprovedStrengthSearchBytes(
                    photo,predictor.bytes,predictor.sha256,"global",
                    {current.ensureActive()})
                if(result==null)throw java.io.IOException(
                    "No strength passed the original V9 damage limits")
                orphan=result.preview.bitmap
                withContext(Dispatchers.Main) {
                    if(request==generation){
                        learned=result.preview.bitmap
                        strength=result.strength
                        orphan=null
                        busy=false
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){
                withContext(Dispatchers.Main) {
                    if(request==generation){problem="AI render: ${e.message}";busy=false}
                }
            }finally{orphan?.let{if(!it.isRecycled)it.recycle()}}
        }
    }

    fun save() {
        val photoUri=selectedUri ?:return
        val approved=model ?:return
        val selectedStrength=strength ?:return
        if(busy||saving||learned==null)return
        val request=++generation
        saving=true;problem=null
        scope.launch(Dispatchers.IO) {
            var full:Bitmap?=null
            var exported:Bitmap?=null
            try {
                val verified=UserModelStore.loadIfVerified(context)
                    ?:throw java.io.IOException("Model is missing or checksum failed")
                if(verified.sha256!=approved.sha256)
                    throw java.io.IOException("Model changed since preview")
                full=loadFullResBitmapFromUri(context,photoUri)
                    ?:throw java.io.IOException("Unable to decode original resolution")
                val check={coroutineContext.ensureActive();Unit}
                val result=LearnedOfflineCandidate.previewApprovedFixedStrengthBytes(
                    full,verified.bytes,verified.sha256,selectedStrength,"global",check)
                    ?:throw java.io.IOException(
                        "AI edit unsafe at export size; no file was saved")
                exported=result.bitmap
                val saved=saveBitmapToGallery(context,result.bitmap)
                    ?:throw java.io.IOException("Failed to write Gallery copy")
                withContext(Dispatchers.Main){
                    if(request==generation){
                        problem=null
                        Toast.makeText(context,
                            "Experimental AI copy saved (${result.bitmap.width} × ${result.bitmap.height})",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception) {
                withContext(Dispatchers.Main){
                    if(request==generation)problem="Export cancelled: ${e.message}"
                }
            }finally{
                exported?.let{if(!it.isRecycled)it.recycle()}
                full?.let{if(!it.isRecycled)it.recycle()}
                withContext(kotlinx.coroutines.NonCancellable+Dispatchers.Main){saving=false}
            }
        }
    }

    // NO model weights or training photographs ship with this APK.
    val amber=Color(0xFFE8B44A)
    Column(modifier=Modifier.fillMaxSize().background(Color(0xFF0B0E11))
        .statusBarsPadding().navigationBarsPadding()
        .padding(18.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Row(modifier=Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
            verticalAlignment=Alignment.CenterVertically){
            Text("Offline AI Lab",style=MaterialTheme.typography.titleLarge,color=Color.White)
            TextButton(onClick=onClose){Text("Close",color=amber)}
        }
        Text("Experimental testing only • no bundled trained model",
            style=MaterialTheme.typography.bodySmall,color=Color.LightGray)
        Spacer(Modifier.height(12.dp))
        Text("Import only a model you are permitted to use. Compatible: 256×256 global learned-photo ONNX predictor. Everything runs on-device.",
            textAlign=TextAlign.Center,color=Color(0xFFABB3BC),
            style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        Button(onClick={modelPicker.launch(arrayOf("*/*"))},enabled=!busy&&!saving,
            colors=ButtonDefaults.buttonColors(containerColor=amber)) {
            Text(if(model==null)"Import local ONNX model" else "Replace ONNX model",
                color=Color.Black)
        }
        model?.let {
            Text("Verified model: ${it.sha256.take(16)}…",
                style=MaterialTheme.typography.bodySmall,color=Color.LightGray)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick={
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },enabled=!busy&&!saving){Text("Choose photo")}
        val display=if(!holding)learned?:source else source
        Box(modifier=Modifier.fillMaxWidth().heightIn(min=260.dp).height(370.dp)
            .background(Color(0xFF151B20),RoundedCornerShape(14.dp))
            .pointerInput(source,learned){
                detectTapGestures(onPress={
                    holding=true
                    tryAwaitRelease()
                    holding=false
                })
            },contentAlignment=Alignment.Center) {
            if(display!=null)Image(display.asImageBitmap(),contentDescription="Photo comparison",
                modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else Text("Choose a photograph to begin",color=Color.LightGray)
            if(busy)CircularProgressIndicator(color=amber)
        }
        Spacer(Modifier.height(10.dp))
        strength?.let {
            Text("AI preview · strongest safe strength: ${(it*100).toInt()}%",
                color=amber,style=MaterialTheme.typography.bodyMedium)
            Text("Hold the photo to compare the original",
                color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
        }
        problem?.let{
            Text(it,color=Color(0xFFFFA8A8),style=MaterialTheme.typography.bodySmall,
                textAlign=TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick=::preview,enabled=model!=null&&source!=null&&!busy&&!saving,
            modifier=Modifier.fillMaxWidth()) {Text("Test this AI on photo")}
        Button(onClick=::save,enabled=learned!=null&&strength!=null&&!busy&&!saving,
            modifier=Modifier.fillMaxWidth(),
            colors=ButtonDefaults.buttonColors(containerColor=amber)) {
            Text(if(saving)"Saving…" else "Save full-resolution AI copy",
                color=Color.Black)
        }
        Text("AI output is independently reviewed at export resolution. If the saved-size result fails safety checks, nothing is saved. Use the regular editor for the standard V9.3 enhancement.",
            textAlign=TextAlign.Center,color=Color(0xFF9BA6AF),
            style=MaterialTheme.typography.labelSmall)
    }
}
