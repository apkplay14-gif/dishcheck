import SwiftUI

struct HomeScreen: View {
    @EnvironmentObject var state: AppState
    let onOpenHistory: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            Brand.ink.ignoresSafeArea()
            OrbitBackdrop().frame(height: 340).ignoresSafeArea(edges: .top)

            VStack(spacing: 0) {
                HStack {
                    WordMark()
                    Spacer()
                    Button(action: onOpenHistory) {
                        HStack(spacing: 6) {
                            RowIcon(systemName: AppIcon.history, tint: Brand.textPrimary, size: 18)
                            Text("\(state.history.count)").foregroundColor(Brand.textPrimary)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 4)

                ScrollView {
                    VStack(spacing: 14) {
                        LinkStatusCard()
                        CaptureMenuCard()
                        StartButton()

                        if let error = state.error {
                            ErrorCard(message: error)
                        }
                        if let current = state.current {
                            ReadingCard(reading: current, showDelete: false)
                        }
                        AdvancedSection()
                        Spacer(minLength: 16)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }

                AdBanner()
            }
        }
        .alert(
            NSLocalizedString("duplicate_title", comment: ""),
            isPresented: Binding(
                get: { state.duplicate != nil },
                set: { if !$0 { state.keepDuplicateAsNew() } }
            ),
            presenting: state.duplicate
        ) { _ in
            Button(NSLocalizedString("action_update_existing", comment: "")) { state.mergeDuplicate() }
            Button(NSLocalizedString("action_create_new", comment: ""), role: .cancel) { state.keepDuplicateAsNew() }
        } message: { prompt in
            Text(
                prompt.starlinkId + "\n" +
                    String(format: NSLocalizedString("duplicate_existing", comment: ""), Sharing.formatDate(prompt.existingTimestamp)) +
                    "\n" + NSLocalizedString("duplicate_hint", comment: "")
            )
        }
    }
}

private struct StartButton: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                state.startCapture()
            } label: {
                HStack(spacing: 10) {
                    if state.reading {
                        ProgressView().tint(Brand.onAccent)
                        Text(NSLocalizedString("state_polling", comment: "")).fontWeight(.semibold)
                    } else {
                        RowIcon(systemName: AppIcon.qr, tint: Brand.onAccent, size: 22)
                        Text(NSLocalizedString("btn_start_capture", comment: "")).fontWeight(.semibold)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 58)
                .foregroundColor(Brand.onAccent)
            }
            .background(Brand.accent)
            .clipShape(RoundedCornerShape(radius: 14))
            .disabled(state.reading || !state.settings.anyEnabled)
            .opacity(state.reading || !state.settings.anyEnabled ? 0.6 : 1)

            if !state.settings.anyEnabled {
                Text(NSLocalizedString("warn_select_one", comment: ""))
                    .font(.footnote)
                    .foregroundColor(Brand.alert)
            }
        }
    }
}

private struct ErrorCard: View {
    @EnvironmentObject var state: AppState
    let message: String

    var body: some View {
        BrandCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Circle().fill(Brand.alert).frame(width: 8, height: 8)
                    SectionLabel(text: NSLocalizedString("error_label", comment: ""))
                }
                Text(message).font(.subheadline).foregroundColor(Brand.textPrimary)
                Button(NSLocalizedString("action_understood", comment: "")) { state.dismissError() }
                    .foregroundColor(Brand.accent)
            }
            .padding(16)
        }
    }
}

private struct LinkStatusCard: View {
    @EnvironmentObject var state: AppState

    private struct Look {
        let color: Color
        let pill: String
        let title: String
        let hint: String
    }

    private var look: Look {
        switch state.link {
        case .noWifi:
            return Look(
                color: Brand.alert,
                pill: NSLocalizedString("link_no_wifi_pill", comment: ""),
                title: NSLocalizedString("link_no_wifi_title", comment: ""),
                hint: NSLocalizedString("link_no_wifi_hint", comment: "")
            )
        case .wifiNoDish:
            return Look(
                color: Brand.waiting,
                pill: NSLocalizedString("link_silent_pill", comment: ""),
                title: NSLocalizedString("link_silent_title", comment: ""),
                hint: NSLocalizedString("link_silent_hint", comment: "")
            )
        case .ready:
            return Look(
                color: Brand.online,
                pill: NSLocalizedString("link_ready_pill", comment: ""),
                title: NSLocalizedString("link_ready_title", comment: ""),
                hint: NSLocalizedString("link_ready_hint", comment: "")
            )
        }
    }

    var body: some View {
        let l = look
        BrandCard(accent: state.link == .ready) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    RowIcon(systemName: AppIcon.signal, tint: l.color, size: 24)
                    Text(l.title).font(.headline).foregroundColor(Brand.textPrimary)
                    Spacer()
                    if state.checkingLink {
                        ProgressView().tint(l.color)
                    } else {
                        StatusPill(color: l.color, text: l.pill)
                    }
                }
                Text(l.hint).font(.footnote).foregroundColor(Brand.textMuted)
                HStack(spacing: 8) {
                    if state.link == .noWifi {
                        Button(NSLocalizedString("action_wifi_settings", comment: "")) { openWifiSettings() }
                            .buttonStyle(.bordered)
                    }
                    Button {
                        Task { await state.refreshLink() }
                    } label: {
                        HStack(spacing: 6) {
                            RowIcon(systemName: AppIcon.refresh, tint: Brand.accent, size: 16)
                            Text(NSLocalizedString("action_check", comment: "")).foregroundColor(Brand.accent)
                        }
                    }
                }
            }
            .padding(16)
        }
    }

    private func openWifiSettings() {
        if let url = URL(string: "App-Prefs:root=WIFI") {
            UIApplication.shared.open(url)
        }
    }
}

