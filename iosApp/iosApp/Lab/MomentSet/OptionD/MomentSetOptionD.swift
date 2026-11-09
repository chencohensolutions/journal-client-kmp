#if DEBUG

import SwiftUI

/// Option d, "Collect into one moment": photos from neighbouring days (a weekend) made into one moment. Picking in
/// Moments as today (long press, then taps), with the picks in a tray at the bottom that counts the days.
/// **Make a moment** asks for a name, the main photo and the day it shows on, then opens its page (option a's).
struct MomentSetScreenD: View {
    enum Step { case picking, naming, made }

    @State private var picked: [MomentSetLab.DayPhoto]
    @State private var step: Step
    @State private var name = "Beach weekend"
    @State private var mainId: String?
    @State private var showOn = 0

    init(step: Step = .picking, picked: Int = 5) {
        _step = State(initialValue: step)
        _picked = State(initialValue: Array(MomentSetLab.days.flatMap(\.photos).filter { $0.index % 2 == 0 }.prefix(picked)))
    }

    var body: some View {
        switch step {
        case .made:
            MomentSetScreenA(set: made)
        default:
            picking
                .sheet(isPresented: Binding(get: { step == .naming }, set: { if !$0 { step = .picking } })) { naming }
        }
    }

    private var made: MomentSetState {
        let days = Set(picked.map(\.day))
        return MomentSetState(
            place: name, when: "\(MomentSetLab.days.last!.title) – \(MomentSetLab.days.first!.title) · \(days.count) days",
            photos: picked.map(\.photo), mainId: mainId ?? picked.first?.id ?? ""
        )
    }

    // MARK: Picking

