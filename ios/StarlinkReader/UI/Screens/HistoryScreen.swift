import SwiftUI

struct HistoryScreen: View {
    @EnvironmentObject var state: AppState
    let onBack: () -> Void
    let onOpen: (String) -> Void

    @State private var selected: Set<String> = []
    @State private var picking = false
    @State private var confirmDelete = false

    private var groups: [(day: Date, readings: [Reading])] {
        let sorted = state.history.sorted { $0.timestamp > $1.timestamp }
        let grouped = Dictionary(grouping: sorted) { Sharing.dayOf($0.timestamp) }
        return grouped.keys.sorted(by: >).map { ($0, grouped[$0] ?? []) }
    }

    private var today: Date { Calendar.current.startOfDay(for: Date()) }

    /// Видалений запис не має лишатись у вибраному, тому рахуємо по живих.
    private var chosen: [Reading] {
        state.history.filter { selected.contains($0.uid) }.sorted { $0.timestamp > $1.timestamp }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if state.history.isEmpty {
                emptyState
            } else {
                list
            }
            if picking, !chosen.isEmpty {
                bottomBar
            }
        }
        .background(Brand.ink.ignoresSafeArea())
        .alert(
            String(format: NSLocalizedString("delete_many_title", comment: ""), Plurals.records(chosen.count)),
            isPresented: $confirmDelete
        ) {
            Button(NSLocalizedString("action_delete", comment: ""), role: .destructive) {
                state.deleteAll(chosen.map(\.uid))
                picking = false
                selected = []
            }
            Button(NSLocalizedString("action_cancel", comment: ""), role: .cancel) {}
        } message: {
            Text(NSLocalizedString("delete_many_text", comment: ""))
        }
    }

    private var header: some View {
        HStack {
            Button {
                if picking {
                    picking = false
                    selected = []
                } else {
                    onBack()
                }
            } label: {
                RowIcon(systemName: AppIcon.back, tint: Brand.textPrimary, size: 20)
            }
            Spacer()
            Text(
                picking
                    ? String(format: NSLocalizedString("history_selected", comment: ""), chosen.count)
                    : NSLocalizedString("history_title", comment: "")
            )
            .font(.headline)
            .foregroundColor(Brand.textPrimary)
            Spacer()
            if picking {
                let allSelected = chosen.count == state.history.count
                Button(NSLocalizedString(allSelected ? "action_deselect_all" : "action_select_all", comment: "")) {
                    selected = allSelected ? [] : Set(state.history.map(\.uid))
                }
                .foregroundColor(Brand.accent)
            } else if !state.history.isEmpty {
                Button(NSLocalizedString("action_select", comment: "")) { picking = true }
                    .foregroundColor(Brand.accent)
            } else {
                Color.clear.frame(width: 1, height: 1)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            AppLogo(size: 56)
            Text(NSLocalizedString("history_empty_title", comment: ""))
                .font(.headline)
                .foregroundColor(Brand.textPrimary)
            Text(NSLocalizedString("history_empty_hint", comment: ""))
                .font(.subheadline)
                .foregroundColor(Brand.textMuted)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var list: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                ForEach(groups, id: \.day) { group in
                    let dayUids = Set(group.readings.map(\.uid))
                    DayHeaderRow(
                        label: Sharing.dayLabel(group.day, today: today),
                        count: group.readings.count,
                        picking: picking,
                        allSelected: dayUids.isSubset(of: selected),
                        onToggleDay: {
                            if dayUids.isSubset(of: selected) {
                                selected.subtract(dayUids)
                            } else {
                                selected.formUnion(dayUids)
                            }
                        }
                    )
                    ForEach(group.readings) { reading in
                        HistoryRow(
                            reading: reading,
                            picking: picking,
                            checked: selected.contains(reading.uid),
                            onTap: {
                                if picking {
                                    if selected.contains(reading.uid) {
                                        selected.remove(reading.uid)
                                    } else {
                                        selected.insert(reading.uid)
                                    }
                                } else {
                                    onOpen(reading.uid)
                                }
                            },
                            onLongPress: {
                                picking = true
                                selected.insert(reading.uid)
                            }
                        )
                    }
                }
            }
            .padding(16)
        }
    }

    private var bottomBar: some View {
        HStack(spacing: 10) {
            Button {
                Sharing.share(Sharing.buildText(chosen))
            } label: {
                HStack(spacing: 8) {
                    RowIcon(systemName: AppIcon.share, tint: Brand.onAccent, size: 18)
                    Text(String(format: NSLocalizedString("action_share_count", comment: ""), chosen.count))
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .foregroundColor(Brand.onAccent)
            }
            .background(Brand.accent)
            .clipShape(RoundedCornerShape(radius: 14))

            Button {
                confirmDelete = true
            } label: {
                RowIcon(systemName: AppIcon.delete, tint: Brand.alert, size: 18)
                    .frame(width: 52, height: 52)
            }
            .overlay(RoundedCornerShape(radius: 14).stroke(Brand.alert, lineWidth: 1))
        }
        .padding(16)
    }
}

private struct DayHeaderRow: View {
    let label: String
    let count: Int
    let picking: Bool
    let allSelected: Bool
    let onToggleDay: () -> Void

    var body: some View {
        HStack {
            SectionLabel(text: label)
            Text("\(count)").font(.footnote).foregroundColor(Brand.textMuted)
            Spacer()
            if picking {
                Button(NSLocalizedString(allSelected ? "day_deselect" : "day_select", comment: "")) {
                    onToggleDay()
                }
                .font(.footnote)
                .foregroundColor(Brand.accent)
            }
        }
        .padding(.top, 8)
    }
}

private struct HistoryRow: View {
    let reading: Reading
    let picking: Bool
    let checked: Bool
    let onTap: () -> Void
    let onLongPress: () -> Void

    var body: some View {
        BrandCard(accent: picking && checked) {
            HStack(spacing: 14) {
                if picking {
                    Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                        .foregroundColor(checked ? Brand.accent : Brand.hairline)
                } else {
                    RowIcon(systemName: AppIcon.box, tint: Brand.accent, size: 22)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(reading.titleOrNull ?? NSLocalizedString("record_untitled", comment: ""))
                        .font(.monoValue)
                        .foregroundColor(Brand.textPrimary)
                    Text(Sharing.formatTime(reading.timestamp)).font(.footnote).foregroundColor(Brand.textMuted)
                    if !reading.note.isEmpty {
                        Text(reading.note.split(separator: "\n").first.map(String.init) ?? "")
                            .font(.footnote)
                            .foregroundColor(Brand.textMuted)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if !picking {
                    Text("›").foregroundColor(Brand.textMuted).font(.title3)
                }
            }
            .padding(16)
        }
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
        .onLongPressGesture { onLongPress() }
    }
}
