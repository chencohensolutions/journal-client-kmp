import SwiftUI
import SharedLogic

/// Registers this device with the server: enrollment code → Secure Enclave key → client certificate.
struct EnrollmentView: View {
    @ObservedObject var viewModel: EnrollmentViewModel
    var onOpenJournal: () -> Void

    @State private var scanning = false
    @State private var scanFailed = false
    @State private var nothingToPaste = false

    var body: some View {
        VStack(spacing: 0) {
            switch viewModel.state.step {
            case is EnrollmentStepWelcome:
                welcome
            case let step as EnrollmentStepEnterCode:
                enterCode(step)
            case let step as EnrollmentStepConfirm:
                confirm(step)
            case let step as EnrollmentStepRegistering:
                registering(step)
            case let step as EnrollmentStepEnrolled:
                enrolled(step)
            case let step as EnrollmentStepFailed:
                failed(step)
            default:
                EmptyView()
            }
        }
        .background(Color(.systemGroupedBackground))
        .sheet(isPresented: $scanning) {
            QRCodeScannerSheet(onScan: viewModel.openScanned)
        }
        .alert("Couldn't open the QR code scanner. Type the code instead.", isPresented: $scanFailed) {
            Button("OK", role: .cancel) {}
        }
        .alert("Nothing to paste. Copy the enrollment link or code first.", isPresented: $nothingToPaste) {
            Button("OK", role: .cancel) {}
        }
    }

    /// The camera, or a note when this device can't scan (no supported camera, as on the simulator, or no access).
    private func scan() {
        Task {
            if await QRCodeScannerSheet.canScan() { scanning = true } else { scanFailed = true }
        }
    }

    /// The system's Paste button reads the clipboard without iOS asking each time; a link opens as a scan does.
    private func paste(_ strings: [String]) {
        DispatchQueue.main.async {
            if viewModel.openPasted(strings.joined(separator: "\n")) == PasteResult.nothing { nothingToPaste = true }
        }
    }

    // MARK: Steps

    private var welcome: some View {
        Group {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Badge(systemName: "lock.shield", tint: .accentColor)
                        .padding(.top, 40)
                    Text("Connect this device to your journal")
                        .font(.largeTitle.weight(.semibold))
                        .padding(.top, 28)
                    Text("Your journal server only talks to devices it knows. Registering creates a private key on this iPhone. The key never leaves it.")
                        .foregroundStyle(.secondary)
                        .padding(.top, 12)
                    VStack(alignment: .leading, spacing: 20) {
                        NumberedItem(number: 1, title: "Get an enrollment code",
                                     detail: "Create one on the server. It works once and expires after 10 minutes.")
                        NumberedItem(number: 2, title: "Type the code here",
                                     detail: "Along with your server's address.")
                        NumberedItem(number: 3, title: "The server issues a certificate",
                                     detail: "From then on, this device signs in automatically.")
                    }
                    .padding(.top, 32)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            BottomActions {
                PrimaryButton(title: "Get started", action: viewModel.start)
            }
        }
    }

