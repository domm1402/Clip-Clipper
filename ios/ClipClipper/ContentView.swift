import SwiftUI
import AVFoundation
import PhotosUI
import UniformTypeIdentifiers

struct ClipMoment: Decodable, Identifiable {
    let start: Double
    let end: Double
    let title: String
    let reason: String
    var id: Double { start }
}

struct ClipResponse: Decodable { let clips: [ClipMoment] }
struct ChatResponse: Decodable {
    struct Choice: Decodable { struct Message: Decodable { let content: String }; let message: Message }
    let choices: [Choice]
}

@MainActor
final class ClipperModel: ObservableObject {
    @Published var apiKey = ""
    @Published var sourceURL = ""
    @Published var videoURL: URL?
    @Published var videoName = "No video selected"
    @Published var clipCount = 5.0
    @Published var status = "Choose a video to get started."
    @Published var busy = false
    @Published var moments: [ClipMoment] = []
    @Published var clips: [URL] = []
    @Published var progress = 0.0

    func analyseAndClip() async {
        guard !apiKey.isEmpty else { status = "Enter your OpenAI API key."; return }
        guard let input = videoURL else { status = "Select a video first."; return }
        busy = true; moments = []; clips = []; progress = 0
        defer { busy = false }
        do {
            status = "Extracting audio…"
            let audio = try await extractAudio(from: input)
            status = "Transcribing with OpenAI…"
            let transcript = try await transcribe(audio)
            status = "Finding the best moments…"
            let found = try await findMoments(transcript, count: Int(clipCount))
            moments = found
            guard !found.isEmpty else { status = "No suitable highlights found."; return }
            for (index, moment) in found.enumerated() {
                status = "Rendering clip \(index + 1) of \(found.count)…"
                let output = try await exportClip(input, moment: moment)
                clips.append(output)
                progress = Double(index + 1) / Double(found.count)
            }
            status = "Finished! Your clips are ready to share."
        } catch { status = "Couldn't finish: \(error.localizedDescription)" }
    }

    private func extractAudio(from url: URL) async throws -> URL {
        let asset = AVURLAsset(url: url)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else { throw ClipError.export }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
        session.outputURL = out; session.outputFileType = .m4a
        await withCheckedContinuation { continuation in session.exportAsynchronously { continuation.resume() } }
        if session.status != .completed { throw session.error ?? ClipError.export }
        return out
    }

    private func transcribe(_ file: URL) async throws -> String {
        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/audio/transcriptions")!)
        request.httpMethod = "POST"; request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n".data(using: .utf8)!)
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n".data(using: .utf8)!)
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".data(using: .utf8)!)
        body.append(try Data(contentsOf: file)); body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body
        let (data, response) = try await URLSession.shared.data(for: request)
        try check(response, data)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    private func findMoments(_ transcript: String, count: Int) async throws -> [ClipMoment] {
        let object = try JSONSerialization.jsonObject(with: Data(transcript.utf8)) as? [String: Any] ?? [:]
        let segments = object["segments"] as? [[String: Any]] ?? []
        let compact = segments.enumerated().filter { $0.offset % max(1, segments.count / 150) == 0 }.map { $0.element }
        let prompt = "Find up to \(count) engaging short-form highlights, ideally 15-60 seconds. Return JSON only: {\"clips\":[{\"start\":1.0,\"end\":20.0,\"title\":\"Title\",\"reason\":\"Why it works\"}]}. Times are seconds from the start. Transcript segments: \(String(data: try JSONSerialization.data(withJSONObject: compact), encoding: .utf8) ?? "")"
        let payload: [String: Any] = ["model":"gpt-4o-mini","response_format":["type":"json_object"],"messages":[["role":"user","content":prompt]]]
        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/chat/completions")!)
        request.httpMethod = "POST"; request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await URLSession.shared.data(for: request)
        try check(response, data)
        let decoded = try JSONDecoder().decode(ChatResponse.self, from: data)
        guard let content = decoded.choices.first?.message.content else { return [] }
        return try JSONDecoder().decode(ClipResponse.self, from: Data(content.utf8)).clips
    }

    private func exportClip(_ source: URL, moment: ClipMoment) async throws -> URL {
        let asset = AVURLAsset(url: source)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else { throw ClipError.export }
        let output = FileManager.default.temporaryDirectory.appendingPathComponent("Clip-\(UUID().uuidString).mp4")
        session.outputURL = output; session.outputFileType = .mp4
        session.timeRange = CMTimeRange(start: CMTime(seconds: moment.start, preferredTimescale: 600), duration: CMTime(seconds: moment.end - moment.start, preferredTimescale: 600))
        await session.export()
        if session.status != .completed { throw session.error ?? ClipError.export }
        return output
    }

    private func check(_ response: URLResponse, _ data: Data) throws {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8) ?? "Request failed"
            throw NSError(domain: "ClipClipper", code: (response as? HTTPURLResponse)?.statusCode ?? -1, userInfo: [NSLocalizedDescriptionKey: message])
        }
    }
}
enum ClipError: Error { case export }

