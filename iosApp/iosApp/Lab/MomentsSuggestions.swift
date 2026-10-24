#if DEBUG

import SwiftUI

// Suggestions for the Moments tab: other ways to go to a year, a month and a day with one finger (the app has the month
// pill, FeedTimelineViews.swift). UI only, on made-up data: a stand-in feed of seven years (`LabTimeline`), a day's
// divider and up to three photos per day, enough to feel the distances. Each option is the same feed with a different
// scrubber over it.

// MARK: - The stand-in feed

/// A day with photos in the stand-in feed.
struct LabTimelineDay: Identifiable, Hashable {
    let id: Int
    let date: Date
    let count: Int
    let place: String
}

/// A month with photos: its days, newest first, as indexes into `LabTimeline.days`.
struct LabTimelineMonth: Identifiable, Hashable {
    let id: Int
    let year: Int
    let month: Int
    let days: [Int]
    let count: Int
}

/// Seven years of made-up days, newest first: quiet weeks, busy weekends and a few trips, the same on every run.
final class LabTimeline {
    static let shared = LabTimeline()

    let days: [LabTimelineDay]
    /// Newest first, as the feed.
    let months: [LabTimelineMonth]
    let calendar = Calendar.current

    private init() {
        let calendar = Calendar.current
        let places = ["Tel Aviv", "Jaffa", "Haifa", "Jerusalem", "Eilat", "Lisbon", "Porto", "Paris", "Rome", "Tokyo", "Kyoto", "Berlin"]
        var seed: UInt64 = 0x9E3779B97F4A7C15
        func next() -> Int {
            seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17
            return Int(seed % 1000)
        }
        let end = calendar.date(from: DateComponents(year: 2026, month: 10, day: 24))!
        let start = calendar.date(from: DateComponents(year: 2019, month: 3, day: 2))!
        var days: [LabTimelineDay] = []
        var date = end
        var trip = 0
        var tripPlace = ""
        while date >= start {
            let weekend = [6, 7].contains(calendar.component(.weekday, from: date))
            if trip == 0 && next() < 8 {
                trip = 4 + next() % 9
                tripPlace = places[5 + next() % 7]
            }
            let chance = trip > 0 ? 950 : weekend ? 520 : 230
            if next() < chance {
                let count = trip > 0 ? 8 + next() % 40 : 1 + next() % (weekend ? 18 : 6)
                days.append(LabTimelineDay(id: days.count, date: date, count: count, place: trip > 0 ? tripPlace : places[next() % 5]))
            }
            if trip > 0 { trip -= 1 }
            date = calendar.date(byAdding: .day, value: -1, to: date)!
        }
        self.days = days
        var months: [LabTimelineMonth] = []
        for day in days {
            let c = calendar.dateComponents([.year, .month], from: day.date)
            if let last = months.last, last.year == c.year, last.month == c.month {
                months[months.count - 1] = LabTimelineMonth(id: last.id, year: last.year, month: last.month, days: last.days + [day.id], count: last.count + day.count)
            } else {
                months.append(LabTimelineMonth(id: months.count, year: c.year!, month: c.month!, days: [day.id], count: day.count))
            }
        }
        self.months = months
    }

    func month(of dayId: Int) -> LabTimelineMonth {
        let c = calendar.dateComponents([.year, .month], from: days[dayId].date)
        return months.first { $0.year == c.year && $0.month == c.month } ?? months[0]
    }

    func dayOfMonth(_ dayId: Int) -> Int { calendar.component(.day, from: days[dayId].date) }

    var years: [Int] { Array(Set(months.map(\.year))).sorted(by: >) }

    static func monthName(_ month: Int, short: Bool = false) -> String {
        let symbols = short ? Calendar.current.shortMonthSymbols : Calendar.current.monthSymbols
        return symbols[month - 1]
    }

    static func dayLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEMMMdyyyy")
        return formatter.string(from: date)
    }
}

