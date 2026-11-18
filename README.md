# Clip Clipper

A free-to-install Android video clipping app. Choose a local recording, let OpenAI transcribe it and identify standout moments, then render each selected moment locally and share it through Android's share menu (including TikTok when installed).

## Features
- Native Android app built with Kotlin and Jetpack Compose
- Select a video from device storage
- Transcribe audio with OpenAI Whisper
- Ask GPT-4o mini to identify funny, surprising, emotional, useful and high-energy moments
- Export individual MP4 clips on-device
- Share clips to compatible apps

## Costs and privacy
The APK is free to install, but OpenAI API usage is not free and requires an API key with billing enabled. A ChatGPT Free or Plus subscription does not include API credits. Only extracted audio is uploaded to OpenAI for transcription. Video rendering happens locally. Keep your API key private.

## Build
Pushes to the main branch trigger GitHub Actions. Download the clip-clipper-debug-apk artifact from the completed workflow run. Build locally with JDK 17, Android SDK and Gradle using assembleDebug.

## Current scope
This first version processes local video recordings. It does not yet capture live streams, download arbitrary stream URLs, or upload directly to TikTok. Use Android's share menu to send rendered clips to TikTok.
