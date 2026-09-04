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
                        ForEach(client.groups) { group in
                            StateGroup(state: group.state, projects: group.projects, client: client)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                // ⚠️ **상한만 주면 눌린다.** VStack 안의 ScrollView 는 남는 공간을
                // 못 받으면 최소 높이로 쪼그라들어, 목록이 있는데도 한두 줄만 보인다
                // (#37 에서 실측 — 12개가 있는데 화면에 거의 안 나왔다).
                // 하한을 함께 줘서 **최소 10행**은 스크롤 없이 보이게 한다.
                //
                // 10행 = (행 16 + spacing 4) × 10 + 그룹 헤더·간격 여유 ≈ 260
                .frame(minHeight: min(contentHeight, 260), maxHeight: 560)
            }

            if !client.selected.isEmpty {
                Divider().padding(.vertical, 6)
                SelectionBar(client: client)
            }

            Divider().padding(.vertical, 6)
            footer
        }
        .frame(width: 380)
        .padding(.vertical, 8)
        // start() 는 앱 init 에서 이미 불렀다 — 여기서 또 부르면 구독이 두 개가 된다.
    }

    /// 목록이 실제로 차지하는 높이 추정.
    ///
    /// 항목이 적으면 하한을 그만큼만 준다 — 세션 2개인데 창이 260 이면
    /// 아래가 휑하게 빈다. 상수는 폰트 크기(행 12pt·헤더 11pt)에서 나온 값이다.
    private var contentHeight: CGFloat {
        let rows = client.groups.reduce(0) { $0 + $1.projects.count }
        return CGFloat(rows) * 20 + CGFloat(client.groups.count) * 29
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

/// 선택한 세션들을 죽이는 명령을 **클립보드에 넣어준다.**
///
/// ⚠️ **앱이 직접 죽이지 않는다** (docs/00-개요.md "조회 전용").
/// 복사는 조회이고, 실제로 죽는 것은 터미널에서 사용자가 붙여넣고 엔터를 칠 때다 —
/// 그 사이에 눈으로 확인하는 단계가 있어서 "잘못 눌러 작업이 날아가는" 경로가 아니다.
private struct SelectionBar: View {
    @ObservedObject var client: BoardClient
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("\(client.selectedPids.count)개 선택")
                    .font(.system(size: 11, weight: .semibold))
                Spacer()
                Button("해제") { client.clearSelection() }
                    .buttonStyle(.link).font(.system(size: 11))
            }

            if let command = client.killCommand {
                // 복사하기 전에 무엇이 복사되는지 보여준다 —
                // 보이지 않는 것을 클립보드에 넣지 않는다.
                Text(command)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .textSelection(.enabled)

                Button(copied ? "✓ 복사됨 — 터미널에 붙여넣으세요" : "kill 명령 복사") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(command, forType: .string)
                    copied = true
                }
                .font(.system(size: 11))
            }

            // 선택해둔 사이에 끝난 세션이 있으면 조용히 빼지 않고 말한다.
            if client.staleSelectionCount > 0 {
                Text("\(client.staleSelectionCount)개는 이미 끝나서 제외했습니다")
                    .font(.system(size: 10))
                    .foregroundStyle(.orange)
            }
        }
        .padding(.horizontal, 12)
        // 선택이 바뀌면 "복사됨" 표시를 되돌린다 — 예전 복사 결과를 새 선택에
        // 붙여 보여주면 안 한 복사를 했다고 믿게 된다.
        .onChange(of: client.selected) { _ in copied = false }
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

private struct StateGroup: View {
    let state: SessionState
    let projects: [Project]
    @ObservedObject var client: BoardClient

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(state.symbol) \(state.label) (\(projects.count))")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(color)
            ForEach(projects) { project in
                Row(project: project, client: client)
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
    @ObservedObject var client: BoardClient

    private var session: Session { project.current }
    private var isExpanded: Bool { client.expanded.contains(session.sessionId) }
    private var isSelected: Bool { client.selected.contains(session.sessionId) }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            header
            if isExpanded { detail }
        }
    }

    private var header: some View {
        HStack(spacing: 6) {
            // 체크박스는 **행 펼침과 따로 논다** — 선택하려다 펼쳐지면 성가시다.
            Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                .font(.system(size: 11))
                .foregroundStyle(isSelected ? Color.accentColor : Color.secondary.opacity(0.5))
                .onTapGesture { client.toggleSelected(session.sessionId) }

            Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(.secondary)

            Text(project.name).font(.system(size: 12))

            // 한 프로젝트에 세션이 여럿이면 그 사실을 알린다 —
            // 안 그러면 current 하나만 보고 "이게 전부"로 읽는다.
            if project.sessionCount > 1 {
                Text("+\(project.sessionCount - 1)")
                    .font(.system(size: 9))
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            if let text = contextText {
                Text(text)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(contextColor)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { client.toggleExpanded(session.sessionId) }
    }

    private var detail: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(session.details, id: \.0) { label, value in
                HStack(alignment: .top, spacing: 6) {
                    Text(label)
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                        .frame(width: 62, alignment: .leading)
                    Text(value)
                        .font(.system(size: 10, design: .monospaced))
                        .textSelection(.enabled)
                }
            }

            if let title = session.title {
                Text(title)
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .padding(.top, 2)
            }

            // 경로는 마지막에. 길어서 위에 두면 나머지를 밀어낸다.
            Text(project.cwd)
                .font(.system(size: 9, design: .monospaced))
                .foregroundStyle(.secondary.opacity(0.7))
                .lineLimit(1)
                .truncationMode(.head)
                .textSelection(.enabled)
        }
        .padding(.leading, 18)
        .padding(.bottom, 4)
    }

    /// 컨텍스트 경고 (#23). 70% 준비 / 85% 지금 —
    /// **평소엔 조용해야 경고가 보인다.**
    private var contextText: String? {
        guard let ratio = session.contextRatio else { return nil }
        return "\(Int(ratio * 100))%"
    }

    private var contextColor: Color {
        guard let ratio = session.contextRatio else { return .secondary }
        if ratio >= 0.85 { return Color(red: 0.83, green: 0.40, blue: 0.25) }
        if ratio >= 0.70 { return Color(red: 0.79, green: 0.64, blue: 0.15) }
        return .secondary
    }
}