/// The Moments tab drawn from `LabTimeline`: the top bar, the feed, the bottom tabs, and `scrubber` over it all.
/// `position` is the day at the top of the feed, both ways: the feed sets it while scrolling, a scrubber to jump.
private struct LabTimelineFeed<Scrubber: View>: View {
    @Binding var position: Int?
    @ViewBuilder let scrubber: Scrubber
    private let timeline = LabTimeline.shared

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(timeline.days) { day in
                        LabTimelineDayView(day: day, width: geometry.size.width).id(day.id)
                    }
                }
                .scrollTargetLayout()
            }
            // Margins, not padding: a day jumped to lands under the top bar, not behind it.
            .contentMargins(.top, 100, for: .scrollContent)
            .contentMargins(.bottom, 150, for: .scrollContent)
            .scrollPosition(id: $position, anchor: .top)
            .scrollIndicators(.hidden)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .overlay(alignment: .top) { topBar }
        .overlay(alignment: .bottom) {
            BottomControls(summary: nil) {
                SegmentedBar(options: tabs, selection: .constant(.moments))
            }
        }
        .overlay { scrubber }
        .toolbar(.hidden, for: .navigationBar)
        .preferredColorScheme(.dark)
    }

    private let tabs: [(HomeTab, LocalizedStringKey)] = [(.storyline, "Storyline"), (.moments, "Moments"), (.explore, "Explore")]

    private var topBar: some View {
        HStack {
            Text("Journal").font(.system(size: 30, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 24)
        .background(LibraryColors.topFade.ignoresSafeArea())
    }
}

private struct LabTimelineDayView: View {
    let day: LabTimelineDay
    let width: CGFloat

    var body: some View {
        VStack(spacing: tileGap) {
            VStack(spacing: 4) {
                DayLine(label: LabTimeline.dayLabel(day.date))
                Text("\(day.place) · \(day.count) photos").font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
            }
            .padding(.horizontal, 16)
            .padding(.top, 28)
            .padding(.bottom, 8)
            ForEach(0..<min(day.count, 3), id: \.self) { i in
                let ratio: CGFloat = [0.75, 1.33, 1.0][(day.id + i) % 3]
                Rectangle()
                    .fill(Color(hue: Double((day.id * 37 + i * 11) % 100) / 100, saturation: 0.22, brightness: 0.62))
                    .frame(width: width, height: width * ratio)
            }
        }
    }
}

/// The pill at the top of the feed saying where it is: "October 2024".
private struct WherePill: View {
    let month: LabTimelineMonth
    var open = false

    var body: some View {
        HStack(spacing: 6) {
            Text(verbatim: "\(LabTimeline.monthName(month.month)) \(String(month.year))")
                .font(.system(size: 15, weight: .semibold))
                .monospacedDigit()
                .contentTransition(.numericText())
            Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold)).rotationEffect(.degrees(open ? 180 : 0))
        }
        .foregroundStyle(LibraryColors.onBackground)
        .padding(.horizontal, 14)
        .frame(height: 34)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(LibraryColors.onBackground.opacity(0.12)))
        .contentShape(Capsule())
    }
}

// MARK: - 1. Edge scrubber

/// A handle on the right edge, where the thumb already is. Grab it and slide: the edge becomes a rail of the years, a
/// bubble says the month under the finger, and the feed follows. Slide left, away from the edge, and the bubble opens
/// the month as a calendar: up and down now go day by day. Let go to stay there.
struct EdgeScrubberSuggestion: View {
    @State private var position: Int? = 0
    @State private var drag: Drag?
    private let timeline = LabTimeline.shared

    private struct Drag {
        var month: Int
        var day: Int
        var fine = false
        /// Where the finger went into day mode, and the day it was on.
        var fineStartY: CGFloat = 0
        var fineStartDay = 0
        /// Coming back from day mode keeps the month where it was instead of jumping to the finger.
        var monthShift = 0
    }

