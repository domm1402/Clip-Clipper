package com.domm.clipclipper

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ClipMoment(val start: Double, val end: Double, val title: String, val reason: String)

class MainActivity : ComponentActivity() {
 private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).build()

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { Screen() }
 }

 @Composable private fun Screen() {
  var uri by remember { mutableStateOf<Uri?>(null) }
  var videoName by remember { mutableStateOf("No video selected") }
  var key by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("Choose a video to discover its best moments.") }
  var busy by remember { mutableStateOf(false) }
  var moments by remember { mutableStateOf(listOf<ClipMoment>()) }
  var outputs by remember { mutableStateOf(listOf<String>()) }
  val scope = rememberCoroutineScope()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
   if (picked != null) {
    contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    uri = picked
    videoName = picked.lastPathSegment?.substringAfterLast('/') ?: "Selected video"
    moments = emptyList()
    status = "Video ready. Add an OpenAI API key to analyse it."
   }
  }
  val accent = Color(0xFF9585FF)
  MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = Color(0xFF0B0D12), surface = Color(0xFF171B25))) {
   Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0D12)) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
     item {
      Row(verticalAlignment = Alignment.CenterVertically) {
       Icon(Icons.Default.ContentCut, null, tint = accent, modifier = Modifier.size(34.dp))
       Spacer(Modifier.width(10.dp))
       Column {
        Text("CLIP CLIPPER", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text("AI finds the moment. You make it viral.", color = Color.LightGray)
       }
      }
     }
     item {
      Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B25)), shape = RoundedCornerShape(20.dp)) {
       Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("1. Choose your video", fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
         Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text("Select video")
        }
        Text(videoName, color = Color.LightGray)
       }
      }
     }
     item {
      Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B25)), shape = RoundedCornerShape(20.dp)) {
       Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("2. Let ChatGPT find the highlights", fontWeight = FontWeight.Bold)
        Text("OpenAI API access is required. API usage can cost money and is separate from ChatGPT subscriptions.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("OpenAI API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
         val selected = uri
         if (selected == null) { status = "Select a video first."; return@Button }
         if (!key.startsWith("sk-")) { status = "Enter your OpenAI API key."; return@Button }
         busy = true; status = "Transcribing audio and finding standout moments…"
         scope.launch {
          try {
           moments = analyse(selected, key)
           status = if (moments.isEmpty()) "No suitable moments found in this video." else "Found ${moments.size} moments. Choose which clips to render."
          } catch (e: Exception) { status = "Analysis failed: ${e.message}" }
          finally { busy = false }
         }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
         if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Find viral moments")
        }
       }
      }
     }
     item { Text(status, color = if (status.startsWith("Analysis failed")) Color(0xFFFF9292) else Color(0xFFB9B2FF)) }
     if (moments.isNotEmpty()) {
      item { Text("3. AI-selected moments", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
      items(moments) { m ->
       Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171B25))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
         Text(m.title, fontWeight = FontWeight.Bold)
         Text("${stamp(m.start)} – ${stamp(m.end)}", color = accent)
         Text(m.reason, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
         Button(onClick = {
          val selected = uri ?: return@Button
          busy = true; status = "Rendering ${m.title}…"
          scope.launch {
           try { val file = render(selected, m); outputs = outputs + file; status = "Clip rendered successfully." }
           catch (e: Exception) { status = "Render failed: ${e.message}" }
           finally { busy = false }
          }
         }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cut and save clip") }
        }
       }
      }
     }
     if (outputs.isNotEmpty()) {
      item { Text("Your clips", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
      items(outputs) { path ->
       Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(File(path).name, color = Color.White)
        IconButton(onClick = {
         val share = Intent(Intent.ACTION_SEND).apply {
          type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", File(path)))
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
         }
         startActivity(Intent.createChooser(share, "Share clip"))
        }) { Icon(Icons.Default.Share, "Share", tint = accent) }
       }
      }
     }
     item { Text("Audio is sent to OpenAI for transcription. Video cutting happens locally on your phone. Share exported clips to TikTok using Android's share menu.", color = Color.Gray, style = MaterialTheme.typography.labelSmall) }
    }
   }
  }
 }

 private fun stamp(t: Double): String = "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)

 private suspend fun analyse(uri: Uri, apiKey: String): List<ClipMoment> = withContext(Dispatchers.IO) {
  val audio = extractAudio(uri)
  try {
   val form = MultipartBody.Builder().setType(MultipartBody.FORM)
    .addFormDataPart("model", "whisper-1")
    .addFormDataPart("response_format", "verbose_json")
    .addFormDataPart("file", "audio.m4a", audio.asRequestBody("audio/mp4".toMediaType())).build()
   val trReq = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions").header("Authorization", "Bearer $apiKey").post(form).build()
   val trRes = http.newCall(trReq).execute()
   val trText = trRes.body?.string().orEmpty()
   if (!trRes.isSuccessful) error("Transcription API: $trText")
   val tr = JSONObject(trText)
   val duration = tr.optDouble("duration", 0.0)
   val segments = tr.optJSONArray("segments") ?: JSONArray()
   val prompt = """Identify up to 8 excellent short-form video moments. Prefer funny, surprising, emotional, high-energy, useful or suspenseful sections. Aim for 15-60 seconds, include enough context, and do not cut off punchlines. Return ONLY JSON in this shape: {"clips":[{"start":12.0,"end":42.0,"title":"Short title","reason":"Why it is compelling"}]}. Times are seconds from video start. Video duration: $duration. Transcript segments: $segments"""
   val payload = JSONObject().put("model", "gpt-4o-mini")
    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
    .put("response_format", JSONObject().put("type", "json_object")).toString()
   val req = Request.Builder().url("https://api.openai.com/v1/chat/completions").header("Authorization", "Bearer $apiKey")
    .post(payload.toRequestBody("application/json".toMediaType())).build()
   val res = http.newCall(req).execute()
   val response = res.body?.string().orEmpty()
   if (!res.isSuccessful) error("AI selection API: $response")
   val content = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
   val arr = JSONObject(content).optJSONArray("clips") ?: JSONArray()
   buildList {
    for (i in 0 until arr.length()) {
     val c = arr.getJSONObject(i); val start = c.optDouble("start", 0.0).coerceAtLeast(0.0)
     val end = c.optDouble("end", 0.0).coerceAtMost(duration)
     if (end > start && end - start >= 5) add(ClipMoment(start, end, c.optString("title", "Highlight"), c.optString("reason", "AI-selected moment")))
    }
   }
  } finally { audio.delete() }
 }

 private fun extractAudio(uri: Uri): File {
  val ex = MediaExtractor(); ex.setDataSource(this, uri, null)
  var track = -1; var format: MediaFormat? = null
  for (i in 0 until ex.trackCount) {
   val f = ex.getTrackFormat(i)
   if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
  }
  if (track < 0 || format == null) { ex.release(); error("The selected video has no audio track.") }
  ex.selectTrack(track)
  val file = File(cacheDir, "clipper-audio.m4a")
  val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  val outTrack = muxer.addTrack(format); muxer.start()
  val buffer = java.nio.ByteBuffer.allocate(1024 * 1024); val info = android.media.MediaCodec.BufferInfo()
  while (true) {
   buffer.clear(); val size = ex.readSampleData(buffer, 0); if (size < 0) break
   info.offset = 0; info.size = size; info.presentationTimeUs = ex.sampleTime; info.flags = ex.sampleFlags
   muxer.writeSampleData(outTrack, buffer, info); ex.advance()
  }
  muxer.stop(); muxer.release(); ex.release(); return file
 }

 private suspend fun render(uri: Uri, m: ClipMoment): String = withContext(Dispatchers.Main) {
  val folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
  val out = File(folder, "Clip_${System.currentTimeMillis()}.mp4")
  val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(
   ClippingConfiguration.Builder().setStartPositionMs((m.start * 1000).toLong()).setEndPositionMs((m.end * 1000).toLong()).build()
  ).build()
  kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
   val transformer = Transformer.Builder(this@MainActivity).addListener(object : Transformer.Listener {
    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(out.absolutePath) {} }
    override fun onError(composition: Composition, exportResult: ExportResult, exception: androidx.media3.transformer.ExportException) { if (cont.isActive) cont.resumeWith(Result.failure(exception)) }
   }).build()
   transformer.start(item, out.absolutePath)
   cont.invokeOnCancellation { transformer.cancel() }
  }
 }
}