struct ContentView: View {
    @StateObject private var model = ClipperModel()
    @State private var picked: PhotosPickerItem?
    @State private var shareURL: URL?
    @State private var showShare = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("CLIP CLIPPER").font(.system(size: 27, weight: .black, design: .rounded))
                        Text("Your highlights, found and cut by AI").foregroundStyle(.secondary)
                        Text("ChatGPT finds the best bits. Clip Clipper automatically turns them into separate videos.")
                            .font(.headline).padding(.top, 8)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
                        .background(LinearGradient(colors: [Color.purple.opacity(0.35), Color.indigo.opacity(0.15)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24))
                    GroupBox("01 · Choose your video") {
                        VStack(alignment: .leading, spacing: 12) {
                            PhotosPicker(selection: $picked, matching: .videos) {
                                Label("Choose video from Photos", systemImage: "video.badge.plus").frame(maxWidth: .infinity)
                            }.buttonStyle(.borderedProminent)
                            Text(model.videoName).font(.caption).foregroundStyle(.secondary)
                            TextField("TikTok / YouTube link (not supported yet)", text: $model.sourceURL)
                                .textInputAutocapitalization(.never).keyboardType(.URL)
                            Text("For this iOS build, import a video saved to Photos or Files. Platform links need a separate, App Store-compatible integration.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    GroupBox("02 · AI settings") {
                        VStack(alignment: .leading, spacing: 12) {
                            SecureField("OpenAI API key (sk-…)", text: $model.apiKey)
                                .textInputAutocapitalization(.never).autocorrectionDisabled()
                            DisclosureGroup("How to get your API key") {
                                Text("1. Sign in at platform.openai.com.\n2. Open API keys.\n3. Tap Create new secret key.\n4. Copy it and paste it above. Keep it private. API usage is billed separately from ChatGPT.")
                                    .font(.footnote).padding(.vertical, 6)
                                Link("OpenAI API keys", destination: URL(string: "https://platform.openai.com/api-keys")!)
                            }
                            HStack { Text("Highlights to find"); Spacer(); Text("\(Int(model.clipCount))") }
                            Slider(value: $model.clipCount, in: 3...8, step: 1).tint(.purple)
                            Button {
                                Task { await model.analyseAndClip() }
                            } label: {
                                Label(model.busy ? "Working…" : "Find & auto-clip", systemImage: "sparkles")
                                    .frame(maxWidth: .infinity).padding(.vertical, 5)
                            }.buttonStyle(.borderedProminent).disabled(model.busy || model.videoURL == nil || model.apiKey.isEmpty)
                        }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text(model.status).font(.subheadline)
                        if model.busy { ProgressView(value: model.progress).tint(.purple) }
                    }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                    if !model.moments.isEmpty {
                        Text("AI-picked moments").font(.title2.bold())
                        ForEach(Array(model.moments.enumerated()), id: \.offset) { index, pair in
                            let moment = pair.element
                            VStack(alignment: .leading, spacing: 5) {
                                Text("\(index + 1). \(moment.title)").font(.headline)
                                Text(String(format: "%.0f–%.0f seconds", moment.start, moment.end)).font(.caption).foregroundStyle(.purple)
                                Text(moment.reason).font(.subheadline).foregroundStyle(.secondary)
                            }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 15))
                        }
                    }
                    if !model.clips.isEmpty {
                        Text("Finished clips").font(.title2.bold())
                        ForEach(model.clips, id: \.self) { url in
                            ShareLink(item: url) { Label(url.lastPathComponent, systemImage: "square.and.arrow.up").frame(maxWidth: .infinity, alignment: .leading) }
                                .padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                        }
                    }
                    Text("Privacy: the selected video's audio is sent to OpenAI for transcription and highlight selection. Video cutting happens on your iPhone. API billing is separate from ChatGPT.")
                        .font(.caption2).foregroundStyle(.secondary)
                }.padding()
            }
            .navigationBarTitleDisplayMode(.inline)
            .task(id: picked) {
                guard let picked, let data = try? await picked.loadTransferable(type: Data.self) else { return }
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("Imported-\(UUID().uuidString).mp4")
                do {
                    try data.write(to: url)
                    model.videoURL = url
                    model.videoName = "Video ready"
                    model.status = "Video ready. Add your API key and tap Find & auto-clip."
                } catch { model.status = error.localizedDescription }
            }
        }.tint(.purple)
    }
}