    var body: some View {
        LabTimelineFeed(position: $position) {
            GeometryReader { geometry in
                let rail = railFrame(geometry.size)
                ZStack(alignment: .topLeading) {
                    if drag != nil { railLabels(rail).transition(.opacity) }
                    handle(rail)
                    if let drag { bubble(drag, rail: rail).transition(.scale(scale: 0.9, anchor: .trailing).combined(with: .opacity)) }
                }
                .animation(.snappy(duration: 0.2), value: drag?.fine)
                .animation(.easeOut(duration: 0.15), value: drag == nil)
            }
        }
    }

    private func railFrame(_ size: CGSize) -> CGRect {
        CGRect(x: size.width - 44, y: 120, width: 44, height: size.height - 120 - 130)
    }

    private var currentMonth: Int { drag?.month ?? timeline.month(of: position ?? 0).id }

    private func y(ofMonth index: Int, in rail: CGRect) -> CGFloat {
        rail.minY + rail.height * CGFloat(index) / CGFloat(max(timeline.months.count - 1, 1))
    }

    private func month(atY y: CGFloat, in rail: CGRect) -> Int {
        let f = min(max((y - rail.minY) / rail.height, 0), 1)
        return Int((f * CGFloat(timeline.months.count - 1)).rounded())
    }

    private func handle(_ rail: CGRect) -> some View {
        let y = y(ofMonth: currentMonth, in: rail)
        let grabbed = drag != nil
        return Capsule()
            .fill(grabbed ? LibraryColors.onBackground : LibraryColors.onBackground.opacity(0.55))
            .frame(width: grabbed ? 6 : 5, height: grabbed ? 44 : 36)
            .overlay(alignment: .trailing) {
                Color.clear.frame(width: 44, height: 64).contentShape(Rectangle())
            }
            .position(x: rail.maxX - 8, y: y)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .contentShape(Rectangle().path(in: rail))
            .gesture(DragGesture(minimumDistance: 0).onChanged { dragged($0, rail: rail) }.onEnded { _ in drag = nil })
    }

    private func dragged(_ value: DragGesture.Value, rail: CGRect) {
        var d = drag ?? Drag(month: month(atY: value.location.y, in: rail), day: 0)
        let away = rail.maxX - value.location.x
        if !d.fine && away > 90 {
            d.fine = true
            d.fineStartY = value.location.y
            d.fineStartDay = d.day
        } else if d.fine && away < 60 {
            d.fine = false
            d.monthShift = d.month - month(atY: value.location.y, in: rail)
        }
        let before = (d.month, d.day)
        let months = timeline.months
        if d.fine {
            // Down is older, as in the feed: 22 pt a day.
            let days = months[d.month].days
            let step = Int(((value.location.y - d.fineStartY) / 22).rounded())
            d.day = min(max(d.fineStartDay + step, 0), days.count - 1)
        } else {
            let m = min(max(month(atY: value.location.y, in: rail) + d.monthShift, 0), months.count - 1)
            if m != d.month { d.day = 0 }
            d.month = m
        }
        if drag == nil || before != (d.month, d.day) {
            if drag != nil && months[before.0].year != months[d.month].year { ScrubHaptics.year.impactOccurred() } else { ScrubHaptics.tick.selectionChanged() }
            position = months[d.month].days[d.day]
        }
        drag = d
    }

