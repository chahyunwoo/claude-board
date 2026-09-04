import SwiftUI

@main
struct ClaudeBoardMenuApp: App {
    @StateObject private var client = BoardClient()

    /// ⚠️ 구독은 **앱이 뜰 때** 시작해야 한다.
    ///
    /// 처음엔 MenuContent 의 `onAppear` 에 뒀는데, 메뉴바 창은 **클릭해야 그려지므로**
    /// 한 번도 안 누르면 영영 연결되지 않았다 — 메뉴바에 `⏳…`(연결 중)만 떠 있었다.
    /// 실측으로 밟았다: 백엔드는 정상인데 앱의 TCP 연결이 0개였다.
    init() {
        let client = BoardClient()
        _client = StateObject(wrappedValue: client)
        Task { @MainActor in client.start() }
    }

    var body: some Scene {
        MenuBarExtra {
            MenuContent(client: client)
        } label: {
            Label(client)
        }
        .menuBarExtraStyle(.window)
    }
}

/// 메뉴바에 **항상 보이는** 부분.
///
/// 답변 대기 수 하나만 낸다 — 메뉴바는 좁고, 그것이 가장 높은 가치다
/// (docs/00-개요.md 목표 2 "답변 대기 세션을 최상단에").
/// 나머지는 눌러서 본다.
private struct Label: View {
    @ObservedObject var client: BoardClient

    init(_ client: BoardClient) { self.client = client }

    var body: some View {
        switch client.health {
        case .ok, .collectFailed:
            // 0 이면 숫자를 안 낸다 — 기다리는 게 없으면 조용해야 한다.
            let n = client.waitingCount
            Text(n > 0 ? "⏳\(n)" : "⏳")
        case .disconnected:
            Text("⏳…")
        case .backendDown:
            Text("⏳✕")
        }
    }
}

private struct MenuContent: View {
    @ObservedObject var client: BoardClient

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Divider().padding(.vertical, 6)

            if let message = problem {
                Problem(message: message)
            } else if client.groups.isEmpty {
                Text("살아있는 세션이 없습니다")
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12).padding(.vertical, 8)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(client.groups, id: \.state) { group in
                            Group(state: group.state, projects: group.projects)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .frame(maxHeight: 420)
            }

            Divider().padding(.vertical, 6)
            footer
        }
        .frame(width: 340)
        .padding(.vertical, 8)
        // start() 는 앱 init 에서 이미 불렀다 — 여기서 또 부르면 구독이 두 개가 된다.
    }

    /// 문제가 있으면 그것부터 말한다. **조용히 비어 있는 화면을 만들지 않는다** —
    /// "세션이 없다"와 "읽지 못했다"는 다르다 (docs/02-백엔드.md).
    private var problem: String? {
        switch client.health {
        case .ok: return nil
        case .disconnected: return client.snapshot == nil ? "연결하는 중…" : nil
        case .backendDown:
            return client.pausedByUser ? "멈춤 — 아래 시작을 누르세요" : "백엔드가 떠 있지 않습니다"
        case .collectFailed(let message): return message
        }
    }

    private var header: some View {
        HStack {
            Text("CLAUDE SESSIONS").font(.system(size: 11, weight: .semibold)).tracking(1)
            Spacer()
            Circle()
                .fill(client.health.isHealthy ? Color.green : Color.orange)
                .frame(width: 6, height: 6)
        }
        .padding(.horizontal, 12)
    }

    private var footer: some View {
        VStack(spacing: 4) {
            HStack {
                if client.pausedByUser || client.health == .backendDown {
                    Button("시작") { client.startBackend() }
                } else {
                    Button("멈춤") { client.stopBackend() }
                }
                Button("웹으로 열기") {
                    NSWorkspace.shared.open(URL(string: "http://127.0.0.1:7777")!)
                }
                Spacer()
                Button("종료") { NSApplication.shared.terminate(nil) }
            }
            .buttonStyle(.link)
            .font(.system(size: 11))
        }
        .padding(.horizontal, 12)
    }
}

private struct Problem: View {
    let message: String
    var body: some View {
        Text(message)
            .font(.system(size: 11))
            .foregroundStyle(.orange)
            .padding(.horizontal, 12).padding(.vertical, 6)
    }
}

private struct Group: View {
    let state: SessionState
    let projects: [Project]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(state.symbol) \(state.label) (\(projects.count))")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(color)
            ForEach(projects) { project in
                Row(project: project)
            }
        }
    }

    /// docs/03-프론트.md "시각 규칙" — 웹 화면과 같은 색을 쓴다.
    private var color: Color {
        switch state {
        case .waiting: return Color(red: 0.85, green: 0.64, blue: 0.25)
        case .stalled: return Color(red: 0.79, green: 0.35, blue: 0.31)
        case .working: return Color(red: 0.37, green: 0.62, blue: 0.42)
        case .idle:    return .secondary
        }
    }
}

private struct Row: View {
    let project: Project

    var body: some View {
        HStack(spacing: 6) {
            Text(project.name).font(.system(size: 12))
            Spacer(minLength: 8)
            if let text = contextText {
                Text(text)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(contextColor)
            }
        }
    }

    /// 컨텍스트 경고 (#23). 70% 준비 / 85% 지금 —
    /// **평소엔 조용해야 경고가 보인다.**
    private var contextText: String? {
        guard let ratio = project.current.contextRatio else { return nil }
        return "\(Int(ratio * 100))%"
    }

    private var contextColor: Color {
        guard let ratio = project.current.contextRatio else { return .secondary }
        if ratio >= 0.85 { return Color(red: 0.83, green: 0.40, blue: 0.25) }
        if ratio >= 0.70 { return Color(red: 0.79, green: 0.64, blue: 0.15) }
        return .secondary
    }
}
