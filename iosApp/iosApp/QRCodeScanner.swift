import AVFoundation
import SwiftUI
import VisionKit

/// The camera reading QR codes (VisionKit), in a sheet with Cancel. Calls `onScan` with the first code it reads.
struct QRCodeScannerSheet: View {
    let onScan: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QRCodeScanner { text in
                dismiss()
                onScan(text)
            }
            .ignoresSafeArea()
            .navigationTitle("Scan QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }

    /// Whether this device can scan: it has a camera VisionKit supports (not the simulator), and the camera isn't
    /// denied. Asks for the camera the first time.
    static func canScan() async -> Bool {
        guard DataScannerViewController.isSupported else { return false }
        if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
            guard await AVCaptureDevice.requestAccess(for: .video) else { return false }
        }
        return DataScannerViewController.isAvailable
    }
}

private struct QRCodeScanner: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        context.coordinator.onScan = onScan
        // Scanning can only start once the camera view is on screen.
        if !scanner.isScanning { try? scanner.startScanning() }
    }

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Coordinator) {
        scanner.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onScan: (String) -> Void
        private var done = false

        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        func dataScanner(_ scanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            // One code is enough; later frames keep reporting it.
            guard !done else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item, let text = barcode.payloadStringValue {
                    done = true
                    scanner.stopScanning()
                    onScan(text)
                    return
                }
            }
        }
    }
}