    private var picking: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0, pinnedViews: []) {
                Color.clear.frame(height: 40)
                ForEach(MomentSetLab.days) { day in
                    HStack {
                        Text(verbatim: day.title).font(.system(size: 15, weight: .semibold))
                        Text(verbatim: day.place).font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 20)
                    .padding(.bottom, 8)
                    VStack(spacing: 2) {
                        ForEach(day.photos) { photo in
                            let on = picked.contains { $0.id == photo.id }
                            SetPicture(photo: photo.photo)
                                .aspectRatio(photo.photo.shownAspect, contentMode: .fit)
                                .overlay { if on { Color.black.opacity(0.25) } }
                                .overlay(alignment: .topTrailing) { check(on) }
                                .contentShape(Rectangle())
                                .onTapGesture { toggle(photo) }
                        }
                    }
                }
                Color.clear.frame(height: 150)
            }
        }
        .background(LibraryColors.background)
        .foregroundStyle(LibraryColors.onBackground)
        .overlay(alignment: .bottom) { tray }
    }

    private func check(_ on: Bool) -> some View {
        ZStack {
            Circle().fill(on ? LibraryColors.accent : Color.black.opacity(0.25))
            Circle().strokeBorder(.white, lineWidth: 1.5)
            if on { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(.white) }
        }
        .frame(width: 22, height: 22)
        .padding(6)
    }

    /// The picks, newest last, with how many days they come from and Make a moment.
    private var tray: some View {
        let days = Set(picked.map(\.day)).count
        return VStack(spacing: 10) {
            ScrollView(.horizontal) {
                HStack(spacing: 4) {
                    ForEach(picked) { photo in
                        SetPicture(photo: photo.photo)
                            .aspectRatio(photo.photo.shownAspect, contentMode: .fill)
                            .frame(width: 44, height: 44)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .onTapGesture { toggle(photo) }
                    }
                }
                .padding(.horizontal, 14)
            }
            .scrollIndicators(.hidden)
            .frame(height: 44)
            HStack {
                Button { picked = [] } label: { Text("Cancel", tableName: "MomentSet") }
                    .foregroundStyle(LibraryColors.onInverse)
                Spacer()
                Text("\(picked.count) photos · \(days) days", tableName: "MomentSet")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(LibraryColors.onInverse)
                Spacer()
                Button { step = .naming } label: {
                    Text("Make a moment", tableName: "MomentSet")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(LibraryColors.onAccentLab)
                        .padding(.horizontal, 14)
                        .frame(height: 34)
                        .background(LibraryColors.accent, in: Capsule())
                }
                .disabled(picked.count < 2)
            }
            .padding(.horizontal, 14)
        }
        .padding(.vertical, 12)
        .background(LibraryColors.inverse, in: RoundedRectangle(cornerRadius: 20))
        .padding(.horizontal, 10)
        .padding(.bottom, 6)
        .opacity(picked.isEmpty ? 0 : 1)
        .animation(.easeInOut(duration: 0.2), value: picked.count)
    }

    private func toggle(_ photo: MomentSetLab.DayPhoto) {
        if let i = picked.firstIndex(where: { $0.id == photo.id }) {
            picked.remove(at: i)
        } else {
            picked.append(photo)
        }
        setHaptic()
    }

    // MARK: Naming

    private var naming: some View {
        let mainId = mainId ?? picked.first?.id
        return VStack(alignment: .leading, spacing: 18) {
            Text("New moment", tableName: "MomentSet").font(.system(size: 20, weight: .bold))
            TextField(text: $name) { Text("Name", tableName: "MomentSet") }
                .font(.system(size: 17))
                .padding(12)
                .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 8) {
                Text("Main photo", tableName: "MomentSet").font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.muted)
                ScrollView(.horizontal) {
                    HStack(spacing: 6) {
                        ForEach(picked) { photo in
                            SetTile(photo: photo.photo, isMain: photo.id == mainId)
                                .frame(width: 64)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                                .onTapGesture { self.mainId = photo.id }
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Show it in Moments on", tableName: "MomentSet").font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.muted)
                Picker(selection: $showOn) {
                    Text(verbatim: MomentSetLab.days.last!.title).tag(0)
                    Text(verbatim: MomentSetLab.days.first!.title).tag(1)
                    Text("Every day it has", tableName: "MomentSet").tag(2)
                } label: { EmptyView() }
                .pickerStyle(.segmented)
                Text(showOn == 2 ? "Each day shows its own photos, with a link to the whole moment." : "Its photos leave their days and show here together.", tableName: "MomentSet")
                    .font(.system(size: 12))
                    .foregroundStyle(LibraryColors.muted)
            }
            Spacer()
            Button {
                step = .made
            } label: {
                Text("Make it", tableName: "MomentSet")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(LibraryColors.onAccentLab)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(LibraryColors.accent, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(20)
        .background(LibraryColors.background)
        .foregroundStyle(LibraryColors.onBackground)
        .presentationDetents([.large])
    }
}

private extension LibraryColors {
    static let onAccentLab = Color.white
}

extension MomentSetLab {
    /// A photo of a day in the lab's library, for picking.
    struct DayPhoto: Identifiable {
        let photo: SetPhoto
        let day: Int
        var id: String { photo.id }
        var index: Int { photo.index }
    }

    struct Day: Identifiable {
        let id: Int
        let title: String
        let place: String
        let photos: [DayPhoto]
    }

    /// A weekend, newest first: beach and park photos.
    static let days: [Day] = [
        ("Sun, Nov 8", "Hod HaSharon", 1, 4),
        ("Sat, Nov 7", "Herzliya", 0, 5),
        ("Fri, Nov 6", "Hod HaSharon", 1, 4),
    ].enumerated().map { d, day in
        let (title, place, scene, count) = day
        return Day(id: d, title: title, place: place, photos: (0..<count).map { i in
            DayPhoto(photo: SetPhoto(id: "day\(d)-\(i)", index: i, of: count, aspect: i % 4 == 1 ? 4.0 / 3.0 : 3.0 / 4.0, scene: scene), day: d)
        })
    }
}

#endif
