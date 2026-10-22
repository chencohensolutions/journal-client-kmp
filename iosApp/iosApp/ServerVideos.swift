import AVFoundation
import UniformTypeIdentifiers
import SharedLogic

/// Videos only on the server, streamed from their original (`GET /media/{sha256}` with Range) into `Caches/videos`:
/// the parts played or buffered are kept, keyed by sha256, so a video swiped away after a few seconds keeps them, and
/// playing it again reads them here and fetches only what is missing. Like Android's ServerVideos.kt. AVPlayer can't
/// present a client certificate itself, so it reads `journal-media://<sha256>` through `ServerVideoLoader`, which
/// fetches with a URLSession that answers the mTLS challenges as the shared client does (`IosServerStream`).
enum ServerVideos {
    static let scheme = "journal-media"

    /// `item`'s original to play, streamed; nil when it has no sha256 or this device isn't registered. Main thread.
    static func asset(_ item: MediaItem) -> AVURLAsset? {
        guard let sha = item.sha256, ServerVideoSession.shared.stream.url(sha256: sha) != nil,
              let url = URL(string: "\(scheme)://\(sha)")
        else { return nil }
        let asset = AVURLAsset(url: url)
        let loader = ServerVideoLoader(sha: sha, fileName: item.fileName)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        // The resource loader holds its delegate weakly: the asset keeps it.
        objc_setAssociatedObject(asset, &loaderKey, loader, .OBJC_ASSOCIATION_RETAIN)
        return asset
    }
}

private var loaderKey: UInt8 = 0

/// Answers AVPlayer's requests for one video's bytes (its length and type first, then byte ranges) from
/// `ServerVideoCache`, fetching what isn't there from the server in pieces that grow from 512 kB to 4 MB, each kept
/// as it arrives. A request AVPlayer cancels (a seek, the player released) stops fetching. Its state is only touched on
/// `queue`.
final class ServerVideoLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    let queue = DispatchQueue(label: "ServerVideoLoader")
    private let sha: String
    private let fileName: String?
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(sha: String, fileName: String?) {
        self.sha = sha
        self.fileName = fileName
        super.init()
        ServerVideoCache.shared.retain(sha)
    }

    deinit {
        tasks.values.forEach { $0.cancel() }
        ServerVideoCache.shared.release(sha)
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        let id = ObjectIdentifier(request)
        tasks[id] = Task { [sha, fileName, weak self] in
            do {
                try await Self.load(request, sha: sha, fileName: fileName)
                request.finishLoading()
            } catch {
                if !Task.isCancelled { request.finishLoading(with: error) }
            }
            self?.queue.async { self?.tasks[id] = nil }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: ObjectIdentifier(request))?.cancel()
    }

    private static let firstPiece: Int64 = 512 << 10
    private static let largestPiece: Int64 = 4 << 20
    /// What is handed to AVPlayer at a time from the cache.
    private static let readSize: Int64 = 1 << 20

    private static func load(_ request: AVAssetResourceLoadingRequest, sha: String, fileName: String?) async throws {
        let cache = ServerVideoCache.shared
        var info = cache.info(sha)
        if info == nil {
            // The first bytes tell the length and type; they are kept like any others.
            let first = try await ServerVideoSession.shared.fetch(sha, from: 0, to: firstPiece - 1)
            cache.begin(sha, length: first.total, type: first.type)
            cache.write(sha, at: 0, first.data)
            info = (first.total, first.type)
        }
        guard let info else { return }
        let (length, type) = info
        if let content = request.contentInformationRequest {
            let ext = (fileName as NSString?)?.pathExtension ?? ""
            content.contentType = (UTType(mimeType: type) ?? UTType(filenameExtension: ext) ?? .mpeg4Movie).identifier
            content.contentLength = length
            content.isByteRangeAccessSupported = true
        }
        guard let data = request.dataRequest else { return }
        var offset = data.currentOffset
        let end = data.requestsAllDataToEndOfResource ? length : min(length, data.requestedOffset + Int64(data.requestedLength))
        var piece = firstPiece
        while offset < end {
            try Task.checkCancellation()
            if let cachedEnd = cache.cachedEnd(sha, at: offset),
               let bytes = cache.read(sha, at: offset, count: min(end, cachedEnd, offset + readSize) - offset), !bytes.isEmpty {
                data.respond(with: bytes)
                offset += Int64(bytes.count)
                continue
            }
            let upTo = min(end, cache.nextCached(sha, after: offset) ?? end, offset + piece)
            let fetched = try await ServerVideoSession.shared.fetch(sha, from: offset, to: upTo - 1).data
            guard !fetched.isEmpty else { throw URLError(.zeroByteResource) }
            cache.write(sha, at: offset, fetched)
            data.respond(with: fetched)
            offset += Int64(fetched.count)
            piece = min(piece * 2, largestPiece)
        }
    }
}

