import Foundation

public struct ServerStatus: Codable {
    public let isRunning: Bool
    public let host: String
    public let port: Int
    public let deviceName: String
    public let version: String

    public init(
        isRunning: Bool,
        host: String,
        port: Int,
        deviceName: String = "PocketStream iOS",
        version: String = "1.2.1"
    ) {
        self.isRunning = isRunning
        self.host = host
        self.port = port
        self.deviceName = deviceName
        self.version = version
    }

    public func toDictionary() -> [String: Any] {
        return [
            "isRunning": isRunning,
            "host": host,
            "port": port,
            "deviceName": deviceName,
            "version": version
        ]
    }
}