    private func railLabels(_ rail: CGRect) -> some View {
        ForEach(timeline.years, id: \.self) { year in
            let first = timeline.months.firstIndex { $0.year == year }!
            Text(verbatim: String(year))
                .font(.system(size: 11, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(LibraryColors.onBackground.opacity(timeline.months[currentMonth].year == year ? 1 : 0.55))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(LibraryColors.pillScrim, in: Capsule())
                .position(x: rail.maxX - 40, y: y(ofMonth: first, in: rail))
        }
    }

    @ViewBuilder
    private func bubble(_ drag: Drag, rail: CGRect) -> some View {
        let month = timeline.months[drag.month]
        let day = timeline.days[month.days[drag.day]]
        let y = min(max(y(ofMonth: drag.month, in: rail), rail.minY + 60), rail.maxY - 60)
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: "\(LabTimeline.monthName(month.month)) \(String(month.year))")
                .font(.system(size: 22, weight: .bold))
                .monospacedDigit()
            if drag.fine {
                MonthCalendar(month: month, selectedDay: timeline.dayOfMonth(day.id))
                Text(verbatim: "\(LabTimeline.dayLabel(day.date)) · \(day.count)").font(.system(size: 13, weight: .semibold))
            } else {
                Text("\(month.count) photos · \(month.days.count) days").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                Label("Slide left for days", systemImage: "arrow.left").font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
            }
        }
        .foregroundStyle(LibraryColors.onBackground)
        .padding(14)
        .frame(width: drag.fine ? 236 : 200, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
        .position(x: rail.minX - (drag.fine ? 140 : 110), y: y)
        .allowsHitTesting(false)
    }
}

/// A month as a calendar, days with photos lit, `selectedDay` ringed.
private struct MonthCalendar: View {
    let month: LabTimelineMonth
    let selectedDay: Int?
    var cell: CGFloat = 26
    private let timeline = LabTimeline.shared

    var body: some View {
        let calendar = timeline.calendar
        let first = calendar.date(from: DateComponents(year: month.year, month: month.month, day: 1))!
        let count = calendar.range(of: .day, in: .month, for: first)!.count
        let lead = (calendar.component(.weekday, from: first) - calendar.firstWeekday + 7) % 7
        let lit = Set(month.days.map { timeline.dayOfMonth($0) })
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: 4), count: 7), spacing: 4) {
            ForEach(0..<(lead + count), id: \.self) { i in
                let day = i - lead + 1
                if day < 1 {
                    Color.clear.frame(width: cell, height: cell)
                } else {
                    Text(verbatim: "\(day)")
                        .font(.system(size: 11, weight: lit.contains(day) ? .bold : .regular))
                        .monospacedDigit()
                        .foregroundStyle(lit.contains(day) ? LibraryColors.onBackground : LibraryColors.muted.opacity(0.5))
                        .frame(width: cell, height: cell)
                        .background(lit.contains(day) ? LibraryColors.accent.opacity(0.55) : .clear, in: Circle())
                        .overlay(Circle().stroke(LibraryColors.onBackground, lineWidth: 2).opacity(day == selectedDay ? 1 : 0))
                }
            }
        }
    }
}

// MARK: - 2. Time ruler

/// The pill at the top says where the feed is. Tap it and a ruler rises over the tabs: every month a bar as tall as
/// its photos, years under them, a needle in the middle. Swipe the ruler like a camera's zoom dial and the feed
/// follows; under it the month's days, lit where there are photos, to tap or slide across.
struct TimeRulerSuggestion: View {
    @State private var position: Int? = 0
    @State private var open = false
    /// The month under the needle, while the ruler is open.
    @State private var rulerMonth: Int?
    private let timeline = LabTimeline.shared

    var body: some View {
        let month = timeline.month(of: position ?? 0)
        LabTimelineFeed(position: $position) {
            VStack {
                Button {
                    rulerMonth = month.id
                    withAnimation(.snappy) { open.toggle() }
                } label: { WherePill(month: month, open: open) }
                .buttonStyle(.plain)
                .padding(.top, 60)
                Spacer()
                if open {
                    panel(month).transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.snappy, value: month.id)
        }
        .onChange(of: rulerMonth) { old, new in
            guard open, let new, let old, new != old else { return }
            if timeline.months[old].year != timeline.months[new].year { ScrubHaptics.year.impactOccurred() } else { ScrubHaptics.tick.selectionChanged() }
            position = timeline.months[new].days.first
        }
    }

    private func panel(_ month: LabTimelineMonth) -> some View {
        VStack(spacing: 12) {
            HStack {
                Text("\(month.count) photos · \(month.days.count) days").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                Spacer()
                Button("Done") { withAnimation(.snappy) { open = false } }.font(.system(size: 15, weight: .semibold))
            }
            MonthRuler(selection: $rulerMonth)
            DayStrip(month: month, selectedDay: timeline.dayOfMonth(position ?? 0)) { position = $0 }
        }
        .foregroundStyle(LibraryColors.onBackground)
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28))
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }
}