    private func enterCode(_ step: EnrollmentStepEnterCode) -> some View {
        let codeError = step.error.flatMap { $0 is EnrollmentErrorMissingServerAddress ? nil : $0 }
        return Group {
            WizardHeader(step: 1, label: "Connect", onBack: viewModel.back)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Enter enrollment code")
                        .font(.title.weight(.semibold))
                        .padding(.top, 24)
                    Text("On your server, run `journal-server enroll-code` and scan its QR code, paste the link it prints, or type the address and code.")
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)

                    HStack(spacing: 12) {
                        Button(action: scan) {
                            Label("Scan QR code", systemImage: "qrcode")
                                .font(.headline)
                                .frame(maxWidth: .infinity, minHeight: 50)
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        PasteButton(payloadType: String.self, onPaste: paste)
                            .buttonBorderShape(.capsule)
                            .labelStyle(.titleAndIcon)
                            .controlSize(.large)
                    }
                    .disabled(step.checking)
                    .padding(.top, 24)

                    FieldLabel("Server address")
                        .padding(.top, 20)
                    TextField("journal.example.com:56398", text: binding(\.serverAddress, viewModel.updateServerAddress))
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .fieldStyle(isError: step.error is EnrollmentErrorMissingServerAddress)
                        .disabled(step.checking)

                    FieldLabel("Enrollment code")
                        .padding(.top, 20)
                    TextField("K7QM-4XPD", text: binding(\.code, viewModel.updateCode))
                        .font(.system(.title2, design: .monospaced))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .submitLabel(.continue)
                        .onSubmit(viewModel.checkCode)
                        .fieldStyle(isError: codeError != nil)
                        .disabled(step.checking)
                    Text(codeError.map(errorMessage) ?? String(localized: "8 letters and digits. Works once, expires 10 minutes after it was created."))
                        .font(.footnote)
                        .foregroundStyle(codeError == nil ? AnyShapeStyle(.secondary) : AnyShapeStyle(.red))
                        .padding(.top, 6)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            BottomActions {
                PrimaryButton(title: "Continue", loading: step.checking, action: viewModel.checkCode)
            }
        }
    }

