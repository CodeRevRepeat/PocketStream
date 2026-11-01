import Foundation
import Network

public class MobileDiscoveryManager: ObservableObject {
    public static let shared = MobileDiscoveryManager()

    @Published public private(set) var discoveredTvs: [TvInfo] = []
    @Published public private(set) var connectedTv: TvInfo?
    @Published public private(set) var tvCapabilities: TvCapabilities = .default1080P

    private var browser: NWBrowser?
    private var udpListener: NWListener?
    private var broadcastTimer: Timer?

    private init() {
        restoreLastConnectedTv()
    }

    public func startDiscovery() {
        startBonjourDiscovery()
        startUdpListener()
        startPeriodicUdpBroadcast()
    }

    public func stopDiscovery() {
        browser?.cancel()
        browser = nil
        udpListener?.cancel()
        udpListener = nil
        broadcastTimer?.invalidate()
        broadcastTimer = nil
    }

    public func connectToTv(info: TvInfo) {
        connectedTv = info
        UserDefaults.standard.set(info.ip, forKey: "last_tv_ip")
        UserDefaults.standard.set(info.port, forKey: "last_tv_port")
        UserDefaults.standard.set(info.name, forKey: "last_tv_name")

        fetchTvCapabilities(tvIp: info.ip, tvPort: info.port)
        sendPairHandshake(tvIp: info.ip, tvPort: info.port)
    }

    private func restoreLastConnectedTv() {
        let defaults = UserDefaults.standard
        if let ip = defaults.string(forKey: "last_tv_ip"), !ip.isEmpty {
            let port = defaults.integer(forKey: "last_tv_port") == 0 ? 8085 : defaults.integer(forKey: "last_tv_port")
            let name = defaults.string(forKey: "last_tv_name") ?? "Android TV"
            let info = TvInfo(ip: ip, port: port, name: name)
            connectedTv = info
            fetchTvCapabilities(tvIp: ip, tvPort: port)
        }
    }

    public func fetchTvCapabilities(tvIp: String, tvPort: Int) {
        guard let url = URL(string: "http://\(tvIp):\(tvPort)/capabilities") else { return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3.0

        URLSession.shared.dataTask(with: request) { [weak self] data, _, error in
            guard let self = self, let data = data,
                  let json = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any] else {
                return
            }
            let caps = TvCapabilities.from(dictionary: json)
            DispatchQueue.main.async {
                self.tvCapabilities = caps
            }
        }.resume()
    }

    public func sendPairHandshake(tvIp: String, tvPort: Int) {
        guard let url = URL(string: "http://\(tvIp):\(tvPort)/pair") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let localIp = NetworkUtils.getLocalWifiIpAddress()
        let dict: [String: Any] = [
            "phoneIp": localIp,
            "phonePort": 8080,
            "deviceName": "iPhone / PocketStream"
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: dict, options: [])

        URLSession.shared.dataTask(with: request) { [weak self] data, _, _ in
            guard let self = self, let data = data,
                  let json = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any],
                  let capsDict = json["capabilities"] as? [String: Any] else {
                return
            }
            let caps = TvCapabilities.from(dictionary: capsDict)
            DispatchQueue.main.async {
                self.tvCapabilities = caps
            }
        }.resume()
    }

    private func startBonjourDiscovery() {
        let descriptor = NWBrowser.Descriptor.bonjour(type: DiscoveryConstants.nsdServiceTypeTv, domain: "local.")
        let params = NWParameters()
        browser = NWBrowser(for: descriptor, using: params)

        browser?.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self = self else { return }
            for result in results {
                if case let .service(name, _, _, _) = result.endpoint {
                    self.resolveBonjourEndpoint(result.endpoint, serviceName: name)
                }
            }
        }
        browser?.start(queue: .global(qos: .userInitiated))
    }

    private func resolveBonjourEndpoint(_ endpoint: NWEndpoint, serviceName: String) {
        let connection = NWConnection(to: endpoint, using: .tcp)
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            if case .ready = state {
                if let innerEndpoint = connection.currentPath?.remoteEndpoint,
                   case let .hostPort(host, port) = innerEndpoint {
                    let ip = "\(host)".replacingOccurrences(of: "%en0", with: "")
                    let tv = TvInfo(ip: ip, port: Int(port.rawValue), name: serviceName)
                    DispatchQueue.main.async {
                        if !self.discoveredTvs.contains(where: { $0.ip == tv.ip }) {
                            self.discoveredTvs.append(tv)
                            if self.connectedTv == nil {
                                self.connectToTv(info: tv)
                            }
                        }
                    }
                }
                connection.cancel()
            }
        }
        connection.start(queue: .global(qos: .userInitiated))
    }

    private func startUdpListener() {
        do {
            let params = NWParameters.udp
            params.allowLocalEndpointReuse = true
            guard let port = NWEndpoint.Port(rawValue: DiscoveryConstants.udpDiscoveryPort) else { return }
            udpListener = try NWListener(using: params, on: port)

            udpListener?.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .global(qos: .userInitiated))
                connection.receiveMessage { data, _, _, _ in
                    guard let self = self, let data = data,
                          let json = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any] else {
                        return
                    }
                    if let type = json["type"] as? String, type == "TV_ANNOUNCE" {
                        if let tv = TvInfo.from(dictionary: json) {
                            DispatchQueue.main.async {
                                if !self.discoveredTvs.contains(where: { $0.ip == tv.ip }) {
                                    self.discoveredTvs.append(tv)
                                    if self.connectedTv == nil {
                                        self.connectToTv(info: tv)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            udpListener?.start(queue: .global(qos: .userInitiated))
        } catch {
            print("Failed to start UDP discovery listener: \(error)")
        }
    }

    private func startPeriodicUdpBroadcast() {
        broadcastTimer?.invalidate()
        var attempts = 0
        broadcastTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] timer in
            guard let self = self else { timer.invalidate(); return }
            self.sendUdpDiscoveryBroadcast()
            attempts += 1
            if attempts >= 10 {
                timer.invalidate()
            }
        }
    }

    private func sendUdpDiscoveryBroadcast() {
        let localIp = NetworkUtils.getLocalWifiIpAddress()
        let dict: [String: Any] = [
            "type": "DISCOVER_TV",
            "phoneIp": localIp,
            "phonePort": 8080,
            "deviceName": "iPhone / PocketStream"
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: dict, options: []) else { return }

        guard let port = NWEndpoint.Port(rawValue: DiscoveryConstants.udpDiscoveryPort) else { return }
        let endpoint = NWEndpoint.hostPort(host: "255.255.255.255", port: port)
        let connection = NWConnection(to: endpoint, using: .udp)
        connection.start(queue: .global(qos: .userInitiated))
        connection.send(content: data, completion: .contentProcessed({ _ in
            connection.cancel()
        }))
    }
}
