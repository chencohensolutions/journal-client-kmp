import CoreLocation
import NetworkExtension

/// The name of the Wi-Fi network the phone is on, for Settings › Backup › "Only on these Wi-Fi networks". iOS tells it
/// only to an app signed with the Access WiFi Information entitlement (opt-in: `WIFI_ENTITLEMENTS` in Local.xcconfig,
/// as it needs the owner's team) that may use the location while in use. Never on the simulator.
enum WifiNetworks {
    /// The current network's name; nil off Wi-Fi, without location permission, or without the entitlement.
    static func current() async -> String? {
        await withCheckedContinuation { continuation in
            NEHotspotNetwork.fetchCurrent { network in continuation.resume(returning: network?.ssid) }
        }
    }

    /// Whether the app may ask: the location while in use is allowed.
    static var locationAllowed: Bool {
        let status = CLLocationManager().authorizationStatus
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    /// Asks for the location while in use, once; returns when the user answered (or at once if they had).
    @MainActor
    static func askForLocation() async {
        guard CLLocationManager().authorizationStatus == .notDetermined else { return }
        await LocationAsk().ask()
    }
}

/// One location prompt, waited for.
@MainActor
private final class LocationAsk: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var answered: CheckedContinuation<Void, Never>?

    func ask() async {
        await withCheckedContinuation { continuation in
            answered = continuation
            manager.delegate = self
            manager.requestWhenInUseAuthorization()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        guard status != .notDetermined else { return }
        Task { @MainActor in
            self.answered?.resume()
            self.answered = nil
        }
    }
}