    private func confirm(_ step: EnrollmentStepConfirm) -> some View {
        Group {
            WizardHeader(step: 2, label: "Confirm", onBack: viewModel.back)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Register this device?")
                        .font(.title.weight(.semibold))
                        .padding(.top, 24)
                    VStack(spacing: 0) {
                        DetailRow(systemName: "server.rack", label: "Server",
                                  value: displayAddress(viewModel.state.serverAddress),
                                  detail: viewModel.state.serverId.map { LocalizedStringKey("Its key matches server ID \(shortServerId($0))") }
                                      ?? LocalizedStringKey("Verified by journal-server-ca"),
                                  highlight: true)
                        Divider().padding(.leading, 72)
                        DetailRow(systemName: "person", label: "Account", value: step.user,
                                  detail: "Code \(viewModel.state.code.uppercased()) · expires in \(Int(step.minutesLeft)) min",
                                  highlight: false)
                    }
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                    .padding(.top, 24)

                    FieldLabel("Device name")
                        .padding(.top, 28)
                    TextField("Device name", text: binding(\.deviceName, viewModel.updateDeviceName))
                        .textInputAutocapitalization(.words)
                        .fieldStyle(isError: false)
                    Text("Shown on the server so you can tell devices apart and revoke one later.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.top, 6)

                    Label("A new key will be created in this iPhone's Secure Enclave. It can't be copied, exported or backed up.",
                          systemImage: "lock")
                        .font(.subheadline)
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                        .padding(.top, 20)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            BottomActions {
                PrimaryButton(title: "Register device", action: viewModel.register)
            }
        }
    }

    private func registering(_ step: EnrollmentStepRegistering) -> some View {
        let items: [(LocalizedStringKey, LocalizedStringKey)] = [
            ("Create key in secure hardware", "EC P-256 · non-exportable"),
            ("Send certificate request", "Signed by the new key, with your enrollment code"),
            ("Save certificate", "Issued by journal-server-ca"),
            ("Test connection", "Signing in with the new certificate"),
        ]
        let current = Int(step.progress.ordinal)
        return Group {
            WizardHeader(step: 3, label: "Register", onBack: nil)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Registering \(viewModel.state.deviceName.trimmingCharacters(in: .whitespaces))")
                        .font(.title.weight(.semibold))
                        .padding(.top, 24)
                    Text("This takes a few seconds. Keep the app open.")
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(items.indices, id: \.self) { index in
                            ProgressRow(title: items[index].0, detail: items[index].1,
                                        state: index < current ? .done : index == current ? .active : .pending,
                                        isLast: index == items.count - 1)
                        }
                    }
                    .padding(.top, 32)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            BottomActions {
                Button("Cancel", action: viewModel.back)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
        }
    }

    private func enrolled(_ step: EnrollmentStepEnrolled) -> some View {
        Group {
            ScrollView {
                VStack(spacing: 0) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 96, height: 96)
                        .background(Color.accentColor, in: Circle())
                        .padding(.top, 64)
                    Text("You're connected")
                        .font(.largeTitle.weight(.semibold))
                        .padding(.top, 28)
                    Text("This device is registered as **\(step.user)**.")
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                    VStack(spacing: 0) {
                        SummaryRow(label: "Server", value: displayAddress(step.serverAddress))
                        SummaryRow(label: "Device", value: step.deviceName)
                        SummaryRow(label: "Key", value: keyLabel(step.keyProtection))
                        SummaryRow(label: "Certificate valid until", value: step.validUntil, isLast: true)
                    }
                    .padding(.horizontal, 16)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                    .padding(.top, 32)
                }
                .padding(.horizontal, 24)
            }
            BottomActions {
                PrimaryButton(title: "Open journal", action: onOpenJournal)
            }
        }
    }

    private func failed(_ step: EnrollmentStepFailed) -> some View {
        let invalidCode = step.error is EnrollmentErrorInvalidCode
        return Group {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Badge(systemName: "exclamationmark.circle", tint: .red)
                        .padding(.top, 64)
                    Text(invalidCode ? "That code didn't work" : "Registration failed")
                        .font(.title.weight(.semibold))
                        .padding(.top, 28)
                    Text(invalidCode
                         ? String(localized: "The server rejected \(viewModel.state.code.uppercased()). It may be mistyped, expired or already used. Each code works once, for 10 minutes.")
                         : errorMessage(step.error))
                        .foregroundStyle(.secondary)
                        .padding(.top, 12)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Create a new code on the server")
                            .font(.subheadline.weight(.semibold))
                        Text(verbatim: "$ journal-server enroll-code")
                            .font(.system(.subheadline, design: .monospaced))
                            .foregroundStyle(Color(red: 0.86, green: 0.9, blue: 0.87))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(red: 0.07, green: 0.08, blue: 0.07), in: RoundedRectangle(cornerRadius: 8))
                    }
                    .padding(16)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                    .padding(.top, 28)
                    Label("The key created for this attempt was deleted from the device.", systemImage: "trash")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.top, 16)
                }
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            BottomActions {
                PrimaryButton(title: "Enter a new code", action: viewModel.back)
            }
        }
    }

    // MARK: Helpers

    private func binding(_ keyPath: KeyPath<EnrollmentState, String>, _ update: @escaping (String) -> Void) -> Binding<String> {
        Binding(get: { viewModel.state[keyPath: keyPath] }, set: update)
    }

    private func displayAddress(_ address: String) -> String {
        address.replacingOccurrences(of: "https://", with: "")
    }

    private func keyLabel(_ protection: KeyProtection) -> String {
        switch protection {
        case .secureEnclave: return String(localized: "Secure Enclave")
        case .strongbox: return String(localized: "StrongBox security chip")
        case .tee: return String(localized: "Secure hardware (TEE)")
        default: return String(localized: "Software (Simulator)")
        }
    }

    private func errorMessage(_ error: EnrollmentError) -> String {
        switch error {
        case is EnrollmentErrorMissingServerAddress: return String(localized: "Enter the server address.")
        case is EnrollmentErrorMissingCode: return String(localized: "Enter the enrollment code.")
        case is EnrollmentErrorInvalidCode: return String(localized: "This code is wrong, expired or already used.")
        case is EnrollmentErrorRateLimited: return String(localized: "Too many wrong codes. Wait a minute and try again.")
        case is EnrollmentErrorRequestRejected: return String(localized: "The server could not process this device's certificate request.")
        case let error as EnrollmentErrorUnreachable: return String(localized: "Could not reach the server: \(error.message)")
        case let error as EnrollmentErrorKeyStoreFailed: return String(localized: "The Keychain failed: \(error.message)")
        case let error as EnrollmentErrorConnectionTestFailed:
            return String(localized: "The certificate was issued, but signing in with it failed: \(error.message)")
        default: return String(localized: "Something went wrong.")
        }
    }
}

