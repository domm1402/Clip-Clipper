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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
  var maxClips by remember { mutableFloatStateOf(5f) }
  var status by remember { mutableStateOf("Choose a video and let AI do the editing.") }
  var busy by remember { mutableStateOf(false) }
  var moments by remember { mutableStateOf(listOf<ClipMoment>()) }
  var outputs by remember { mutableStateOf(listOf<Pair<String, String>>()) }
  var progress by remember { mutableIntStateOf(0) }
  var totalToRender by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
   if (picked != null) {
    try { contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
    uri = picked
    videoName = picked.lastPathSegment?.substringAfterLast('/') ?: "Selected video"
    moments = emptyList()
    outputs = emptyList()
    status = "Video ready. Add your API key, then tap Find & auto-clip."
   }
  }
  val accent = Color(0xFFB7A2FF)
  val bg = Color(0xFF090A12)
  val panel = Color(0xFF171827)
  val muted = Color(0xFFA8A6B8)
  MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel)) {
   Surface(Modifier.fillMaxSize(), color = bg) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 32.dp)) {
     item {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
       Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Brush.linearGradient(listOf(Color(0xFF8C65FF), Color(0xFF3C2A82)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.ContentCut, null, tint = Color.White, modifier = Modifier.size(29.dp))
       }
       Column(Modifier.weight(1f)) {
        Text("CLIP CLIPPER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
        Text("Your highlights, found and cut by AI", color = muted, style = MaterialTheme.typography.bodySmall)
       }
       Box(Modifier.clip(CircleShape).background(Color(0xFF242039)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("AI EDITOR", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
       }
      }
     }
     item {
      Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = panel)) {
       Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF282044), panel, panel))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("TURN LONG VIDEOS INTO CLIPS", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text("ChatGPT finds the best bits. Clip Clipper automatically cuts every selected moment into its own video.", color = Color(0xFFD0CCDF), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
         Icon(Icons.Default.AutoAwesome, null, tint = accent, modifier = Modifier.size(17.dp))
         Text("Find highlights  ·  Auto-cut  ·  Share", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
       }
      }
     }
     item {
      SectionCard("01", "Choose your video", "Pick a recording from your phone", panel) {
       OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(15.dp)) {
        Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.width(9.dp)); Text("Select a video")
       }
       Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Movie, null, tint = accent, modifier = Modifier.size(18.dp))
        Text(videoName, color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
       }
      }
     }
     item {
      SectionCard("02", "AI settings", "Let the AI choose the moments. No manual trimming.", panel) {
       OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("OpenAI API key") }, placeholder = { Text("sk-…") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(14.dp))
       Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Highlights to find", color = muted)
        Text(maxClips.toInt().toString(), color = accent, fontWeight = FontWeight.Bold)
       }
       Slider(value = maxClips, onValueChange = { maxClips = it }, valueRange = 3f..8f, steps = 4)
       Text("The AI selects up to ${maxClips.toInt()} moments. Every valid selection is automatically rendered as an MP4 clip.", color = muted, style = MaterialTheme.typography.bodySmall)
       Button(onClick = {
        val selected = uri
        if (selected == null) { status = "Select a video first."; return@Button }
        if (!key.startsWith("sk-")) { status = "Enter your OpenAI API key."; return@Button }
        busy = true; progress = 0; totalToRender = 0; moments = emptyList(); outputs = emptyList()
        status = "Listening to the video and finding the best moments…"
        scope.launch {
         try {
          val found = analyse(selected, key, maxClips.toInt())
          moments = found
          if (found.isEmpty()) {
           status = "The AI couldn't find any valid moments in this video."
          } else {
           totalToRender = found.size
           status = "AI found ${found.size} moments. Automatically cutting them now…"
           found.forEachIndexed { index, moment ->
            status = "Cutting clip ${index + 1} of ${found.size}: ${moment.title}"
            val file = render(selected, moment)
            outputs = outputs + (moment.title to file)
            progress = index + 1
           }
           status = "Finished! All ${found.size} AI-selected clips are ready to share."
          }
         } catch (e: Exception) { status = "Couldn't finish: ${e.message ?: "Unknown error"}" }
         finally { busy = false }
        }
       }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
        if (busy) { CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(10.dp)); Text("Finding & cutting…") }
        else { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Find & auto-clip", fontWeight = FontWeight.Bold) }
       }
       if (busy && totalToRender > 0) {
        LinearProgressIndicator(progress = { progress.toFloat() / totalToRender.toFloat() }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
        Text("$progress of $totalToRender clips rendered", color = muted, style = MaterialTheme.typography.labelSmall)
       }
      }
     }
     item {
      Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (status.startsWith("Couldn't")) Color(0xFF351D27) else Color(0xFF211D35))) {
       Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(if (outputs.isNotEmpty() && !busy) Icons.Default.CheckCircle else Icons.Default.AutoAwesome, null, tint = accent)
        Text(status, color = Color(0xFFE4DFFF), style = MaterialTheme.typography.bodySmall)
       }
      }
     }
     if (moments.isNotEmpty()) {
      item { Text("AI picked these moments", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold) }
      itemsIndexed(moments) { index, m ->
       Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = panel)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
         Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF30264F)), contentAlignment = Alignment.Center) {
          Text("${index + 1}", color = accent, fontWeight = FontWeight.Bold)
         }
         Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
          Text(m.title, color = Color.White, fontWeight = FontWeight.Bold)
          Text("${stamp(m.start)} – ${stamp(m.end)}", color = accent, style = MaterialTheme.typography.labelMedium)
          Text(m.reason, color = muted, style = MaterialTheme.typography.bodySmall)
         }
         if (index < progress || outputs.size > index) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF77D6A1))
        }
       }
      }
     }
     if (outputs.isNotEmpty()) {
      item { Text("Your finished clips", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold) }
      itemsIndexed(outputs) { index, output ->
       Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = panel)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
         Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF30264F)), contentAlignment = Alignment.Center) {
          Icon(Icons.Default.PlayArrow, null, tint = accent)
         }
         Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
          Text(output.first, color = Color.White, fontWeight = FontWeight.SemiBold)
          Text("Clip ${index + 1} · MP4", color = muted, style = MaterialTheme.typography.labelSmall)
         }
         IconButton(onClick = {
          val share = Intent(Intent.ACTION_SEND).apply {
           type = "video/mp4"
           putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", File(output.second)))
           addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          startActivity(Intent.createChooser(share, "Share clip"))
         }) { Icon(Icons.Default.Share, "Share", tint = accent) }
        }
       }
      }
     }
     item {
      Text("Privacy & costs: only extracted audio is sent to OpenAI for transcription and highlight selection. Your original video stays on your phone and clips are rendered locally. OpenAI API billing is separate from ChatGPT subscriptions.", color = Color(0xFF777587), style = MaterialTheme.typography.labelSmall)
     }
    }
   }
  }
 }

 @Composable private fun SectionCard(number: String, title: String, subtitle: String, panel: Color, content: @Composable ColumnScope.() -> Unit) {
  Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = panel)) {
   Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
     Box(Modifier.size(29.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF30264F)), contentAlignment = Alignment.Center) {
      Text(number, color = Color(0xFFB7A2FF), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
     }
     Column {
      Text(title, color = Color.White, fontWeight = FontWeight.Bold)
      Text(subtitle, color = Color(0xFFA8A6B8), style = MaterialTheme.typography.labelSmall)
     }
    }
    content()
   }
  }
 }

 private fun stamp(t: Double): String = "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)

 private suspend fun analyse(uri: Uri, apiKey: String, maxClips: Int): List<ClipMoment> = withContext(Dispatchers.IO) {
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
   if (duration <= 0.0) error("Couldn't read the video duration from its audio.")
   val segments = tr.optJSONArray("segments") ?: JSONArray()
   val compact = JSONArray()
   val stride = maxOf(1, kotlin.math.ceil(segments.length() / 160.0).toInt())
   var transcriptChars = 0
   for (i in 0 until segments.length() step stride) {
    val segment = segments.getJSONObject(i)
    val text = segment.optString("text").take(220)
    if (transcriptChars + text.length > 10000) break
    compact.put(JSONObject().put("start", segment.optDouble("start")).put("end", segment.optDouble("end")).put("text", text))
    transcriptChars += text.length
   }
   val prompt = """Identify up to $maxClips excellent short-form video moments. Prefer funny, surprising, emotional, high-energy, useful or suspenseful sections. Aim for 15-60 seconds, include enough context, and do not cut off punchlines. Return ONLY JSON in this shape: {"clips":[{"start":12.0,"end":42.0,"title":"Short title","reason":"Why it is compelling"}]}. Times are seconds from video start. Video duration: $duration. Compact transcript sample (spread across the recording): $compact"""
   val payload = JSONObject().put("model", "gpt-4o-mini")
    .put("max_tokens", 1200)
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
     val c = arr.getJSONObject(i)
     val start = c.optDouble("start", 0.0).coerceIn(0.0, duration)
     val end = c.optDouble("end", 0.0).coerceIn(0.0, duration)
     if (end > start && end - start >= 5.0) add(ClipMoment(start, end, c.optString("title", "Highlight"), c.optString("reason", "AI-selected moment")))
    }
   }.sortedBy { it.start }.distinctBy { (it.start / 2.0).toInt() }
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
