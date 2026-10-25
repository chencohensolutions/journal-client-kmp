import SwiftUI
import SharedLogic

// Sound recordings in Moments and on their own page, as on Android (MomentsAudioViews.kt, AudioParts.kt, AudioPage.kt):
// a recording is a slim bar where its moment starts ("header strip"), pinned under the title while the photos taken
// during it scroll by; calls one after another fold into one card; a voice message is a bubble. A recording's page
// ("filmstrip") has its bars large with a mark at each photo, the controls, and the photos under them. See
// docs/screens/moments.md and viewer.md.

/// What the feed's sound recordings can ask for: the player, and the recording's page.
struct FeedAudio {
    var player: AudioPlayer?
    /// The recording's page, by its sha256.
    var open: (SoundRecording) -> Void = { _ in }
}

private struct FeedAudioKey: EnvironmentKey {
    static let defaultValue = FeedAudio()
}

extension EnvironmentValues {
    /// The app sets the player (LibraryRootView); the UI lab and previews play nothing.
    var feedAudio: FeedAudio {
        get { self[FeedAudioKey.self] }
        set { self[FeedAudioKey.self] = newValue }
    }
}

extension SoundRecording {
    /// What it is called: a call's contact, or its number, else its kind ("Recording · Jaffa Port").
    func title(place: String? = nil) -> String {
        switch kind {
        case .call: return contact ?? number ?? String(localized: "call.unnamed", defaultValue: "Call")
        case .voiceMessage: return String(localized: "Voice message")
        default: return place.map { String(localized: "Recording · \($0)") } ?? String(localized: "Recording")
        }
    }

    /// "18:10 – 19:55 · 1:45:12".
    var times: String {
        "\(clockTime(startMillis)) – \(clockTime(endMillis)) · \(formatDuration(TimeInterval(durationMillis) / 1000))"
    }

    var symbol: String {
        switch kind {
        case .call: return "phone"
        case .voiceMessage: return "bubble.left.and.text.bubble.right"
        default: return "mic"
        }
    }
}

private func duration(_ millis: Int64) -> String { formatDuration(TimeInterval(max(millis, 0)) / 1000) }

/// A recording's bar: play, what it is, its times or where it plays, and a line for how far it has played. Tapping it
/// opens the recording's page.
struct RecordingBar: View {
    let moment: FeedMoment
    @ObservedObject var player: AudioPlayer
    let open: (SoundRecording) -> Void

    var body: some View {
        if let recording = moment.recording {
            let playback = player.of(recording)
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    PlayButton(playing: playback?.playing == true, size: 36) { player.toggle(recording) }
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 4) {
                            Image(systemName: recording.symbol).font(.system(size: 11)).foregroundStyle(LibraryColors.muted)
                            Text(verbatim: recording.title(place: moment.place))
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(LibraryColors.onBackground)
                                .lineLimit(1)
                        }
                        Text(verbatim: playback.map {
                            String(localized: "\(duration($0.positionMillis)) of \(duration(recording.durationMillis)) · at \(clockTime(recording.startMillis + $0.positionMillis))")
                        } ?? recording.times)
                        .font(.system(size: 12))
                        .monospacedDigit()
                        .foregroundStyle(LibraryColors.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        LibraryColors.muted.opacity(0.2)
                        if let playback, recording.durationMillis > 0 {
                            LibraryColors.onBackground
                                .frame(width: geometry.size.width * min(max(CGFloat(playback.positionMillis) / CGFloat(recording.durationMillis), 0), 1))
                        }
                    }
                }
                .frame(height: 3)
            }
            .background(LibraryColors.raised)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .contentShape(RoundedRectangle(cornerRadius: 14))
            .onTapGesture { open(recording) }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(LibraryColors.background)
        }
    }
}