// MARK: - Building blocks

private struct WizardHeader: View {
    let step: Int
    let label: LocalizedStringKey
    let onBack: (() -> Void)?

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                if let onBack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.backward")
                            .font(.title3.weight(.semibold))
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Back")
                }
                Text("Step \(step) of 3 · \(Text(label))")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.leading, onBack == nil ? 24 : 8)
            .frame(height: 52)
            HStack(spacing: 6) {
                ForEach(1...3, id: \.self) { index in
                    Capsule()
                        .fill(index <= step ? Color.accentColor : Color(.systemGray4))
                        .frame(height: 4)
                }
            }
            .padding(.horizontal, 24)
        }
    }
}

private struct BottomActions<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 4) { content }
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 16)
    }
}

private struct PrimaryButton: View {
    let title: LocalizedStringKey
    var loading = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if loading {
                    ProgressView().tint(.white)
                } else {
                    Text(title).font(.headline)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .disabled(loading)
    }
}

private struct Badge: View {
    let systemName: String
    let tint: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 40))
            .foregroundStyle(tint)
            .frame(width: 88, height: 88)
            .background(tint.opacity(0.15), in: RoundedRectangle(cornerRadius: 28))
    }
}

private struct NumberedItem: View {
    let number: Int
    let title: LocalizedStringKey
    let detail: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Text("\(number)")
                .font(.headline)
                .frame(width: 40, height: 40)
                .background(Color.accentColor.opacity(0.15), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

private struct FieldLabel: View {
    let text: LocalizedStringKey
    init(_ text: LocalizedStringKey) { self.text = text }

    var body: some View {
        Text(text).font(.subheadline.weight(.semibold)).padding(.bottom, 6)
    }
}

private struct DetailRow: View {
    let systemName: String
    let label: LocalizedStringKey
    let value: String
    let detail: LocalizedStringKey
    let highlight: Bool

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: systemName)
                .frame(width: 40, height: 40)
                .background(Color.accentColor.opacity(0.15), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.headline)
                Text(detail).font(.footnote).foregroundStyle(highlight ? Color.accentColor : .secondary)
            }
            Spacer()
        }
        .padding(16)
    }
}

private struct SummaryRow: View {
    let label: LocalizedStringKey
    let value: String
    var isLast = false

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(label).foregroundStyle(.secondary)
                Spacer(minLength: 16)
                Text(value).fontWeight(.semibold).multilineTextAlignment(.trailing)
            }
            .font(.subheadline)
            .padding(.vertical, 12)
            if !isLast { Divider() }
        }
    }
}

private struct ProgressRow: View {
    enum State { case done, active, pending }

    let title: LocalizedStringKey
    let detail: LocalizedStringKey
    let state: State
    let isLast: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(spacing: 4) {
                ZStack {
                    switch state {
                    case .done:
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 32, height: 32)
                            .background(Color.accentColor, in: Circle())
                    case .active:
                        ProgressView()
                    case .pending:
                        Circle().strokeBorder(Color(.systemGray4), lineWidth: 2).frame(width: 20, height: 20)
                    }
                }
                .frame(width: 32, height: 32)
                if !isLast {
                    Rectangle().fill(Color(.systemGray4)).frame(width: 2, height: 28)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
            .padding(.top, 4)
            .opacity(state == .pending ? 0.55 : 1)
        }
    }
}

private extension View {
    func fieldStyle(isError: Bool) -> some View {
        padding(.horizontal, 14)
            .frame(minHeight: 52)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(isError ? Color.red : Color(.systemGray4)))
    }
}

/// The first two groups of a server id, "T6DNBAM-IJR6WLU-…": enough to recognize it.
private func shortServerId(_ id: String) -> String {
    id.split(separator: "-").prefix(2).joined(separator: "-") + "-…"
}
