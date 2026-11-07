import Foundation
import Network

public class PocketHttpServer: ObservableObject {
    public static let shared = PocketHttpServer()

    private var listener: NWListener?
    private let port: UInt16 = 8080
    @Published public private(set) var isRunning: Bool = false
    @Published public private(set) var serverUrl: String = ""

    public var onPlaybackStarted: ((_ id: String, _ title: String, _ durationMs: Int64) -> Void)?
    public var onPlaybackStopped: (() -> Void)?
    public var onSeekRequested: ((_ media: LocalMedia, _ positionMs: Int64) -> Void)?

    private init() {}

    public func start() {
        guard !isRunning else { return }

        do {
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.enableKeepalive = true
            let params = NWParameters(tls: nil, tcp: tcpOptions)
            params.allowLocalEndpointReuse = true

            guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }
            let newListener = try NWListener(using: params, on: nwPort)

            newListener.stateUpdateHandler = { [weak self] state in
                guard let self = self else { return }
                DispatchQueue.main.async {
                    switch state {
                    case .ready:
                        self.isRunning = true
                        let ip = NetworkUtils.getLocalWifiIpAddress()
                        self.serverUrl = "http://\(ip):\(self.port)"
                    case .failed, .cancelled:
                        self.isRunning = false
                        self.serverUrl = ""
                    default:
                        break
                    }
                }
            }

            newListener.newConnectionHandler = { [weak self] connection in
                self?.handleConnection(connection)
            }

            newListener.start(queue: .global(qos: .userInitiated))
            self.listener = newListener

            let ip = NetworkUtils.getLocalWifiIpAddress()
            DispatchQueue.main.async {
                self.isRunning = true
                self.serverUrl = "http://\(ip):\(self.port)"
            }
        } catch {
            print("Failed to start HTTP server: \(error)")
        }
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        TranscoderManager.shared.purgeAllCache()
        DispatchQueue.main.async {
            self.isRunning = false
            self.serverUrl = ""
            self.onPlaybackStopped?()
        }
    }

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: .global(qos: .userInitiated))
        readRequest(connection: connection)
    }

    private func readRequest(connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self, let data = data, !data.isEmpty else {
                if isComplete { connection.cancel() }
                return
            }

            self.processHttpRequest(data: data, connection: connection)
        }
    }

    private func processHttpRequest(data: Data, connection: NWConnection) {
        guard let requestString = String(data: data, encoding: .utf8) else {
            sendResponse(connection: connection, status: "400 Bad Request", headers: [:], body: Data())
            return
        }

        let lines = requestString.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            sendResponse(connection: connection, status: "400 Bad Request", headers: [:], body: Data())
            return
        }

        let parts = requestLine.components(separatedBy: " ")
        guard parts.count >= 2 else {
            sendResponse(connection: connection, status: "400 Bad Request", headers: [:], body: Data())
            return
        }

        let method = parts[0].uppercased()
        let fullPath = parts[1]
        let path = fullPath.components(separatedBy: "?").first ?? fullPath
        let queryString = fullPath.contains("?") ? String(fullPath.split(separator: "?")[1]) : ""

        var headers: [String: String] = [:]
        var bodyStartIndex = 0
        for (idx, line) in lines.enumerated() {
            if idx == 0 { continue }
            if line.isEmpty {
                bodyStartIndex = idx + 1
                break
            }
            let headerParts = line.split(separator: ":", maxSplits: 1).map { String($0).trimmingCharacters(in: .whitespaces) }
            if headerParts.count == 2 {
                headers[headerParts[0].lowercased()] = headerParts[1]
            }
        }

        var postBody = ""
        if bodyStartIndex < lines.count {
            postBody = lines[bodyStartIndex...].joined(separator: "\r\n")
        }

        if method == "OPTIONS" {
            sendResponse(connection: connection, status: "405 Method Not Allowed", headers: ["Content-Length": "0"], body: Data())
            return
        }

        switch path {
        case "/api/v1/status":
            handleStatus(connection: connection)
        case "/api/v1/library":
            handleLibrary(headers: headers, connection: connection)
        case "/api/v1/transcode/status":
            handleTranscodeStatus(queryString: queryString, connection: connection)
        case "/api/v1/play":
            handlePlay(postBody: postBody, headers: headers, connection: connection)
        case "/api/v1/stop":
            handleStop(connection: connection)
        case "/disconnect", "/api/v1/disconnect":
            handleDisconnect(connection: connection)
        case "/api/v1/transcode/seek":
            handleTranscodeSeek(postBody: postBody, connection: connection)
        default:
            if path.hasPrefix("/api/v1/thumbnail/") {
                let mediaId = path.replacingOccurrences(of: "/api/v1/thumbnail/", with: "").components(separatedBy: "/")[0].components(separatedBy: "?")[0]
                handleThumbnail(mediaId: mediaId, connection: connection)
            } else if path.hasPrefix("/stream/live/") {
                let mediaId = path.replacingOccurrences(of: "/stream/live/", with: "").components(separatedBy: "/")[0]
                handleStreamFile(mediaId: mediaId, isLive: true, headers: headers, connection: connection)
            } else if path.hasPrefix("/stream/transcoded/") {
                let mediaId = path.replacingOccurrences(of: "/stream/transcoded/", with: "").components(separatedBy: "/")[0]
                handleStreamFile(mediaId: mediaId, isLive: false, headers: headers, connection: connection)
            } else if path.hasPrefix("/stream/") {
                let mediaId = path.replacingOccurrences(of: "/stream/", with: "").components(separatedBy: "/")[0]
                handleStreamDirect(mediaId: mediaId, headers: headers, connection: connection)
            } else {
                sendResponse(connection: connection, status: "404 Not Found", headers: [:], body: "Not Found: \(path)".data(using: .utf8) ?? Data())
            }
        }
    }

    private func handleStatus(connection: NWConnection) {
        let ip = NetworkUtils.getLocalWifiIpAddress()
        let name = "iPhone / PocketStream"
        let status = ServerStatus(isRunning: true, host: ip, port: Int(port), deviceName: name, version: "1.2.1")
        if let json = try? JSONSerialization.data(withJSONObject: status.toDictionary(), options: []) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "application/json"], body: json)
        }
    }

    private func handleThumbnail(mediaId: String, connection: NWConnection) {
        let thumbUrl = MediaRepository.shared.getThumbnailFile(id: mediaId)
        if FileManager.default.fileExists(atPath: thumbUrl.path), let data = try? Data(contentsOf: thumbUrl) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "image/jpeg", "Cache-Control": "public, max-age=86400"], body: data)
            return
        }
        if let media = MediaRepository.shared.getMedia(by: mediaId), let generated = MediaRepository.shared.generateThumbnail(for: media), let data = try? Data(contentsOf: generated) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "image/jpeg", "Cache-Control": "public, max-age=86400"], body: data)
            return
        }
        sendResponse(connection: connection, status: "404 Not Found", headers: [:], body: "Thumbnail not found".data(using: .utf8) ?? Data())
    }

    private func handleLibrary(headers: [String: String], connection: NWConnection) {
        if let tvIp = headers["x-ps-tv-ip"], !tvIp.isEmpty {
            let tvPort = Int(headers["x-ps-tv-port"] ?? "") ?? 8085
            let tvName = headers["x-ps-tv-name"] ?? "PocketStream TV"
            let sessionId = headers["x-ps-tv-sid"] ?? ""
            let sessionKey = headers["x-ps-tv-key"] ?? ""
            DispatchQueue.main.async {
                let info = TvInfo(ip: tvIp, port: tvPort, name: tvName, sessionId: sessionId, sessionKey: sessionKey)
                MobileDiscoveryManager.shared.connectToTv(info: info)
            }
        }
        let items = MediaRepository.shared.getAllMediaItems().map { $0.toDictionary() }
        if let json = try? JSONSerialization.data(withJSONObject: items, options: []) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "application/json"], body: json)
        }
    }

    private func handleTranscodeStatus(queryString: String, connection: NWConnection) {
        var mediaId = ""
        for param in queryString.split(separator: "&") {
            let pair = param.split(separator: "=")
            if pair.count == 2 && pair[0] == "id" {
                mediaId = String(pair[1])
            }
        }

        let isTranscoding = TranscoderManager.shared.isTranscoding(id: mediaId)
        let progress = TranscoderManager.shared.getProgress(id: mediaId)
        let isReady = TranscoderManager.shared.getTranscodedFile(id: mediaId) != nil

        let dict: [String: Any] = [
            "id": mediaId,
            "isTranscoding": isTranscoding,
            "progress": progress,
            "isReady": isReady
        ]
        if let json = try? JSONSerialization.data(withJSONObject: dict, options: []) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "application/json"], body: json)
        }
    }

    private func handlePlay(postBody: String, headers: [String: String], connection: NWConnection) {
        guard let data = postBody.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any],
              let mediaId = json["mediaId"] as? String,
              let media = MediaRepository.shared.getMedia(by: mediaId) else {
            sendResponse(connection: connection, status: "400 Bad Request", headers: [:], body: Data())
            return
        }

        let tvIp = (json["tvIp"] as? String) ?? headers["x-ps-tv-ip"] ?? ""
        if !tvIp.isEmpty {
            let tvPort = (json["tvPort"] as? Int) ?? Int(headers["x-ps-tv-port"] ?? "") ?? 8085
            let tvName = (json["tvName"] as? String) ?? headers["x-ps-tv-name"] ?? "PocketStream TV"
            let sessionId = (json["sessionId"] as? String) ?? headers["x-ps-tv-sid"] ?? ""
            let sessionKey = (json["sessionKey"] as? String) ?? headers["x-ps-tv-key"] ?? ""
            DispatchQueue.main.async {
                let info = TvInfo(ip: tvIp, port: tvPort, name: tvName, sessionId: sessionId, sessionKey: sessionKey)
                MobileDiscoveryManager.shared.connectToTv(info: info)
            }
        }

        let ip = NetworkUtils.getLocalWifiIpAddress()
        let isAlreadyTranscoded = TranscoderManager.shared.getTranscodedFile(id: media.id) != nil
        let requestedMode = (json["mode"] as? String)?.lowercased() ?? "direct"

        var resJson: [String: Any] = [:]
        if requestedMode == "transcode" && !isAlreadyTranscoded {
            let streamUrl = "http://\(ip):\(port)/stream/live/\(media.id)"
            TranscoderManager.shared.startLiveTranscode(media: media, onReadyToStream: { _ in })
            resJson["status"] = "ok"
            resJson["mode"] = "transcode"
            resJson["streamUrl"] = streamUrl
            resJson["title"] = "[Converted] \(media.title)"
            resJson["durationMs"] = media.durationMs
            resJson["message"] = "Encoding using phone and started the stream"
            DispatchQueue.main.async {
                self.onPlaybackStarted?(media.id, "[Converted] \(media.title)", media.durationMs)
            }
        } else {
            // Direct Play is preferred in all scenarios (ADR-001)
            let streamUrl = isAlreadyTranscoded ? "http://\(ip):\(port)/stream/transcoded/\(media.id)" : "http://\(ip):\(port)/stream/\(media.id)"
            resJson["status"] = "ok"
            resJson["mode"] = "direct"
            resJson["streamUrl"] = streamUrl
            resJson["title"] = media.title
            resJson["durationMs"] = media.durationMs
            resJson["message"] = "Direct stream started."
            DispatchQueue.main.async {
                self.onPlaybackStarted?(media.id, media.title, media.durationMs)
            }
        }

        if let resData = try? JSONSerialization.data(withJSONObject: resJson, options: []) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "application/json"], body: resData)
        }
    }

    private func handleStop(connection: NWConnection) {
        DispatchQueue.main.async {
            self.onPlaybackStopped?()
        }
        let dict = ["status": "stopped"]
        if let json = try? JSONSerialization.data(withJSONObject: dict, options: []) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "application/json"], body: json)
        }
    }

    private func handleDisconnect(connection: NWConnection) {
        DispatchQueue.main.async {
            MobileDiscoveryManager.shared.disconnectTv()
            self.onPlaybackStopped?()
        }
        let dict = ["status": "ok"]
        if let json = try? JSONSerialization.data(withJSONObject: dict, options: []) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "application/json"], body: json)
        }
    }

    private func handleTranscodeSeek(postBody: String, connection: NWConnection) {
        guard let data = postBody.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any],
              let mediaId = json["mediaId"] as? String,
              let positionMs = json["positionMs"] as? Int64,
              let media = MediaRepository.shared.getMedia(by: mediaId) else {
            sendResponse(connection: connection, status: "400 Bad Request", headers: [:], body: Data())
            return
        }

        DispatchQueue.main.async {
            self.onSeekRequested?(media, positionMs)
        }

        let dict: [String: Any] = ["status": "seeking", "mediaId": mediaId, "positionMs": positionMs]
        if let resData = try? JSONSerialization.data(withJSONObject: dict, options: []) {
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Type": "application/json"], body: resData)
        }
    }

    private func handleStreamDirect(mediaId: String, headers: [String: String], connection: NWConnection) {
        guard let media = MediaRepository.shared.getMedia(by: mediaId) else {
            sendResponse(connection: connection, status: "404 Not Found", headers: [:], body: Data())
            return
        }
        serveFile(url: media.url, mimeType: media.mimeType, headers: headers, connection: connection)
    }

    private func handleStreamFile(mediaId: String, isLive: Bool, headers: [String: String], connection: NWConnection) {
        let fileUrl: URL?
        if isLive {
            fileUrl = TranscoderManager.shared.getActiveOrCompletedFile(id: mediaId)
        } else {
            fileUrl = TranscoderManager.shared.getTranscodedFile(id: mediaId)
        }

        guard let targetUrl = fileUrl, FileManager.default.fileExists(atPath: targetUrl.path) else {
            sendResponse(connection: connection, status: "503 Service Unavailable", headers: [:], body: "Stream initializing...".data(using: .utf8) ?? Data())
            return
        }

        serveFile(url: targetUrl, mimeType: "video/mp4", headers: headers, connection: connection)
    }

    private func serveFile(url: URL, mimeType: String, headers: [String: String], connection: NWConnection) {
        guard let fileHandle = try? FileHandle(forReadingFrom: url) else {
            sendResponse(connection: connection, status: "500 Internal Server Error", headers: [:], body: Data())
            return
        }

        let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0

        if let rangeHeader = headers["range"], rangeHeader.hasPrefix("bytes=") {
            let rangeSpec = rangeHeader.replacingOccurrences(of: "bytes=", with: "").trimmingCharacters(in: .whitespaces)
            let parts = rangeSpec.components(separatedBy: "-")
            let start = Int64(parts[0]) ?? 0
            let end = (parts.count > 1 && !parts[1].isEmpty) ? (Int64(parts[1]) ?? (fileSize - 1)) : (fileSize - 1)

            if start > end || start >= fileSize {
                try? fileHandle.close()
                var respHeaders: [String: String] = [
                    "Content-Range": "bytes */\(fileSize)",
                    "Content-Length": "0"
                ]
                sendResponse(connection: connection, status: "416 Range Not Satisfiable", headers: respHeaders, body: Data())
                return
            }

            let boundedEnd = min(end, fileSize - 1)
            let rangeLength = boundedEnd - start + 1

            do {
                try fileHandle.seek(toOffset: UInt64(start))
            } catch {
                try? fileHandle.close()
                sendResponse(connection: connection, status: "500 Internal Server Error", headers: [:], body: Data())
                return
            }

            var respHeaders: [String: String] = [
                "Content-Type": mimeType,
                "Accept-Ranges": "bytes",
                "Content-Range": "bytes \(start)-\(boundedEnd)/\(fileSize)",
                "Content-Length": "\(rangeLength)"
            ]

            sendHeadersAndStream(fileHandle: fileHandle, remainingLength: rangeLength, status: "206 Partial Content", headers: respHeaders, connection: connection)
        } else {
            var respHeaders: [String: String] = [
                "Content-Type": mimeType,
                "Accept-Ranges": "bytes",
                "Content-Length": "\(fileSize)"
            ]

            sendHeadersAndStream(fileHandle: fileHandle, remainingLength: fileSize, status: "200 OK", headers: respHeaders, connection: connection)
        }
    }

    private func sendHeadersAndStream(fileHandle: FileHandle, remainingLength: Int64, status: String, headers: [String: String], connection: NWConnection) {
        var headerStr = "HTTP/1.1 \(status)\r\n"
        headerStr += "Connection: close\r\n"

        for (k, v) in headers {
            headerStr += "\(k): \(v)\r\n"
        }
        headerStr += "\r\n"

        guard let headerData = headerStr.data(using: .utf8) else {
            try? fileHandle.close()
            connection.cancel()
            return
        }

        connection.send(content: headerData, isComplete: false, completion: .contentProcessed({ [weak self] error in
            if let _ = error {
                try? fileHandle.close()
                connection.cancel()
                return
            }
            self?.streamNextChunk(fileHandle: fileHandle, remainingLength: remainingLength, connection: connection)
        }))
    }

    private func streamNextChunk(fileHandle: FileHandle, remainingLength: Int64, connection: NWConnection) {
        guard remainingLength > 0 else {
            try? fileHandle.close()
            connection.cancel()
            return
        }

        let chunkSize = Int(min(Int64(256 * 1024), remainingLength)) // 256 KB bounded read window
        let chunkData: Data
        do {
            chunkData = try fileHandle.read(upToCount: chunkSize) ?? Data()
        } catch {
            try? fileHandle.close()
            connection.cancel()
            return
        }

        if chunkData.isEmpty {
            try? fileHandle.close()
            connection.cancel()
            return
        }

        let newRemaining = remainingLength - Int64(chunkData.count)
        let isDone = (newRemaining <= 0)

        connection.send(content: chunkData, isComplete: isDone, completion: .contentProcessed({ [weak self] error in
            if let _ = error {
                try? fileHandle.close()
                connection.cancel()
                return
            }
            if isDone {
                try? fileHandle.close()
                connection.cancel()
            } else {
                self?.streamNextChunk(fileHandle: fileHandle, remainingLength: newRemaining, connection: connection)
            }
        }))
    }

    private func sendResponse(connection: NWConnection, status: String, headers: [String: String], body: Data) {
        var headerStr = "HTTP/1.1 \(status)\r\n"
        headerStr += "Connection: close\r\n"

        for (k, v) in headers {
            headerStr += "\(k): \(v)\r\n"
        }
        if headers["Content-Length"] == nil {
            headerStr += "Content-Length: \(body.count)\r\n"
        }
        headerStr += "\r\n"

        var responseData = headerStr.data(using: .utf8) ?? Data()
        responseData.append(body)

        connection.send(content: responseData, completion: .contentProcessed({ _ in
            connection.cancel()
        }))
    }
}
