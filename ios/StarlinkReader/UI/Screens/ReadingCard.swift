import SwiftUI

struct ReadingCard: View {
    @EnvironmentObject var state: AppState
    let reading: Reading
    let showDelete: Bool
    @State private var confirmDelete = false

    var body: some View {
        BrandCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    SectionLabel(text: NSLocalizedString("section_kit", comment: ""))
                    Spacer()
                    Text(Sharing.formatDate(reading.timestamp)).font(.footnote).foregroundColor(Brand.textMuted)
                }

                if reading.settings.kitNumber {
                    ScanField(
                        item: menuKit, value: reading.kitNumber,
                        onChange: { state.setField(uid: reading.uid, field: .kit, value: $0) },
                        onScan: { state.scanSingle(uid: reading.uid, step: .kit) }
                    )
                }
                if reading.settings.dishSerial {
                    ScanField(
                        item: menuDishSN, value: reading.dishSerial,
                        onChange: { state.setField(uid: reading.uid, field: .dishSerial, value: $0) },
                        onScan: { state.scanSingle(uid: reading.uid, step: .dishSerial) }
                    )
                }
                if reading.settings.modemSerial {
                    ScanField(
                        item: menuModemSN, value: reading.modemSerial,
                        onChange: { state.setField(uid: reading.uid, field: .modemSerial, value: $0) },
                        onScan: { state.scanSingle(uid: reading.uid, step: .modemSerial) }
                    )
                }

                if reading.settings.starlinkId || reading.settings.routerId || reading.settings.modemMac {
                    Divider().background(Brand.hairline)
                    SectionLabel(text: NSLocalizedString("section_network", comment: ""))

                    if reading.settings.starlinkId {
                        NetworkValue(item: menuStarlinkId, value: reading.starlinkId)
                    }
                    if reading.settings.routerId {
                        NetworkValue(item: menuRouterId, value: reading.routerId)
                    }
                    if reading.settings.modemMac {
                        ScanField(
                            item: menuMac, value: reading.effectiveMac,
                            onChange: { state.setField(uid: reading.uid, field: .modemMac, value: $0) },
                            onScan: nil
                        )
                    }

                    Button {
                        Task { await state.runNetworkStep(uid: reading.uid) }
                    } label: {
                        HStack(spacing: 6) {
                            RowIcon(systemName: AppIcon.refresh, tint: Brand.accent, size: 16)
                            Text(NSLocalizedString("action_retry_network", comment: "")).foregroundColor(Brand.accent)
                        }
                    }
                }

                Divider().background(Brand.hairline)

                HStack(spacing: 10) {
                    RowIcon(systemName: AppIcon.note)
                    SectionLabel(text: NSLocalizedString("notes_label", comment: ""))
                }
                ZStack(alignment: .topLeading) {
                    if reading.note.isEmpty {
                        Text(NSLocalizedString("notes_placeholder", comment: ""))
                            .foregroundColor(Brand.textMuted)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 18)
                    }
                    TextEditor(text: Binding(
                        get: { reading.note },
                        set: { state.setField(uid: reading.uid, field: .note, value: $0) }
                    ))
                    .scrollContentBackground(.hidden)
                    .padding(.horizontal, 10)
                    .foregroundColor(Brand.textPrimary)
                }
                .frame(minHeight: 90)
                .background(Brand.ink)
                .clipShape(RoundedCornerShape(radius: 10))
                .overlay(RoundedCornerShape(radius: 10).stroke(Brand.hairline, lineWidth: 1))

                HStack(spacing: 10) {
                    Button {
                        Sharing.share(Sharing.buildText(reading))
                    } label: {
                        HStack(spacing: 8) {
                            RowIcon(systemName: AppIcon.share, tint: Brand.onAccent, size: 18)
                            Text(NSLocalizedString("action_share", comment: "")).fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .foregroundColor(Brand.onAccent)
                    }
                    .background(Brand.accent)
                    .clipShape(RoundedCornerShape(radius: 14))

                    if showDelete {
                        Button {
                            confirmDelete = true
                        } label: {
                            RowIcon(systemName: AppIcon.delete, tint: Brand.alert, size: 18)
                                .frame(width: 50, height: 50)
                        }
                        .overlay(RoundedCornerShape(radius: 14).stroke(Brand.alert, lineWidth: 1))
                    }
                }
            }
            .padding(16)
        }
        .alert(NSLocalizedString("delete_one_title", comment: ""), isPresented: $confirmDelete) {
            Button(NSLocalizedString("action_delete", comment: ""), role: .destructive) {
                state.delete(reading.uid)
            }
            Button(NSLocalizedString("action_cancel", comment: ""), role: .cancel) {}
        } message: {
            Text(NSLocalizedString("delete_one_text", comment: ""))
        }
    }
}

/// Поле, яке заповнюється сканером, але яке завжди можна виправити руками.
private struct ScanField: View {
    let item: MenuItemInfo
    let value: String
    let onChange: (String) -> Void
    let onScan: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                StepBadge(number: item.number, active: !value.isEmpty)
                RowIcon(systemName: item.icon)
                Text(NSLocalizedString(item.titleKey, comment: "")).font(.subheadline).foregroundColor(Brand.textMuted)
            }
            HStack(spacing: 8) {
                TextField("—", text: Binding(get: { value }, set: onChange))
                    .font(.monoValue)
                    .foregroundColor(Brand.textPrimary)
                    .padding(10)
                    .background(Brand.ink)
                    .clipShape(RoundedCornerShape(radius: 10))
                    .overlay(RoundedCornerShape(radius: 10).stroke(Brand.hairline, lineWidth: 1))

                if let onScan {
                    Button(action: onScan) {
                        RowIcon(systemName: AppIcon.qr, tint: Brand.accent, size: 20)
                            .frame(width: 56, height: 56)
                    }
                    .overlay(RoundedCornerShape(radius: 10).stroke(Brand.hairline, lineWidth: 1))
                }
            }
        }
    }
}

/// Значення, зняте по мережі — лише для читання.
private struct NetworkValue: View {
    let item: MenuItemInfo
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                StepBadge(number: item.number, active: !value.isEmpty)
                RowIcon(systemName: item.icon)
                Text(NSLocalizedString(item.titleKey, comment: "")).font(.subheadline).foregroundColor(Brand.textMuted)
            }
            Text(value.isEmpty ? NSLocalizedString("value_not_read", comment: "") : value)
                .font(value.isEmpty ? .body : .monoValue)
                .foregroundColor(value.isEmpty ? Brand.textMuted : Brand.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Brand.ink)
                .clipShape(RoundedCornerShape(radius: 10))
        }
    }
}