/// On a photo taken during `recording`: how far into it ("+32:10").
struct RecordingOffset: View {
    let item: MediaItem
    let recording: SoundRecording

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(LibraryColors.warning).frame(width: 6, height: 6)
            Text(verbatim: "+\(duration(item.takenAtMillis - recording.startMillis))")
                .font(.system(size: 12, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(LibraryColors.photoText)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(LibraryColors.badgeScrim, in: Capsule())
    }
}

/// The round play or pause button. Nothing plays until it is tapped (or a photo inside a recording is held).
struct PlayButton: View {
    let playing: Bool
    var size: CGFloat = 44
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: playing ? "pause.fill" : "play.fill")
                .font(.system(size: size * 0.4))
                .foregroundStyle(LibraryColors.background)
                .frame(width: size, height: size)
                .background(LibraryColors.onBackground, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(playing ? Text("Pause") : Text("Play"))
    }
}

/// How many bars the waveform draws.
private let waveBars = 64

/// A recording as bars, what has played darker; a tap seeks there. The server doesn't measure loudness yet, so the bars
/// are even. `marks` are moments to show under it (when photos were taken), as fractions of its length.
struct Waveform: View {
    let progress: Double?
    var marks: [Double] = []
    let onSeek: (Double) -> Void
    @Environment(\.layoutDirection) private var direction

    var body: some View {
        GeometryReader { geometry in
            let rtl = direction == .rightToLeft
            let markSpace: CGFloat = marks.isEmpty ? 0 : 8
            let height = geometry.size.height - markSpace
            let step = geometry.size.width / CGFloat(waveBars)
            let bar = max(step * 0.6, 1)
            Canvas { context, size in
                for i in 0..<waveBars {
                    let f = (Double(i) + 0.5) / Double(waveBars)
                    let x = rtl ? size.width - CGFloat(i + 1) * step : CGFloat(i) * step
                    let rect = CGRect(x: x + (step - bar) / 2, y: height / 4, width: bar, height: height / 2)
                    let played = progress.map { f <= $0 } ?? false
                    context.fill(Path(roundedRect: rect, cornerRadius: bar / 2), with: .color(played ? LibraryColors.onBackground : LibraryColors.muted.opacity(0.45)))
                }
                for mark in marks {
                    let m = min(max(mark, 0), 1)
                    let x = (rtl ? 1 - m : m) * size.width
                    context.fill(Path(ellipseIn: CGRect(x: x - 2.5, y: size.height - 5.5, width: 5, height: 5)), with: .color(LibraryColors.warning))
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { at in
                let f = min(max(at.x / max(geometry.size.width, 1), 0), 1)
                onSeek(rtl ? 1 - f : f)
            }
            // The page's own layout direction is handled above.
            .environment(\.layoutDirection, .leftToRight)
        }
    }
}

/// One call: who, when and how long, and play. Tapping it opens its page.
struct CallRow: View {
    let call: SoundRecording
    @ObservedObject var player: AudioPlayer
    let open: (SoundRecording) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "phone").font(.system(size: 16)).foregroundStyle(LibraryColors.muted)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: call.title())
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .lineLimit(1)
                Text(verbatim: "\(clockTime(call.startMillis)) · \(duration(call.durationMillis))")
                    .font(.system(size: 13))
                    .monospacedDigit()
                    .foregroundStyle(LibraryColors.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            PlayButton(playing: player.of(call)?.playing == true, size: 36) { player.toggle(call) }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .onTapGesture { open(call) }
    }
}

/// Calls one after another: one line ("4 calls · 09:10 – 11:40") until tapped, then each call. A single call shows open.
struct CallsRow: View {
    let calls: [SoundRecording]
    @ObservedObject var player: AudioPlayer
    let open: (SoundRecording) -> Void
    @State private var unfolded = false

