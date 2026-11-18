# Clip Clipper

A free-to-install Android video clipping app. Choose a local recording, let OpenAI transcribe it and identify standout moments, then render each selected moment locally and share it through Android's share menu (including TikTok when installed).

## Features
- Native Android app built with Kotlin and Jetpack Compose
- Select a video from device storage
- Transcribe audio with OpenAI Whisper
- Ask GPT-4o mini to identify funny, surprising, emotional, useful and high-energy moments
- **AI Budget Saver:** choose 3–8 highlights per analysis
- **Compact transcript sampling:** sends GPT a time-spread sample capped at about 10,000 transcript characters, reducing text-model input
- Export individual MP4 clips on-device
- Share clips to compatible apps

## Costs and privacy
The APK is free to install, but OpenAI API usage is billed separately and requires an API key with billing enabled. A ChatGPT Free or Plus subscription does not include API credits. The Budget Saver reduces the amount of transcript sent to GPT-4o mini and limits the number of suggested clips. **Whisper still transcribes the full audio**, so transcription costs still depend on the recording's duration. Video rendering happens locally and does not use AI. Keep your API key private.

Only extracted audio is uploaded to OpenAI for transcription; the video itself is processed locally for cutting.

## Build
Pushes to the main branch trigger GitHub Actions. Download the clip-clipper-debug-apk artifact from the completed workflow run. Build locally with JDK 17, Android SDK and Gradle using assembleDebug.

## Current scope
This first version processes local video recordings. It does not yet capture live streams, download arbitrary stream URLs, or upload directly to TikTok. Use Android's share menu to send rendered clips to TikTok.