private struct CaptureMenuCard: View {
    @EnvironmentObject var state: AppState
    @State private var expanded = true

    var body: some View {
        BrandCard {
            VStack(spacing: 0) {
                Button {
                    withAnimation { expanded.toggle() }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            SectionLabel(text: NSLocalizedString("capture_menu_title", comment: ""))
                            Text(String(format: NSLocalizedString("capture_menu_selected", comment: ""), state.settings.enabledCount))
                                .font(.headline)
                                .foregroundColor(Brand.textPrimary)
                        }
                        Spacer()
                        Text(NSLocalizedString(expanded ? "action_collapse" : "action_expand", comment: ""))
                            .font(.footnote)
                            .foregroundColor(Brand.accent)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                }

                if expanded {
                    VStack(spacing: 0) {
                        menuRow(menuKit, state.settings.kitNumber) { state.updateSettings(with(\.kitNumber, $0)) }
                        menuRow(menuDishSN, state.settings.dishSerial) { state.updateSettings(with(\.dishSerial, $0)) }
                        menuRow(menuModemSN, state.settings.modemSerial) { state.updateSettings(with(\.modemSerial, $0)) }
                        Divider().background(Brand.hairline).padding(.horizontal, 16).padding(.vertical, 6)
                        menuRow(menuStarlinkId, state.settings.starlinkId) { state.updateSettings(with(\.starlinkId, $0)) }
                        menuRow(menuRouterId, state.settings.routerId) { state.updateSettings(with(\.routerId, $0)) }
                        menuRow(menuMac, state.settings.modemMac) { state.updateSettings(with(\.modemMac, $0)) }
                    }
                    .padding(.bottom, 8)
                }
            }
            .padding(.vertical, 6)
        }
    }

    private func with(_ keyPath: WritableKeyPath<CaptureSettings, Bool>, _ value: Bool) -> CaptureSettings {
        var copy = state.settings
        copy[keyPath: keyPath] = value
        return copy
    }

    private func menuRow(_ item: MenuItemInfo, _ checked: Bool, onChange: @escaping (Bool) -> Void) -> some View {
        Button { onChange(!checked) } label: {
            HStack(spacing: 12) {
                StepBadge(number: item.number, active: checked)
                RowIcon(systemName: item.icon, tint: checked ? Brand.accent : Brand.textMuted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(NSLocalizedString(item.titleKey, comment: ""))
                        .foregroundColor(checked ? Brand.textPrimary : Brand.textMuted)
                    Text(NSLocalizedString(item.hintKey, comment: ""))
                        .font(.caption)
                        .foregroundColor(Brand.textMuted)
                }
                Spacer()
                Toggle("", isOn: Binding(get: { checked }, set: onChange))
                    .labelsHidden()
                    .tint(Brand.accent)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
        }
        .buttonStyle(.plain)
    }
}

private struct AdvancedSection: View {
    @EnvironmentObject var state: AppState
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading) {
            Button {
                withAnimation { expanded.toggle() }
            } label: {
                Text(NSLocalizedString(expanded ? "advanced_collapse" : "advanced_expand", comment: ""))
                    .font(.footnote)
                    .foregroundColor(Brand.textMuted)
            }

            if expanded {
                BrandCard {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(NSLocalizedString("fallback_title", comment: "")).font(.subheadline.weight(.semibold))
                                Text(NSLocalizedString("fallback_hint", comment: ""))
                                    .font(.caption)
                                    .foregroundColor(Brand.textMuted)
                            }
                            Spacer()
                            Toggle("", isOn: Binding(get: { state.allowRawProbe }, set: { state.setAllowRawProbe($0) }))
                                .labelsHidden()
                                .tint(Brand.accent)
                        }

                        if let log = state.diagnostics, !log.isEmpty {
                            Divider().background(Brand.hairline)
                            SectionLabel(text: NSLocalizedString("log_title", comment: ""))
                            ScrollView(.horizontal) {
                                Text(log)
                                    .font(.system(.footnote, design: .monospaced))
                                    .foregroundColor(Brand.textMuted)
                                    .padding(12)
                            }
                            .background(Brand.ink)
                            .clipShape(RoundedCornerShape(radius: 10))

                            Button {
                                Sharing.share(log)
                            } label: {
                                HStack(spacing: 6) {
                                    RowIcon(systemName: AppIcon.share, tint: Brand.accent, size: 16)
                                    Text(NSLocalizedString("action_send_log", comment: "")).foregroundColor(Brand.accent)
                                }
                            }
                        }

                        PrivacyOptionsEntry()
                    }
                    .padding(16)
                }
            }
        }
    }
}

/// Проста заміна MaterialTheme.shapes без прямого залежання від Compose-нотації.
struct RoundedCornerShape: Shape {
    var radius: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, cornerRadius: radius).cgPath)
    }
}