    var body: some View {
        let showing = unfolded || calls.count == 1
        VStack(spacing: 0) {
            if calls.count > 1 {
                Button { withAnimation { unfolded.toggle() } } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "phone").font(.system(size: 16)).foregroundStyle(LibraryColors.onBackground)
                        Text(verbatim: "\(String(localized: "\(calls.count) calls")) · \(clockTime(calls.map(\.startMillis).min() ?? 0)) – \(clockTime(calls.map(\.endMillis).max() ?? 0))")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(LibraryColors.onBackground)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "chevron.down")
                            .foregroundStyle(LibraryColors.muted)
                            .rotationEffect(.degrees(showing ? 180 : 0))
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if showing {
                ForEach(Array(calls.enumerated()), id: \.element.sha256) { i, call in
                    if i > 0 || calls.count > 1 { Divider().overlay(LibraryColors.muted.opacity(0.15)) }
                    CallRow(call: call, player: player, open: open)
                }
            }
        }
        .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }
}

/// A WhatsApp voice message: a bubble with play, its bars and its length.
struct VoiceMessageRow: View {
    let recording: SoundRecording
    @ObservedObject var player: AudioPlayer
    let open: (SoundRecording) -> Void

    var body: some View {
        let playback = player.of(recording)
        let length = Double(max(recording.durationMillis, 1))
        HStack(spacing: 10) {
            PlayButton(playing: playback?.playing == true, size: 34) { player.toggle(recording) }
            VStack(alignment: .leading, spacing: 2) {
                Waveform(progress: playback.map { Double($0.positionMillis) / length }) { f in
                    player.seek(recording, to: Int64(f * Double(recording.durationMillis)))
                }
                .frame(height: 26)
                Text(verbatim: "\(duration(recording.durationMillis)) · \(clockTime(recording.startMillis))")
                    .font(.system(size: 12))
                    .monospacedDigit()
                    .foregroundStyle(LibraryColors.muted)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(width: 260)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 4, bottomLeadingRadius: 18, bottomTrailingRadius: 18, topTrailingRadius: 18)
                .fill(LibraryColors.raised)
        )
        .contentShape(Rectangle())
        .onTapGesture { open(recording) }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

// MARK: - A recording's page

/// A recording's page ("filmstrip"): `during` are the photos taken while it ran, oldest first; `place` is where (from
/// them). A call's page names who it was with, with Call and Edit contact. Always dark, like the viewer.
struct AudioPageView: View {
    let recording: SoundRecording
    let place: String?
    let during: [MediaItem]
    @ObservedObject var player: AudioPlayer
    /// Saves who a call was with (empty is none); the server's answer comes with the next sync.
    let setCall: (_ contact: String, _ number: String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var contact = ""
    @State private var number = ""

    var body: some View {
        let playback = player.of(recording)
        let position = playback?.positionMillis ?? 0
        let length = Double(max(recording.durationMillis, 1))
        let nowIndex = playback == nil ? nil : during.lastIndex { $0.takenAtMillis <= recording.startMillis + position }
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(LibraryColors.onBackground)
                        .frame(width: 48, height: 48)
                }
                .accessibilityLabel("Back")
                Text(verbatim: "\(recording.day.label) · \(clockTime(recording.startMillis)) – \(clockTime(recording.endMillis))")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(LibraryColors.muted)
                Spacer()
            }
            .padding(4)
            VStack(spacing: 0) {
                Image(systemName: recording.symbol)
                    .font(.system(size: 28))
                    .foregroundStyle(LibraryColors.photoText)
                    .frame(width: 64, height: 64)
                    .background(LibraryColors.accent, in: Circle())
                Text(verbatim: recording.title(place: place))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.top, 12)
                // A call with both: the number under the name.
                if recording.kind == .call, recording.contact != nil, let number = recording.number {
                    Text(verbatim: number)
                        .font(.system(size: 15))
                        .foregroundStyle(LibraryColors.muted)
                        .environment(\.layoutDirection, .leftToRight)
                }
                if recording.kind == .call {
                    HStack(spacing: 8) {
                        if let number = recording.number {
                            Button { dial(number) } label: { Label("Call", systemImage: "phone") }
                                .buttonStyle(.bordered)
                        }
                        Button {
                            contact = recording.contact ?? ""
                            number = recording.number ?? ""
                            editing = true
                        } label: {
                            Label("Edit contact", systemImage: "pencil")
                        }
                        .buttonStyle(.bordered)
                    }
                    .tint(LibraryColors.onBackground)
                    .padding(.top, 12)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            Spacer()
            Waveform(
                progress: Double(position) / length,
                marks: during.map { Double($0.takenAtMillis - recording.startMillis) / length }
            ) { f in
                player.seek(recording, to: Int64(f * Double(recording.durationMillis)))
            }
            .frame(height: 96)
            .padding(.horizontal, 20)
            HStack {
                Text(verbatim: duration(position)).font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                Spacer()
                Text(verbatim: "−\(duration(recording.durationMillis - position))").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
            }
            .monospacedDigit()
            .padding(.horizontal, 20)
            .padding(.vertical, 6)
            controls(playback, position: position)
                .padding(.vertical, 12)
            if !during.isEmpty {
                Text("\(during.count) photos while it recorded")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LibraryColors.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)
                strip(nowIndex)
            }
        }
        .padding(.bottom, 16)
        .background(LibraryColors.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .alert("Edit contact", isPresented: $editing) {
            TextField("Contact", text: $contact)
            TextField("Phone number", text: $number).keyboardType(.phonePad)
            Button("Cancel", role: .cancel) {}
            Button("Save") { setCall(contact, number) }
        }
    }

    /// Back 15 s, play or pause, forward 15 s, and the speed (1×, 1.5×, 2×, then 1× again).
    private func controls(_ playback: AudioPlayback?, position: Int64) -> some View {
        let playing = playback?.playing == true
        let speed = playback?.speed ?? 1
        return HStack {
            Spacer()
            skip(back: true) { player.seek(recording, to: max(position - skipMillis, 0)) }
            Spacer()
            PlayButton(playing: playing, size: 68) { player.toggle(recording) }
            Spacer()
            skip(back: false) { player.seek(recording, to: min(position + skipMillis, recording.durationMillis)) }
            Spacer()
            Button { player.setSpeed(nextSpeed(speed)) } label: {
                Text(verbatim: "\(speed == speed.rounded() ? String(Int(speed)) : String(speed))×")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .overlay(Capsule().stroke(LibraryColors.muted.opacity(0.5)))
            }
            .buttonStyle(.plain)
            .disabled(playback == nil)
            Spacer()
        }
    }

    private func skip(back: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: back ? "gobackward.15" : "goforward.15")
                .font(.system(size: 26))
                .foregroundStyle(LibraryColors.onBackground)
                .frame(width: 52, height: 52)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(back ? Text("Back 15 seconds") : Text("Forward 15 seconds"))
    }

