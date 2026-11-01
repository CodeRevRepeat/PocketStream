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
            sendResponse(connection: connection, status: "200 OK", headers: ["Content-Length": "0"], body: Data())
            return
        }

        switch path {
        case "/api/v1/status":
            handleStatus(connection: connection)
        case "/api/v1/library":
            handleLibrary(connection: connection)
        case "/api/v1/transcode/status":
            handleTranscodeStatus(queryString: queryString, connection: connection)
        case "/api/v1/play":
            handlePlay(postBody: postBody, connection: connection)
        case "/api/v1/stop":
            handleStop(connection: connection)
        case "/api/v1/transcode/seek":
            handleTranscodeSeek(postBody: postBody, connection: connection)
        default:
            if path.hasPrefix("/stream/live/") {
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

    private func handleLibrary(connection: NWConnection) {
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

    private func handlePlay(postBody: String, connection: NWConnection) {
        guard let data = postBody.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any],
              let mediaId = json["mediaId"] as? String,
              let media = MediaRepository.shared.getMedia(by: mediaId) else {
            sendResponse(connection: connection, status: "400 Bad Request", headers: [:], body: Data())
            return
        }

        let ip = NetworkUtils.getLocalWifiIpAddress()
        let isAlreadyTranscoded = TranscoderManager.shared.getTranscodedFile(id: media.id) != nil

        var resJson: [String: Any] = [:]
        if media.is10Bit && !isAlreadyTranscoded {
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
            let rangeSpec = rangeHeader.replacingOccurrences(of: "bytes=", with: "")
            let parts = rangeSpec.components(separatedBy: "-")
            let start = Int64(parts[0]) ?? 0
            let end = (parts.count > 1 && !parts[1].isEmpty) ? (Int64(parts[1]) ?? (fileSize - 1)) : (fileSize - 1)
            let chunkLength = max(0, min(end - start + 1, fileSize - start))

            fileHandle.seek(toFileOffset: UInt64(start))
            let chunk = fileHandle.readData(ofLength: Int(chunkLength))
            fileHandle.closeFile()

            var respHeaders: [String: String] = [
                "Content-Type": mimeType,
                "Accept-Ranges": "bytes",
                "Content-Range": "bytes \(start)-\(start + Int64(chunk.count) - 1)/\(fileSize)",
                "Content-Length": "\(chunk.count)"
            ]
            sendResponse(connection: connection, status: "206 Partial Content", headers: respHeaders, body: chunk)
        } else {
            // First 1MB chunk to begin playback instantly
            let initialChunkSize = min(Int64(2 * 1024 * 1024), fileSize)
            let chunk = fileHandle.readData(ofLength: Int(initialChunkSize))
            fileHandle.closeFile()

            let respHeaders: [String: String] = [
                "Content-Type": mimeType,
                "Accept-Ranges": "bytes",
                "Content-Length": "\(fileSize)"
            ]
            sendResponse(connection: connection, status: "200 OK", headers: respHeaders, body: chunk)
        }
    }

    private func sendResponse(connection: NWConnection, status: String, headers: [String: String], body: Data) {
        var headerStr = "HTTP/1.1 \(status)\r\n"
        headerStr += "Access-Control-Allow-Origin: *\r\n"
        headerStr += "Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n"
        headerStr += "Access-Control-Allow-Headers: Range, Content-Type, Accept\r\n"
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