/// The months oldest to newest, left to right, a bar each; the one at the needle is `selection`. Dragged like a dial:
/// it follows the finger, a flick keeps it turning, and it settles on a month.
private struct MonthRuler: View {
    @Binding var selection: Int?
    /// Where the dial is, in months from the oldest: fractional while it turns.
    @State private var at: CGFloat?
    @State private var dragStart: CGFloat?
    private let timeline = LabTimeline.shared
    private let step: CGFloat = 16

    var body: some View {
        let months = Array(timeline.months.reversed())
        let peak = CGFloat(timeline.months.map(\.count).max() ?? 1)
        let current = at ?? CGFloat(months.count - 1 - (selection ?? 0))
        GeometryReader { geometry in
            let mid = geometry.size.width / 2
            let reach = Int(mid / step) + 2
            let lo = max(Int(current) - reach, 0), hi = min(Int(current) + reach, months.count - 1)
            ZStack(alignment: .topLeading) {
                ForEach(lo...hi, id: \.self) { i in
                    let month = months[i]
                    let picked = month.id == selection
                    VStack(spacing: 4) {
                        Spacer(minLength: 0)
                        Capsule()
                            .fill(picked ? LibraryColors.onBackground : LibraryColors.muted.opacity(0.6))
                            .frame(width: picked ? 8 : 6, height: 6 + 40 * sqrt(CGFloat(month.count) / peak))
                        Text(verbatim: month.month == 1 ? String(month.year) : month.month == 7 ? "·" : "")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(LibraryColors.muted)
                            .fixedSize()
                            .frame(width: step, height: 12)
                    }
                    .frame(width: step, height: geometry.size.height)
                    .position(x: mid + (CGFloat(i) - current) * step, y: geometry.size.height / 2)
                }
                Rectangle().fill(LibraryColors.accent).frame(width: 2, height: geometry.size.height - 16).position(x: mid, y: (geometry.size.height - 16) / 2)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 2)
                .onChanged { value in
                    let start = dragStart ?? current
                    dragStart = start
                    turn(to: start - value.translation.width / step, months: months)
                }
                .onEnded { value in
                    let start = dragStart ?? current
                    dragStart = nil
                    // A flick goes on as far as it would have slid, then settles on a month.
                    let end = (start - value.predictedEndTranslation.width / step).rounded()
                    let target = min(max(end, 0), CGFloat(months.count - 1))
                    withAnimation(.snappy(duration: 0.45)) { at = target }
                    selection = months[Int(target)].id
                })
        }
        .frame(height: 66)
        .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.12), .init(color: .black, location: 0.88), .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
    }

    /// Follows the finger; the month at the needle is picked as it passes.
    private func turn(to position: CGFloat, months: [LabTimelineMonth]) {
        let clamped = min(max(position, 0), CGFloat(months.count - 1))
        at = clamped
        let id = months[Int(clamped.rounded())].id
        if id != selection { selection = id }
    }
}

/// The days of a month in a line; a day with photos is lit. Tap one, or slide along: each lit day the finger crosses
/// lands the feed there.
private struct DayStrip: View {
    let month: LabTimelineMonth
    let selectedDay: Int
    let onDay: (Int) -> Void
    private let timeline = LabTimeline.shared