    /// The photos taken while it ran, the one of the moment playing marked and kept in view; tapping one plays from
    /// when it was taken.
    private func strip(_ nowIndex: Int?) -> some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                LazyHStack(spacing: 6) {
                    ForEach(Array(during.enumerated()), id: \.element.id) { i, item in
                        let current = i == nowIndex
                        VStack(spacing: 4) {
                            ExploreThumbnail(item: item)
                                .frame(width: 84, height: 84)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .overlay { if current { RoundedRectangle(cornerRadius: 10).stroke(LibraryColors.warning, lineWidth: 3) } }
                                .onTapGesture { player.seek(recording, to: item.takenAtMillis - recording.startMillis) }
                            Text(verbatim: clockTime(item.takenAtMillis))
                                .font(.system(size: 11))
                                .foregroundStyle(current ? LibraryColors.warning : LibraryColors.muted)
                        }
                        .id(item.id)
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .frame(height: 110)
            .onChange(of: nowIndex) { _, index in
                if let index { withAnimation { proxy.scrollTo(during[index].id, anchor: .center) } }
            }
        }
    }

    private func dial(_ number: String) {
        let digits = number.filter { $0.isNumber || $0 == "+" }
        if let url = URL(string: "tel:\(digits)") { UIApplication.shared.open(url) }
    }
}

private let skipMillis: Int64 = 15_000

private func nextSpeed(_ speed: Float) -> Float {
    switch speed {
    case 1: return 1.5
    case 1.5: return 2
    default: return 1
    }
}
