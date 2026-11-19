import SwiftUI

struct StreamingSettingsSection: View {
    let store: ShellSettingsStore
    @StateObject private var discovery = ReceiverDiscovery()
    @State private var revision = 0
    var body: some View {
        let _ = revision
        Section {
            Toggle(ShellStrings.text("Stream to Android"), isOn: Binding(get: { store.streamEnabled }, set: {
                store.storeStreamEnabled($0); revision += 1
            }))
            .accessibilityIdentifier("stream.enabled")
            Button { discovery.scan() } label: {
                HStack {
                    Label(ShellStrings.text("Find receivers"), systemImage: "antenna.radiowaves.left.and.right")
                    Spacer()
                    if discovery.searching { ProgressView() }
                }
            }
            .accessibilityIdentifier("stream.scan")
            ForEach(discovery.receivers) { receiver in
                Button {
                    store.storeStreamReceiver(receiver); revision += 1
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(verbatim: receiver.name)
                            Text(verbatim: "\(receiver.width) × \(receiver.height)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if store.streamReceiver?.id == receiver.id { Image(systemName: "checkmark") }
                    }
                }
                .accessibilityIdentifier("stream.receiver.\(receiver.host)")
            }
            if let selected = store.streamReceiver {
                LabeledContent(ShellStrings.text("Selected receiver"), value: selected.name)
                    .accessibilityIdentifier("stream.selected")
                Picker(ShellStrings.text("Output resolution"), selection: Binding(get: { store.streamSize }, set: {
                    store.storeStreamSize($0); revision += 1
                })) {
                    Text(ShellStrings.text("Automatic (match receiver)")).tag("auto")
                    ForEach(selected.modes.filter(StreamReceiver.validSize), id: \.self) { size in
                        Text(verbatim: size).tag(size)
                    }
                }
                .accessibilityIdentifier("stream.resolution")
            } else {
                Text(ShellStrings.text("Open NXStream on the receiver, then find receivers here."))
                    .foregroundStyle(.secondary)
            }
            Toggle(ShellStrings.text("Hide game picture on iPhone"), isOn: Binding(get: { store.streamBlack }, set: {
                store.storeStreamBlack($0); revision += 1
            }))
            .accessibilityIdentifier("stream.black")
            if !discovery.searching && discovery.receivers.isEmpty {
                Text(ShellStrings.text("No receiver found. Check Local Network permission and that the hotspot allows devices to communicate."))
                    .font(.caption).foregroundStyle(.secondary)
            }
        } footer: {
            Text(ShellStrings.text("Changes apply when you restart the game. Keep this app in the foreground and leave the iPhone unlocked."))
        }
        .onAppear { discovery.scan() }
        .onDisappear { discovery.stop() }
        .onChange(of: discovery.receivers) { _, receivers in
            // Refresh capabilities and addresses when the selected receiver is rediscovered.
            if let selected = store.streamReceiver,
               let current = receivers.first(where: { $0.id == selected.id }) {
                store.storeStreamReceiver(current); revision += 1
            }
        }
    }
}
