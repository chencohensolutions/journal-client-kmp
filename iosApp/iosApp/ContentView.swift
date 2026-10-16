import SwiftUI
import UniformTypeIdentifiers
import SharedLogic

struct ContentView: View {
    @StateObject private var viewModel = JournalViewModel()

    @State private var isImporterPresented = false
    @State private var pendingBundle: Data?
    @State private var password = ""
    @State private var importError: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Server URL", text: serverUrl)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section {
                    Text(identityText)
                        .italic()
                    Button("Import Identity (.p12)…") {
                        isImporterPresented = true
                    }
                } header: {
                    Text("Identity")
                } footer: {
                    Text("The PKCS#12 bundle created by the server's generate-user-cert script.")
                }

                Section {
                    Button("Connect") {
                        viewModel.connect()
                    }
                    .frame(maxWidth: .infinity)
                    .disabled(viewModel.state.status is ConnectionStatusConnecting)
                }

                Section("Status") {
                    Text(statusText)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }

                if viewModel.state.status is ConnectionStatusConnected {
                    Section("Images (\(viewModel.state.images.count))") {
                        if let error = viewModel.state.imagesError {
                            Text("Could not load images: \(error)")
                                .foregroundStyle(.red)
                        }
                        ForEach(viewModel.state.images, id: \.id) { image in
                            VStack(alignment: .leading) {
                                Text(image.title)
                                if let description = image.description_ {
                                    Text(description)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Journal")
            .fileImporter(isPresented: $isImporterPresented, allowedContentTypes: [.pkcs12]) { result in
                loadBundle(result)
            }
            .alert("Certificate Password", isPresented: isPasswordPromptPresented) {
                SecureField("Password (empty if none)", text: $password)
                Button("Import") { importPendingBundle() }
                Button("Cancel", role: .cancel) { pendingBundle = nil }
            } message: {
                Text("Enter the password protecting the .p12 file.")
            }
            .alert("Import Failed", isPresented: isImportErrorPresented) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importError ?? "")
            }
        }
    }

    private var serverUrl: Binding<String> {
        Binding(
            get: { viewModel.state.serverUrl },
            set: { viewModel.updateServerUrl($0) }
        )
    }

    private var isPasswordPromptPresented: Binding<Bool> {
        Binding(
            get: { pendingBundle != nil },
            set: { if !$0 { pendingBundle = nil } }
        )
    }

    private var isImportErrorPresented: Binding<Bool> {
        Binding(
            get: { importError != nil },
            set: { if !$0 { importError = nil } }
        )
    }

    private var identityText: String {
        if let alias = viewModel.state.identityAlias {
            return "Identity: \(alias)"
        }
        return "No identity selected"
    }

    private var statusText: String {
        switch viewModel.state.status {
        case is ConnectionStatusNotConnected:
            return "Status: Not connected"
        case is ConnectionStatusMissingServerUrl:
            return "Status: Error - Please enter server URL"
        case is ConnectionStatusMissingIdentity:
            return "Error: Please import an identity first"
        case let connecting as ConnectionStatusConnecting:
            return "Connecting…\nTime: \(connecting.startedAt)\nTrusting: \(connecting.trustedCaNames.joined(separator: ", "))"
        case is ConnectionStatusConnected:
            return "Status: Connected successfully!"
        case let failed as ConnectionStatusFailed:
            return "Status: Failed (HTTP \(failed.httpCode))"
        case let error as ConnectionStatusError:
            return "Status: Error - \(error.message)"
        default:
            return ""
        }
    }

    private func loadBundle(_ result: Result<URL, Error>) {
        do {
            let url = try result.get()
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            password = ""
            pendingBundle = try Data(contentsOf: url)
        } catch {
            importError = error.localizedDescription
        }
    }

    private func importPendingBundle() {
        guard let bundle = pendingBundle else { return }
        pendingBundle = nil
        importError = viewModel.importIdentity(bundle, password: password)
        password = ""
    }
}

#Preview {
    ContentView()
}
