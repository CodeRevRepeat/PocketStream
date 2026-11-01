import Foundation

public struct CompatibilityResult {
    public let isCompatible: Bool
    public let unsupportedReasons: [String]

    public init(isCompatible: Bool, unsupportedReasons: [String]) {
        self.isCompatible = isCompatible
        self.unsupportedReasons = unsupportedReasons
    }
}

public struct TvCapabilities: Codable {
    public let deviceModel: String
    public let maxWidth: Int
    public let maxHeight: Int
    public let maxFps: Int
    public let supports10BitHevc: Bool
    public let supportsHdr: Bool
    public let supports4K: Bool
    public let supportedVideoMimes: [String]
    public let hardwareDecoders: [String]

    public init(
        deviceModel: String = "Android TV",
        maxWidth: Int = 1920,
        maxHeight: Int = 1088,
        maxFps: Int = 60,
        supports10BitHevc: Bool = false,
        supportsHdr: Bool = false,
        supports4K: Bool = false,
        supportedVideoMimes: [String] = ["video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9"],
        hardwareDecoders: [String] = []
    ) {
        self.deviceModel = deviceModel
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.maxFps = maxFps
        self.supports10BitHevc = supports10BitHevc
        self.supportsHdr = supportsHdr
        self.supports4K = supports4K
        self.supportedVideoMimes = supportedVideoMimes
        self.hardwareDecoders = hardwareDecoders
    }

    public func checkCompatibility(
        videoMime: String?,
        width: Int,
        height: Int,
        is10Bit: Bool
    ) -> CompatibilityResult {
        var reasons: [String] = []

        // 1. 10-bit / HDR color check
        if is10Bit && !supports10BitHevc {
            reasons.append("10-bit color / HDR detected (TV hardware decoder only supports 8-bit)")
        }

        // 2. Resolution check
        let effW = max(0, width)
        let effH = max(0, height)
        if effW > maxWidth || effH > maxHeight {
            let resLabel = (effW >= 3840 || effH >= 2160) ? "4K UHD (\(effW)x\(effH))" : "\(effW)x\(effH)"
            reasons.append("Resolution \(resLabel) exceeds TV hardware limit (\(maxWidth)x\(maxHeight))")
        }

        // 3. Codec check
        if let mime = videoMime, !mime.isEmpty {
            let normalized = mime.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
            let isKnown = supportedVideoMimes.contains { $0.caseInsensitiveCompare(normalized) == .orderedSame }
            let isHevcOrAvc = normalized.contains("hevc") || normalized.contains("h265") ||
                              normalized.contains("avc") || normalized.contains("h264") ||
                              normalized.contains("mp4") || normalized.contains("matroska")
            if !isKnown && !isHevcOrAvc && normalized.hasPrefix("video/") {
                reasons.append("Video codec '\(mime)' is not natively supported by TV hardware")
            }
        }

        return CompatibilityResult(
            isCompatible: reasons.isEmpty,
            unsupportedReasons: reasons
        )
    }

    public static func from(dictionary: [String: Any]) -> TvCapabilities {
        let mimes = dictionary["supportedVideoMimes"] as? [String] ?? ["video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9"]
        let hw = dictionary["hardwareDecoders"] as? [String] ?? []
        return TvCapabilities(
            deviceModel: dictionary["deviceModel"] as? String ?? "Android TV",
            maxWidth: dictionary["maxWidth"] as? Int ?? 1920,
            maxHeight: dictionary["maxHeight"] as? Int ?? 1088,
            maxFps: dictionary["maxFps"] as? Int ?? 60,
            supports10BitHevc: dictionary["supports10BitHevc"] as? Bool ?? false,
            supportsHdr: dictionary["supportsHdr"] as? Bool ?? false,
            supports4K: dictionary["supports4K"] as? Bool ?? false,
            supportedVideoMimes: mimes,
            hardwareDecoders: hw
        )
    }

    public static let default1080P = TvCapabilities(
        deviceModel: "Android TV",
        maxWidth: 1920,
        maxHeight: 1088,
        maxFps: 60,
        supports10BitHevc: false,
        supportsHdr: false,
        supports4K: false,
        supportedVideoMimes: ["video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9"],
        hardwareDecoders: []
    )
}