    var body: some View {
        let first = timeline.calendar.date(from: DateComponents(year: month.year, month: month.month, day: 1))!
        let count = timeline.calendar.range(of: .day, in: .month, for: first)!.count
        let byDay = Dictionary(month.days.map { (timeline.dayOfMonth($0), $0) }, uniquingKeysWith: { a, _ in a })
        GeometryReader { geometry in
            let w = geometry.size.width / CGFloat(count)
            HStack(spacing: 0) {
                ForEach(1...count, id: \.self) { day in
                    VStack(spacing: 3) {
                        Circle()
                            .fill(byDay[day] != nil ? LibraryColors.accent : LibraryColors.muted.opacity(0.25))
                            .frame(width: day == selectedDay ? 9 : 6, height: day == selectedDay ? 9 : 6)
                            .overlay(Circle().stroke(LibraryColors.onBackground, lineWidth: 1.5).padding(-3).opacity(day == selectedDay ? 1 : 0))
                        Text(verbatim: day == 1 || day % 5 == 0 ? "\(day)" : "").font(.system(size: 9)).foregroundStyle(LibraryColors.muted).fixedSize()
                    }
                    .frame(width: w)
                }
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                let day = min(max(Int(value.location.x / w) + 1, 1), count)
                // The nearest lit day to the finger.
                guard let lit = byDay.keys.min(by: { abs($0 - day) < abs($1 - day) }), lit != selectedDay, let id = byDay[lit] else { return }
                ScrubHaptics.tick.selectionChanged()
                onDay(id)
            })
        }
        .frame(height: 32)
        .animation(.snappy(duration: 0.15), value: selectedDay)
    }
}

// MARK: - 3. Date pad

/// Press and hold the pill at the top, then move the finger without lifting it: up and down change the year, left and
/// right the month, like a joystick. The years show as a column and the months as a row through the finger. Let go
/// and the feed is at that month; the pill then offers its days.
struct DatePadSuggestion: View {
    @State private var position: Int? = 0
    @State private var pad: Pad?
    @State private var showDays = false
    private let timeline = LabTimeline.shared
    private let yearStep: CGFloat = 52
    private let monthStep: CGFloat = 30

    private struct Pad: Equatable {
        var startYear: Int
        var startMonth: Int
        var year: Int
        var month: Int
    }

