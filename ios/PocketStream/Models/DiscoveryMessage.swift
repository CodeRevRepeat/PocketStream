import Foundation

public struct DiscoveryConstants {
    public static let udpDiscoveryPort: UInt16 = 8088
    public static let nsdServiceTypeTv = "_pocketstream-tv._tcp."
    public static let nsdServiceTypePhone = "_pocketstream-phone._tcp."
    public static let qrScheme = "pocketstream://connect"
}

public struct TvInfo: Codable, Equatable, Identifiable {
    public var id: String { "\(ip):\(port)" }
    public let ip: String
    public let port: Int
    public let name: String
    public let sessionId: String
    public let sessionKey: String

    public init(ip: String, port: Int = 8085, name: String = "PocketStream TV", sessionId: String = "", sessionKey: String = "") {
        self.ip = ip
        self.port = port
        self.name = name
        self.sessionId = sessionId
        self.sessionKey = sessionKey
    }

    public func toQrString() -> String {
        let encodedName = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name
        var base = "\(DiscoveryConstants.qrScheme)?ip=\(ip)&port=\(port)&name=\(encodedName)"
        if !sessionId.isEmpty { base += "&sid=\(sessionId)" }
        if !sessionKey.isEmpty { base += "&key=\(sessionKey)" }
        return base
    }

    public func toDictionary() -> [String: Any] {
        return [
            "type": "TV_ANNOUNCE",
            "ip": ip,
            "port": port,
            "name": name,
            "sessionId": sessionId
        ]
    }

    public static func from(dictionary: [String: Any]) -> TvInfo? {
        guard let ip = dictionary["ip"] as? String, !ip.isEmpty else { return nil }
        let port = dictionary["port"] as? Int ?? 8085
        let name = dictionary["name"] as? String ?? "PocketStream TV"
        let sessionId = dictionary["sessionId"] as? String ?? ""
        return TvInfo(ip: ip, port: port, name: name, sessionId: sessionId)
    }

    public static func from(qrString: String) -> TvInfo? {
        guard qrString.hasPrefix(DiscoveryConstants.qrScheme),
              let url = URL(string: qrString),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let queryItems = components.queryItems else {
            return nil
        }

        var ip = ""
        var port = 8085
        var name = "PocketStream TV"
        var sessionId = ""
        var sessionKey = ""

        for item in queryItems {
            switch item.name {
            case "ip":
                ip = item.value ?? ""
            case "port":
                if let p = Int(item.value ?? "") {
                    port = p
                }
            case "name":
                name = item.value ?? "PocketStream TV"
            case "sid":
                sessionId = item.value ?? ""
            case "key":
                sessionKey = item.value ?? ""
            default:
                break
            }
        }

        guard !ip.isEmpty else { return nil }
        return TvInfo(ip: ip, port: port, name: name, sessionId: sessionId, sessionKey: sessionKey)
    }
}

public struct PhoneInfo: Codable {
    public let ip: String
    public let port: Int
    public let name: String

    public init(ip: String, port: Int = 8080, name: String = "PocketStream iPhone") {
        self.ip = ip
        self.port = port
        self.name = name
    }

    public func toDictionary() -> [String: Any] {
        return [
            "type": "PHONE_ANNOUNCE",
            "ip": ip,
            "port": port,
            "name": name
        ]
    }
}
