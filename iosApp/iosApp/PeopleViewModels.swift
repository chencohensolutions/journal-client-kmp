import Foundation
import Network
import OnnxRuntimeBindings
import SharedLogic

/// Bridges the shared Kotlin `PeopleController` (Explore › People: the people and groups of faces on the server) into
/// SwiftUI. One per app, shared by People, person pages and the viewer's details.
final class PeopleViewModel: ObservableObject {
    @Published private(set) var state: PeopleState

    let controller = IosJournal.shared.peopleController()
    private var observation: ObservationHandle?

    init() {
        state = controller.currentState
        observation = controller.observe { [weak self] newState in self?.state = newState }
    }

    deinit { observation?.cancel() }

    func load() { controller.load() }
    func reload() { controller.reload() }

    // Correcting faces: sent to the server, or (offline) applied on this phone and queued.
    func confirm(_ face: Face, _ personId: String) { controller.confirm(face: face, personId: personId) }
    func notPerson(_ face: Face, _ personId: String) { controller.notPerson(face: face, personId: personId) }
    func moveFace(_ face: Face, _ personId: String) { controller.moveFace(face: face, personId: personId) }
    func nameFace(_ face: Face, _ name: String) { controller.nameFace(face: face, name: name) }

    var faceActions: FaceActions {
        FaceActions(confirm: confirm, notPerson: notPerson, move: moveFace, name: nameFace)
    }
}

/// Bridges a shared `PersonController` (a person's page, or a group's "Who's this?") into SwiftUI; one per page.
final class PersonViewModel: ObservableObject {
    @Published private(set) var state: PersonState

    private let controller: PersonController
    private var observation: ObservationHandle?

    init(id: String, library: LibraryViewModel) {
        controller = IosJournal.shared.createPersonController(id: id, library: library.controller)
        state = controller.currentState
        observation = controller.observe { [weak self] newState in self?.state = newState }
    }

    deinit {
        observation?.cancel()
        controller.close()
    }

    func load() { controller.load() }
    func setQuery(_ text: String) { controller.setQuery(text: text) }
    func setSort(_ sort: PageSort) { controller.setSort(sort: sort) }
    func setName(_ text: String) { controller.setName(text: text) }
    func rename(_ name: String) { controller.rename(name: name) }
    func join(_ personId: String) { controller.join(personId: personId) }
    func merge(_ otherId: String) { controller.merge(otherId: otherId) }
    func forget() { controller.forget() }
    func notThem(_ item: MediaItem) { controller.notThem(item: item) }
    func notThem(faces: [Face]) { controller.notThem(faces: faces) }
}

/// Bridges the shared `PhotoFindingsController` (what the server found in the photo the viewer shows) into SwiftUI.
final class FindingsViewModel: ObservableObject {
    @Published private(set) var state: PhotoFindings

    private let controller: PhotoFindingsController
    private var observation: ObservationHandle?

    init(library: LibraryViewModel) {
        controller = IosJournal.shared.createPhotoFindingsController(library: library.controller)
        state = controller.currentState
        observation = controller.observe { [weak self] newState in self?.state = newState }
    }

    deinit {
        observation?.cancel()
        controller.close()
    }

    func show(_ item: MediaItem) { controller.show(item: item) }
}

/// Bridges the shared `LocalIndexes` (Settings › Search › On this phone) into SwiftUI.
final class PhoneSearchViewModel: ObservableObject {
    @Published private(set) var state: PhoneSearchState

    let indexes = IosJournal.shared.localIndexes()
    private var observation: ObservationHandle?

    init() {
        state = indexes.currentState
        observation = indexes.observe { [weak self] newState in self?.state = newState }
    }

    deinit { observation?.cancel() }

    func refresh() { indexes.refresh() }
    func set(_ kind: PhoneIndexKind, _ on: Bool) { indexes.set(kind: kind, on: on) }
    func setWifiOnly(_ on: Bool) { indexes.setWifiOnly(on: on) }
    func updateNow() { indexes.updateNow() }
    func pause() { indexes.pause() }
    func updateIfDue() { indexes.updateIfDue() }
}

/// Whether the phone is on Wi-Fi (or a network that isn't expensive), for On this phone's "Only on Wi-Fi".
final class NetworkWatch {
    static let shared = NetworkWatch()

    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var cheap = true

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            lock.lock()
            cheap = path.status == .satisfied && !path.isExpensive && !path.isConstrained
            lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "network-watch"))
    }

    var onWifi: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cheap
    }
}

/// The search model's text half on this phone, with ONNX Runtime, as the server runs it: `input_ids` (1 × 64, int64)
/// in, the vector from `pooler_output` out. The session is made on the first search and kept until `releaseModel`.
final class OnnxTextEncoding: NSObject, TextEncoding {
    private var env: ORTEnv?
    private var session: (path: String, session: ORTSession)?
    private let lock = NSLock()

    func encode(modelPath: String, tokens: KotlinLongArray) -> KotlinFloatArray? {
        lock.lock()
        defer { lock.unlock() }
        do {
            if env == nil { env = try ORTEnv(loggingLevel: .warning) }
            guard let env else { return nil }
            if session?.path != modelPath {
                let options = try ORTSessionOptions()
                try options.setIntraOpNumThreads(4)
                session = (modelPath, try ORTSession(env: env, modelPath: modelPath, sessionOptions: options))
            }
            guard let session = session?.session else { return nil }
            var ids = (0..<Int(tokens.size)).map { tokens.get(index: Int32($0)) }
            let data = NSMutableData(bytes: &ids, length: ids.count * MemoryLayout<Int64>.size)
            let input = try ORTValue(tensorData: data, elementType: .int64, shape: [1, NSNumber(value: ids.count)])
            let names = try session.outputNames()
            let output = names.contains("pooler_output") ? "pooler_output" : names[0]
            let outputs = try session.run(withInputs: ["input_ids": input], outputNames: [output], runOptions: nil)
            guard let value = outputs[output] else { return nil }
            let bytes = try value.tensorData() as Data
            let count = bytes.count / MemoryLayout<Float>.size
            let vector = KotlinFloatArray(size: Int32(count))
            bytes.withUnsafeBytes { raw in
                let floats = raw.bindMemory(to: Float.self)
                for i in 0..<count { vector.set(index: Int32(i), value: floats[i]) }
            }
            return vector
        } catch {
            return nil
        }
    }

    func releaseModel() {
        lock.lock()
        session = nil
        lock.unlock()
    }
}
