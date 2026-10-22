import SwiftUI
import SharedLogic

/// Shows the registration wizard until this device has an identity, then the journal.
struct ContentView: View {
    @StateObject private var viewModel = JournalViewModel()
    @State private var showWizard: Bool?

    var body: some View {
        screen
            // journal://enroll?code=…&server=…, the QR code `enroll-code` prints on the server, scanned with the
            // camera: register (again) with its code.
            .onOpenURL { url in
                guard let link = EnrollmentControllerKt.parseEnrollmentLink(link: url.absoluteString) else { return }
                viewModel.enrollment.openLink(link)
                showWizard = true
            }
    }

    @ViewBuilder
    private var screen: some View {
        // Decided once at launch; afterwards only "Open journal" leaves the wizard, so the
        // success screen stays visible after the identity is stored.
        if showWizard ?? (viewModel.state.identityAlias == nil) {
            EnrollmentView(viewModel: viewModel.enrollment) {
                showWizard = false
                viewModel.enrollment.reset()
            }
            .onAppear { showWizard = showWizard ?? true }
        } else {
            LibraryRootView(journal: viewModel, library: viewModel.library, stories: viewModel.stories, settings: viewModel.settings, backup: viewModel.backup, search: viewModel.search) {
                viewModel.enrollment.reset()
                showWizard = true
            }
        }
    }
}

#Preview {
    ContentView()
}