    var body: some View {
        let month = timeline.month(of: position ?? 0)
        LabTimelineFeed(position: $position) {
            ZStack(alignment: .top) {
                if let pad {
                    Rectangle().fill(.black.opacity(0.55)).ignoresSafeArea().transition(.opacity)
                    cross(pad).padding(.top, 77)
                }
                VStack(spacing: 10) {
                    WherePill(month: pad.map { nearest(year: $0.year, month: $0.month) } ?? month, open: showDays)
                        .scaleEffect(pad != nil ? 1.12 : 1)
                        .gesture(press)
                        .simultaneousGesture(TapGesture().onEnded { withAnimation(.snappy) { showDays.toggle() } })
                    if showDays && pad == nil {
                        MonthCalendar(month: month, selectedDay: timeline.dayOfMonth(position ?? 0), cell: 34)
                            .padding(14)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22))
                            .overlay { dayTaps(month) }
                            .transition(.scale(scale: 0.9, anchor: .top).combined(with: .opacity))
                    }
                    if pad == nil && !showDays {
                        Text("Hold and slide · tap for days").font(.system(size: 11)).foregroundStyle(LibraryColors.muted)
                    }
                }
                .padding(.top, 60)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .animation(.snappy(duration: 0.2), value: pad == nil)
        }
    }

    private var press: some Gesture {
        LongPressGesture(minimumDuration: 0.25)
            .sequenced(before: DragGesture(minimumDistance: 0))
            .onChanged { value in
                guard case .second(true, let drag) = value else { return }
                let current = timeline.month(of: position ?? 0)
                var p = pad ?? Pad(startYear: current.year, startMonth: current.month, year: current.year, month: current.month)
                if pad == nil { ScrubHaptics.year.impactOccurred(); showDays = false }
                let t = drag?.translation ?? .zero
                let years = timeline.years
                // Down is older, as in the feed; right is a later month.
                let yearIndex = min(max(years.firstIndex(of: p.startYear)! + Int((t.height / yearStep).rounded()), 0), years.count - 1)
                let month = min(max(p.startMonth + Int((t.width / monthStep).rounded()), 1), 12)
                if (years[yearIndex], month) != (p.year, p.month) {
                    if years[yearIndex] != p.year { ScrubHaptics.year.impactOccurred() } else { ScrubHaptics.tick.selectionChanged() }
                    p.year = years[yearIndex]
                    p.month = month
                }
                pad = p
            }
            .onEnded { _ in
                if let pad { position = nearest(year: pad.year, month: pad.month).days.first }
                pad = nil
            }
    }

    /// The month with photos nearest to the one picked.
    private func nearest(year: Int, month: Int) -> LabTimelineMonth {
        timeline.months.min { abs(($0.year * 12 + $0.month) - (year * 12 + month)) < abs(($1.year * 12 + $1.month) - (year * 12 + month)) }!
    }

    private func cross(_ pad: Pad) -> some View {
        let years = timeline.years
        let yearIndex = years.firstIndex(of: pad.year)!
        let target = timeline.months.first { $0.year == pad.year && $0.month == pad.month }
        return ZStack {
            // The years, a column through the pill.
            ForEach(Array(years.enumerated()), id: \.element) { i, year in
                let offset = CGFloat(i - yearIndex)
                Text(verbatim: String(year))
                    .font(.system(size: offset == 0 ? 34 : 20, weight: .bold))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(offset == 0 ? 1 : max(0.15, 0.6 - abs(offset) * 0.12)))
                    // Older years sit under the months, which go through the year picked.
                    .offset(y: 70 + offset * yearStep + (offset > 0 ? 96 : 0))
            }
            // The months of that year, a row through the year.
            ForEach(1...12, id: \.self) { m in
                let has = timeline.months.contains { $0.year == pad.year && $0.month == m }
                let offset = CGFloat(m - pad.month)
                Text(verbatim: LabTimeline.monthName(m, short: true))
                    .font(.system(size: 14, weight: m == pad.month ? .bold : .medium))
                    .foregroundStyle(m == pad.month ? LibraryColors.paperInk : .white.opacity(has ? 0.85 : 0.3))
                    .frame(width: monthStep * 1.5, height: 28)
                    .background(m == pad.month ? Color.white : .clear, in: Capsule())
                    .offset(x: offset * monthStep * 1.6, y: 112)
                    .opacity(abs(offset) > 4 ? 0 : 1)
            }
            Text(target.map { "\($0.count) photos · \($0.days.count) days" } ?? "No photos — lands on the nearest month")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
                .offset(y: 154)
        }
        .animation(.snappy(duration: 0.18), value: pad)
        .allowsHitTesting(false)
    }

    /// Taps on the calendar's lit days land the feed there.
    private func dayTaps(_ month: LabTimelineMonth) -> some View {
        Color.clear.contentShape(Rectangle()).onTapGesture { _ in }
            .simultaneousGesture(SpatialTapGesture().onEnded { value in
                let calendar = timeline.calendar
                let first = calendar.date(from: DateComponents(year: month.year, month: month.month, day: 1))!
                let lead = (calendar.component(.weekday, from: first) - calendar.firstWeekday + 7) % 7
                let col = Int((value.location.x - 14) / 38), row = Int((value.location.y - 14) / 38)
                let day = row * 7 + col - lead + 1
                guard let id = month.days.first(where: { timeline.dayOfMonth($0) == day }) else { return }
                ScrubHaptics.tick.selectionChanged()
                position = id
                withAnimation(.snappy) { showDays = false }
            })
    }
}

#endif