/// The URLSession videos stream with: the device's client certificate from the Keychain, and the server trusted by the
/// journal's CA, both answered by the shared logic (`IosServerStream`). Nothing is cached by URLSession.
final class ServerVideoSession: NSObject, URLSessionDelegate {
    static let shared = ServerVideoSession()

    let stream = IosJournal.shared.serverStream()
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        // One delegate call at a time: IosServerStream is called from one queue.
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        return URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }()

    /// Bytes `from`...`to` of `sha`'s original, with the original's whole length and MIME type.
    func fetch(_ sha: String, from: Int64, to: Int64) async throws -> (data: Data, total: Int64, type: String) {
        guard let url = stream.url(sha256: sha).flatMap(URL.init(string:)) else { throw URLError(.userAuthenticationRequired) }
        var request = URLRequest(url: url)
        request.setValue("bytes=\(from)-\(to)", forHTTPHeaderField: "Range")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 206,
              let range = http.value(forHTTPHeaderField: "Content-Range"),
              let total = range.split(separator: "/").last.flatMap({ Int64($0) })
        else { throw URLError(.badServerResponse) }
        return (data, total, http.mimeType ?? "")
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let credential: URLCredential?
        switch challenge.protectionSpace.authenticationMethod {
        case NSURLAuthenticationMethodServerTrust: credential = stream.serverTrust(challenge: challenge)
        case NSURLAuthenticationMethodClientCertificate: credential = stream.clientCertificate()
        default: return completionHandler(.performDefaultHandling, nil)
        }
        if let credential {
            completionHandler(.useCredential, credential)
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

/// The streamed videos kept on this phone, in `Caches/videos/<sha256>/`: `data` holds the video's bytes at their own
/// offsets, a sparse file, so the parts never fetched take no space, and `index.json` which parts those are, the
/// video's length and type, and when it was last played. It is `PhoneStorage`'s video cache: it shares the "Kept on
/// this phone" limit with the previews and originals of server photos, and gives way whole videos at a time, least
/// recently played first (never one that is open).
final class ServerVideoCache: NSObject, VideoCache {
    static let shared = ServerVideoCache()

    private struct Index: Codable {
        var v = 1
        var length: Int64
        var type: String
        /// The parts kept, as [from, to) pairs in order, none touching.
        var ranges: [[Int64]] = []
        /// When it was last opened, in seconds since 1970.
        var used: Double

        var bytes: Int64 { ranges.reduce(0) { $0 + $1[1] - $1[0] } }
    }

    private let directory: URL
    private let storage = IosJournal.shared.phoneStorage()
    private let lock = NSLock()
    /// Read from disk on first use.
    private var loaded: [String: Index]?
    /// How many players have each video open.
    private var open: [String: Int] = [:]

    private override init() {
        directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("videos")
        super.init()
    }

    /// Counts the cache in Settings → Storage on this phone and its Clear, and under the limit. Main thread.
    func attach() {
        storage.attachVideos(cache: self)
        storage.attachVideoRemoval { [weak self] sha in self?.remove(sha) }
    }

    /// Deletes one video, which the server no longer lists (deleted, or in a hidden collection); kept while it is open.
    func remove(_ sha: String) {
        locked {
            guard open[sha] == nil, entries[sha] != nil else { return }
            try? FileManager.default.removeItem(at: folder(sha))
            entries[sha] = nil
        }
    }

    func retain(_ sha: String) {
        locked {
            open[sha, default: 0] += 1
            if var index = entries[sha] {
                index.used = Date().timeIntervalSince1970
                save(sha, index)
            }
        }
    }

    func release(_ sha: String) {
        locked { open[sha] = open[sha].map { $0 - 1 }.flatMap { $0 > 0 ? $0 : nil } }
    }

    /// The video's length and MIME type, once its first bytes were fetched.
    func info(_ sha: String) -> (length: Int64, type: String)? {
        locked { entries[sha].map { ($0.length, $0.type) } }
    }

    func begin(_ sha: String, length: Int64, type: String) {
        locked {
            guard entries[sha] == nil else { return }
            try? FileManager.default.createDirectory(at: folder(sha), withIntermediateDirectories: true)
            FileManager.default.createFile(atPath: dataFile(sha).path, contents: nil)
            save(sha, Index(length: length, type: type, used: Date().timeIntervalSince1970))
        }
    }

    /// Where the kept part that `offset` is in ends; nil when `offset` isn't kept.
    func cachedEnd(_ sha: String, at offset: Int64) -> Int64? {
        locked { entries[sha]?.ranges.first { $0[0] <= offset && offset < $0[1] }?[1] }
    }

    /// Where the next kept part after `offset` starts.
    func nextCached(_ sha: String, after offset: Int64) -> Int64? {
        locked { entries[sha]?.ranges.first { $0[0] > offset }?[0] }
    }

    func read(_ sha: String, at offset: Int64, count: Int64) -> Data? {
        guard let file = try? FileHandle(forReadingFrom: dataFile(sha)) else { return nil }
        defer { try? file.close() }
        try? file.seek(toOffset: UInt64(offset))
        return try? file.read(upToCount: Int(count))
    }

    /// Keeps `data` at `offset`, then makes room under the limit.
    func write(_ sha: String, at offset: Int64, _ data: Data) {
        guard locked({ entries[sha] != nil }), let file = try? FileHandle(forWritingTo: dataFile(sha)) else { return }
        defer { try? file.close() }
        do {
            try file.seek(toOffset: UInt64(offset))
            try file.write(contentsOf: data)
        } catch {
            return
        }
        locked {
            // Cleared meanwhile: what was written isn't counted.
            guard var index = entries[sha] else { return }
            index.ranges = Self.adding(index.ranges, offset, offset + Int64(data.count))
            save(sha, index)
            evict()
        }
    }

    // MARK: VideoCache (called off the main thread)

    func usedBytes() -> Int64 {
        locked { entries.values.reduce(0) { $0 + $1.bytes } }
    }

    func cachedBytes(sha256: String) -> Int64 {
        locked { entries[sha256]?.bytes ?? 0 }
    }

    func clear() {
        locked {
            try? FileManager.default.removeItem(at: directory)
            loaded = [:]
        }
    }

    // MARK: Under the lock

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    private var entries: [String: Index] {
        get {
            if let loaded { return loaded }
            let read = load()
            loaded = read
            return read
        }
        set { loaded = newValue }
    }

    private func load() -> [String: Index] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        var found: [String: Index] = [:]
        for sha in names {
            if let data = try? Data(contentsOf: indexFile(sha)), let index = try? JSONDecoder().decode(Index.self, from: data),
               FileManager.default.fileExists(atPath: dataFile(sha).path) {
                found[sha] = index
            } else {
                // Damaged or half-made: dropped, it streams again.
                try? FileManager.default.removeItem(at: folder(sha))
            }
        }
        return found
    }

    private func save(_ sha: String, _ index: Index) {
        entries[sha] = index
        if let data = try? JSONEncoder().encode(index) { try? data.write(to: indexFile(sha), options: .atomic) }
    }

    /// Least recently played first, while the cache is over what videos may take now (`PhoneStorage.videoBudget`).
    private func evict() {
        let budget = storage.videoBudget()
        var total = entries.values.reduce(0) { $0 + $1.bytes }
        for (sha, index) in entries.filter({ open[$0.key] == nil }).sorted(by: { $0.value.used < $1.value.used }) {
            guard total > budget else { break }
            try? FileManager.default.removeItem(at: folder(sha))
            entries[sha] = nil
            total -= index.bytes
        }
    }

    private func folder(_ sha: String) -> URL { directory.appendingPathComponent(sha, isDirectory: true) }
    private func dataFile(_ sha: String) -> URL { folder(sha).appendingPathComponent("data") }
    private func indexFile(_ sha: String) -> URL { folder(sha).appendingPathComponent("index.json") }

    /// `ranges` with [from, to) added, merged with the parts it touches.
    private static func adding(_ ranges: [[Int64]], _ from: Int64, _ to: Int64) -> [[Int64]] {
        var from = from, to = to
        var kept: [[Int64]] = []
        for range in ranges {
            if range[1] < from || range[0] > to {
                kept.append(range)
            } else {
                from = min(from, range[0])
                to = max(to, range[1])
            }
        }
        kept.append([from, to])
        return kept.sorted { $0[0] < $1[0] }
    }
}
