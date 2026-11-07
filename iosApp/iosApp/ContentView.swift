import SwiftUI
import SharedLogic

/// Shows the registration wizard until this device has an identity, then the journal.
struct ContentView: View {
    @StateObject private var viewModel = JournalViewModel()
    @State private var showWizard: Bool?
    /// A friend's swap invite opened from a message (journal://swap?…): "Keep my copy at a friend's" over the journal.
    @State private var swapDraft: JoinDraft?

    var body: some View {
        screen
            // journal://enroll?code=…&server=…, the QR code `enroll-code` prints on the server, scanned with the
            // camera: register (again) with its code.
            .onOpenURL { url in
                if let swap = SwapLinkKt.parseSwapLink(text: url.absoluteString), viewModel.state.identityAlias != nil {
                    let draft = JoinDraft()
                    draft.linkText = swap.link
                    draft.name = swap.from ?? ""
                    SwapViewModel.shared.swaps.clearOutcome()
                    SwapViewModel.shared.refresh()
                    swapDraft = draft
                    return
                }
                guard let link = EnrollmentControllerKt.parseEnrollmentLink(link: url.absoluteString) else { return }
                viewModel.enrollment.openLink(link)
                showWizard = true
            }
            .sheet(item: $swapDraft) { draft in
                NavigationStack {
                    SwapThereView(model: SwapViewModel.shared, draft: draft)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { swapDraft = nil } } }
                }
                .preferredColorScheme(.dark)
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
            LibraryRootView(
                journal: viewModel, library: viewModel.library, stories: viewModel.stories, settings: viewModel.settings, backup: viewModel.backup,
                search: viewModel.search, people: viewModel.people, findings: viewModel.findings, phoneSearch: viewModel.phoneSearch,
                serverIndexing: viewModel.serverIndexing
            ) {
                viewModel.enrollment.reset()
                showWizard = true
            }
        }
    }
}

#Preview {
    ContentView()
}
