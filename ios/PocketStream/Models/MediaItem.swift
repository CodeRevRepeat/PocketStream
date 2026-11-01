import Foundation

public struct MediaItem: Codable, Identifiable, Equatable {
    public let id: String
    public let title: String
    public let sizeBytes: Int64
    public let mimeType: String
    public var durationMs: Int64
    public let width: Int
    public let height: Int
    public let videoCodec: String
    public let audioCodec: String
    public let streamPath: String

    public init(
        id: String,
        title: String,
        sizeBytes: Int64,
        mimeType: String,
        durationMs: Int64 = 0,
        width: Int = 0,
        height: Int = 0,
        videoCodec: String = "",
        audioCodec: String = "",
        streamPath: String? = nil
    ) {
        self.id = id
        self.title = title
        self.sizeBytes = sizeBytes
        self.mimeType = mimeType
        self.durationMs = durationMs
        self.width = width
        self.height = height
        self.videoCodec = videoCodec
        self.audioCodec = audioCodec
        self.streamPath = streamPath ?? "/stream/\(id)"
    }

    public func toDictionary() -> [String: Any] {
        return [
            "id": id,
            "title": title,
            "sizeBytes": sizeBytes,
            "mimeType": mimeType,
            "durationMs": durationMs,
            "width": width,
            "height": height,
            "videoCodec": videoCodec,
            "audioCodec": audioCodec,
            "streamPath": streamPath
        ]
    }

    public static func from(dictionary: [String: Any]) -> MediaItem? {
        guard let id = dictionary["id"] as? String else { return nil }
        return MediaItem(
            id: id,
            title: dictionary["title"] as? String ?? "Untitled",
            sizeBytes: (dictionary["sizeBytes"] as? NSNumber)?.int64Value ?? 0,
            mimeType: dictionary["mimeType"] as? String ?? "video/mp4",
            durationMs: (dictionary["durationMs"] as? NSNumber)?.int64Value ?? 0,
            width: dictionary["width"] as? Int ?? 0,
            height: dictionary["height"] as? Int ?? 0,
            videoCodec: dictionary["videoCodec"] as? String ?? "",
            audioCodec: dictionary["audioCodec"] as? String ?? "",
            streamPath: dictionary["streamPath"] as? String ?? "/stream/\(id)"
        )
    }

    public var formattedDuration: String {
        let totalSeconds = max(0, durationMs / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%02d:%02d", minutes, seconds)
        }
    }

    public var formattedSize: String {
        let bcf = ByteCountFormatter()
        bcf.allowedUnits = [.useMB, .useGB]
        bcf.countStyle = .file
        return bcf.string(fromByteCount: sizeBytes)
    }

    public var resolutionLabel: String {
        if width >= 3840 || height >= 2160 { return "4K" }
        if width >= 1920 || height >= 1080 { return "1080p" }
        if width >= 1280 || height >= 720 { return "720p" }
        if height > 0 { return "\(height)p" }
        return "Video"
    }
}
