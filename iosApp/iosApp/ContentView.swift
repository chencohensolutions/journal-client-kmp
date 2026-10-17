import SwiftUI
import SharedLogic

/// Shows the registration wizard until this device has an identity, then the home screen.
struct ContentView: View {
    @StateObject private var viewModel = JournalViewModel()
    @State private var showWizard: Bool?

    var body: some View {
        // Decided once at launch; afterwards only "Open journal" leaves the wizard, so the
        // success screen stays visible after the identity is stored.
        if showWizard ?? (viewModel.state.identityAlias == nil) {
            EnrollmentView(viewModel: viewModel.enrollment) {
                showWizard = false
                viewModel.enrollment.reset()
            }
            .onAppear { showWizard = showWizard ?? true }
        } else {
            HomeView(viewModel: viewModel)
        }
    }
}

#Preview {
    ContentView()
}
