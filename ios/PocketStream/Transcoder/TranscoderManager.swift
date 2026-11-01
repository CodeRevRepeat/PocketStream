import Foundation
import AVFoundation

public class TranscoderManager: ObservableObject {
    public static let shared = TranscoderManager()

    private var activeExportSession: AVAssetExportSession?
    private var progressTimer: Timer?
    private var inProgress: [String: Int] = [:]
    private var completedFiles: [String: URL] = [:]

    private init() {}

    public func getTranscodedFile(id: String) -> URL? {
        if let cached = completedFiles[id], FileManager.default.fileExists(atPath: cached.path) {
            return cached
        }
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let file = cacheDir.appendingPathComponent("transcoded_\(id).mp4")
        let doneMarker = cacheDir.appendingPathComponent("transcoded_\(id).done")

        if FileManager.default.fileExists(atPath: file.path) && FileManager.default.fileExists(atPath: doneMarker.path) {
            completedFiles[id] = file
            return file
        }
        return nil
    }

    public func getActiveOrCompletedFile(id: String) -> URL? {
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let file = cacheDir.appendingPathComponent("transcoded_\(id).mp4")
        if FileManager.default.fileExists(atPath: file.path) {
            let attr = try? FileManager.default.attributesOfItem(atPath: file.path)
            if let size = attr?[.size] as? Int64, size > 0 {
                return file
            }
        }
        return nil
    }

    public func isTranscoding(id: String) -> Bool {
        return inProgress[id] != nil
    }

    public func getProgress(id: String) -> Int {
        return inProgress[id] ?? 0
    }

    public func startLiveTranscode(
        media: LocalMedia,
        startPositionMs: Int64 = 0,
        onReadyToStream: @escaping (URL) -> Void,
        onProgress: @escaping (Int) -> Void = { _ in },
        onCompleted: @escaping (URL) -> Void = { _ in },
        onError: @escaping (String) -> Void = { _ in }
    ) {
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let outputFile = cacheDir.appendingPathComponent("transcoded_\(media.id).mp4")
        let doneMarker = cacheDir.appendingPathComponent("transcoded_\(media.id).done")

        if startPositionMs > 0 {
            cancelActive()
            completedFiles.removeValue(forKey: media.id)
            try? FileManager.default.removeItem(at: outputFile)
            try? FileManager.default.removeItem(at: doneMarker)
        } else {
            if let existing = getTranscodedFile(id: media.id) {
                onReadyToStream(existing)
                onCompleted(existing)
                return
            }
        }

        try? FileManager.default.removeItem(at: outputFile)
        try? FileManager.default.removeItem(at: doneMarker)

        inProgress[media.id] = 0

        let asset = AVURLAsset(url: media.url)
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1920x1080) else {
            inProgress.removeValue(forKey: media.id)
            onError("Could not initialize hardware export session")
            return
        }

        exportSession.outputURL = outputFile
        exportSession.outputFileType = .mp4
        exportSession.shouldOptimizeForNetworkUse = true

        if startPositionMs > 0 {
            let startCM = CMTime(value: startPositionMs, timescale: 1000)
            let durationCM = CMTimeSubtract(asset.duration, startCM)
            exportSession.timeRange = CMTimeRange(start: startCM, duration: durationCM)
        }

        activeExportSession = exportSession

        var readyDispatched = false
        progressTimer?.invalidate()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] timer in
            guard let self = self else { timer.invalidate(); return }
            let percent = Int((exportSession.progress) * 100.0)
            self.inProgress[media.id] = percent
            DispatchQueue.main.async {
                onProgress(percent)
            }

            if !readyDispatched && FileManager.default.fileExists(atPath: outputFile.path) {
                let attr = try? FileManager.default.attributesOfItem(atPath: outputFile.path)
                if let size = attr?[.size] as? Int64, size >= 1024 * 1024 {
                    readyDispatched = true
                    DispatchQueue.main.async {
                        onReadyToStream(outputFile)
                    }
                }
            }
        }

        exportSession.exportAsynchronously { [weak self] in
            guard let self = self else { return }
            self.progressTimer?.invalidate()
            self.progressTimer = nil

            DispatchQueue.main.async {
                switch exportSession.status {
                case .completed:
                    if startPositionMs == 0 {
                        try? Data().write(to: doneMarker)
                        self.completedFiles[media.id] = outputFile
                    }
                    self.inProgress.removeValue(forKey: media.id)
                    onCompleted(outputFile)
                case .failed:
                    self.inProgress.removeValue(forKey: media.id)
                    try? FileManager.default.removeItem(at: outputFile)
                    onError(exportSession.error?.localizedDescription ?? "Hardware transcode failed")
                case .cancelled:
                    self.inProgress.removeValue(forKey: media.id)
                    try? FileManager.default.removeItem(at: outputFile)
                default:
                    break
                }
            }
        }
    }

    public func cancelActive() {
        progressTimer?.invalidate()
        progressTimer = nil
        activeExportSession?.cancelExport()
        activeExportSession = nil
        inProgress.removeAll()
    }

    public func purgeAllCache() {
        cancelActive()
        let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        if let files = try? FileManager.default.contentsOfDirectory(at: cacheDir, includingPropertiesForKeys: nil) {
            for f in files {
                let name = f.lastPathComponent
                if name.hasPrefix("transcoded_") || name.hasSuffix(".done") || name.hasSuffix(".mp4") {
                    try? FileManager.default.removeItem(at: f)
                }
            }
        }
        completedFiles.removeAll()
    }
}
