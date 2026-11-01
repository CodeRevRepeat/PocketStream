import Foundation
import AVFoundation

public struct LocalMedia: Identifiable, Equatable {
    public let id: String
    public let url: URL
    public let title: String
    public let sizeBytes: Int64
    public let mimeType: String
    public var durationMs: Int64
    public var width: Int
    public var height: Int
    public var videoCodec: String
    public var audioCodec: String
    public var is10Bit: Bool

    public init(
        id: String,
        url: URL,
        title: String,
        sizeBytes: Int64,
        mimeType: String,
        durationMs: Int64 = 0,
        width: Int = 0,
        height: Int = 0,
        videoCodec: String = "",
        audioCodec: String = "",
        is10Bit: Bool = false
    ) {
        self.id = id
        self.url = url
        self.title = title
        self.sizeBytes = sizeBytes
        self.mimeType = mimeType
        self.durationMs = durationMs
        self.width = width
        self.height = height
        self.videoCodec = videoCodec
        self.audioCodec = audioCodec
        self.is10Bit = is10Bit
    }
}

public class MediaRepository: ObservableObject {
    public static let shared = MediaRepository()

    private let prefsName = "pocketstream_storage_bookmarks"
    private let keyPersistedBookmarks = "persisted_file_bookmarks"
    private let keyExcludedIds = "excluded_media_ids"

    @Published public private(set) var mediaList: [LocalMedia] = []
    private var mediaMap: [String: LocalMedia] = [:]
    private var excludedIds: Set<String> = []

    public init() {
        loadPersistedMedia()
    }

    public func loadPersistedMedia() {
        let defaults = UserDefaults.standard
        if let savedExcludes = defaults.stringArray(forKey: keyExcludedIds) {
            excludedIds = Set(savedExcludes)
        }

        guard let bookmarks = defaults.dictionary(forKey: keyPersistedBookmarks) as? [String: Data] else {
            return
        }

        var loaded: [LocalMedia] = []
        for (id, bookmarkData) in bookmarks {
            if excludedIds.contains(id) { continue }
            var isStale = false
            do {
                let url = try URL(resolvingBookmarkData: bookmarkData, options: .withoutUI, relativeTo: nil, bookmarkDataIsStale: &isStale)
                if url.startAccessingSecurityScopedResource() {
                    if let media = probeMedia(url: url, id: id) {
                        loaded.append(media)
                        mediaMap[id] = media
                    }
                }
            } catch {
                print("Failed to resolve bookmark for \(id): \(error)")
            }
        }

        DispatchQueue.main.async {
            self.mediaList = loaded.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        }
    }

    public func addMedia(from url: URL) {
        let _ = url.startAccessingSecurityScopedResource()
        let id = UUID().uuidString
        if let media = probeMedia(url: url, id: id) {
            excludedIds.remove(id)
            mediaMap[id] = media

            do {
                let bookmark = try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
                var currentBookmarks = UserDefaults.standard.dictionary(forKey: keyPersistedBookmarks) as? [String: Data] ?? [:]
                currentBookmarks[id] = bookmark
                UserDefaults.standard.set(currentBookmarks, forKey: keyPersistedBookmarks)
            } catch {
                print("Failed to create bookmark for \(url): \(error)")
            }

            DispatchQueue.main.async {
                self.mediaList = Array(self.mediaMap.values).sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
            }
        }
    }

    public func removeMedia(ids: Set<String>) {
        var currentBookmarks = UserDefaults.standard.dictionary(forKey: keyPersistedBookmarks) as? [String: Data] ?? [:]
        for id in ids {
            mediaMap.removeValue(forKey: id)
            excludedIds.insert(id)
            currentBookmarks.removeValue(forKey: id)
        }
        UserDefaults.standard.set(currentBookmarks, forKey: keyPersistedBookmarks)
        UserDefaults.standard.set(Array(excludedIds), forKey: keyExcludedIds)

        DispatchQueue.main.async {
            self.mediaList = Array(self.mediaMap.values).sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        }
    }

    public func getMedia(by id: String) -> LocalMedia? {
        if excludedIds.contains(id) { return nil }
        return mediaMap[id]
    }

    public func getAllMediaItems() -> [MediaItem] {
        return mediaList.map { media in
            MediaItem(
                id: media.id,
                title: media.title,
                sizeBytes: media.sizeBytes,
                mimeType: media.mimeType,
                durationMs: media.durationMs,
                width: media.width,
                height: media.height,
                videoCodec: media.videoCodec,
                audioCodec: media.audioCodec,
                streamPath: "/stream/\(media.id)"
            )
        }
    }

    private func probeMedia(url: URL, id: String) -> LocalMedia? {
        let asset = AVURLAsset(url: url)
        let resources = try? url.resourceValues(forKeys: [.fileSizeKey])
        let size = Int64(resources?.fileSize ?? 0)
        let title = url.lastPathComponent

        let pathExt = url.pathExtension.lowercased()
        let mimeType: String
        switch pathExt {
        case "mp4", "m4v": mimeType = "video/mp4"
        case "mkv": mimeType = "video/x-matroska"
        case "mov": mimeType = "video/quicktime"
        case "avi": mimeType = "video/x-msvideo"
        case "webm": mimeType = "video/webm"
        default: mimeType = "video/mp4"
        }

        let durationSeconds = CMTimeGetSeconds(asset.duration)
        let durationMs = durationSeconds.isFinite ? Int64(durationSeconds * 1000.0) : 0

        var width = 0
        var height = 0
        var videoCodec = ""
        var is10Bit = title.localizedCaseInsensitiveContains("10bit") ||
                      title.localizedCaseInsensitiveContains("10-bit") ||
                      title.localizedCaseInsensitiveContains("hdr")

        if let track = asset.tracks(withMediaType: .video).first {
            let size = track.naturalSize.applying(track.preferredTransform)
            width = Int(abs(size.width))
            height = Int(abs(size.height))

            let formatDescriptions = track.formatDescriptions as! [CMFormatDescription]
            for desc in formatDescriptions {
                let subType = CMFormatDescriptionGetMediaSubType(desc)
                videoCodec = fourCCToString(subType)

                // Inspect color primaries and bit depth extensions
                if let extensions = CMFormatDescriptionGetExtensions(desc) as? [String: Any] {
                    if let transfer = extensions[kCVImageBufferTransferFunctionKey as String] as? String {
                        if transfer.contains("2084") || transfer.contains("HLG") || transfer.contains("ITU_R_2100") {
                            is10Bit = true
                        }
                    }
                    if let primaries = extensions[kCVImageBufferColorPrimariesKey as String] as? String {
                        if primaries.contains("2020") {
                            is10Bit = true
                        }
                    }
                }
            }
        }

        return LocalMedia(
            id: id,
            url: url,
            title: title,
            sizeBytes: size,
            mimeType: mimeType,
            durationMs: durationMs,
            width: width,
            height: height,
            videoCodec: videoCodec,
            audioCodec: "aac",
            is10Bit: is10Bit
        )
    }

    private func fourCCToString(_ code: FourCharCode) -> String {
        let bytes: [CChar] = [
            CChar((code >> 24) & 0xff),
            CChar((code >> 16) & 0xff),
            CChar((code >> 8) & 0xff),
            CChar(code & 0xff),
            0
        ]
        return String(cString: bytes).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
