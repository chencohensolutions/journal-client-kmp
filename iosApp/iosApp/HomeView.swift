import SwiftUI
import SharedLogic

/// Shown once this device has an identity; connects with it and shows whether that worked.
struct HomeView: View {
    @ObservedObject var viewModel: JournalViewModel

    var body: some View {
        VStack(spacing: 16) {
            Text("home")
                .font(.title)
            HStack(spacing: 8) {
                if viewModel.state.status is ConnectionStatusConnecting {
                    ProgressView()
                }
                Text(statusText)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(statusColor)
            }
            if isFailed {
                Button("Try again", action: viewModel.connect)
                    .buttonStyle(.bordered)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { viewModel.connect() }
    }

    private var isFailed: Bool {
        viewModel.state.status is ConnectionStatusFailed || viewModel.state.status is ConnectionStatusError
    }

    private var statusText: String {
        switch viewModel.state.status {
        case is ConnectionStatusConnecting:
            return "Connecting…"
        case is ConnectionStatusConnected:
            return "Connected"
        case let failed as ConnectionStatusFailed:
            // 401: the server does not accept this device's certificate (unknown or revoked).
            return failed.httpCode == 401
                ? "Connection failed: the server rejected this device's certificate"
                : "Connection failed (HTTP \(failed.httpCode))"
        case let error as ConnectionStatusError:
            return "Connection failed: \(error.message)"
        case is ConnectionStatusMissingIdentity, is ConnectionStatusMissingServerUrl:
            return "This device is not registered"
        default:
            return "Not connected"
        }
    }

    private var statusColor: Color {
        if isFailed { return .red }
        if viewModel.state.status is ConnectionStatusConnected { return .green }
        return .secondary
    }
}
