import Foundation

public enum ControlAction: String, Codable {
    case play = "PLAY"
    case pause = "PAUSE"
    case seek = "SEEK"
    case stop = "STOP"
    case resume = "RESUME"
    case rw = "RW"
    case ff = "FF"
    case restart = "RESTART"
    case preparingSeek = "PREPARING_SEEK"
}

public struct PlaybackControl: Codable {
    public let action: String
    public let positionMs: Int64
    public let mediaId: String?

    public init(action: ControlAction, positionMs: Int64 = 0, mediaId: String? = nil) {
        self.action = action.rawValue
        self.positionMs = positionMs
        self.mediaId = mediaId
    }

    public func toDictionary() -> [String: Any] {
        var dict: [String: Any] = [
            "action": action,
            "positionMs": positionMs
        ]
        if let mediaId = mediaId {
            dict["mediaId"] = mediaId
        }
        return dict
    }
}
