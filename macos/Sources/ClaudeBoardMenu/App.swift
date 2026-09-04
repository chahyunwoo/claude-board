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

    /// ⚠️ **이모지를 쓰지 않는다.** `⏳` 를 쓰다가 걷어냈다 — 컬러 이모지는
    /// 메뉴바의 다른 아이템(전부 단색 심볼)과 섞이지 않고, 다크모드에서 따로 논다.
    /// SF Symbol 은 메뉴바 텍스트 색을 그대로 따라간다.
    var body: some View {
        switch client.health {
        case .ok, .collectFailed:
            // 0 이면 숫자를 안 낸다 — 기다리는 게 없으면 조용해야 한다.
            let n = client.waitingCount
            if n > 0 {
                HStack(spacing: 3) {
                    Image(systemName: "circle.fill").font(.system(size: 7))
                    Text("\(n)")
                }
            } else {
                Image(systemName: "circle")
            }
        case .disconnected:
            // 연결 중 — 속이 빈 점선. "아직 모른다"를 뜻한다.
            Image(systemName: "circle.dotted")
        case .backendDown:
            Image(systemName: "circle.slash")
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
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12).padding(.vertical, 8)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(client.groups) { group in
                            StateGroup(state: group.state, rows: group.rows, client: client)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                // ⚠️ **상한만 주면 눌린다.** VStack 안의 ScrollView 는 남는 공간을
                // 못 받으면 최소 높이로 쪼그라들어, 목록이 있는데도 한두 줄만 보인다
                // (#37 에서 실측 — 12개가 있는데 화면에 거의 안 나왔다).
                // 하한을 함께 줘서 **최소 10행**은 스크롤 없이 보이게 한다.
                //
                // 10행 = (행 20 + spacing 6) × 10 + 그룹 헤더·간격 여유 ≈ 340
                .frame(minHeight: min(contentHeight, 340), maxHeight: 620)
            }

            if !client.selected.isEmpty {
                Divider().padding(.vertical, 6)
                SelectionBar(client: client)
            }

            Divider().padding(.vertical, 6)
            footer
        }
        .frame(width: 420)
        .padding(.vertical, 8)
        // start() 는 앱 init 에서 이미 불렀다 — 여기서 또 부르면 구독이 두 개가 된다.
    }

    /// 목록이 실제로 차지하는 높이 추정.
    ///
    /// 항목이 적으면 하한을 그만큼만 준다 — 세션 2개인데 창이 260 이면
    /// 아래가 휑하게 빈다. 상수는 폰트 크기(행 12pt·헤더 11pt)에서 나온 값이다.
    private var contentHeight: CGFloat {
        let rows = client.groups.reduce(0) { $0 + $1.rows.count }
        return CGFloat(rows) * 26 + CGFloat(client.groups.count) * 34
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
            Text("Claude Sessions")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.secondary)
            Spacer()
            // 상태 점은 문제가 있을 때만 낸다 — 평소에 초록 점이 늘 떠 있으면
            // 그게 배경이 되어 정작 주황으로 바뀌어도 눈에 안 들어온다.
            if !client.health.isHealthy {
                Circle()
                    .fill(Color(red: 0.78, green: 0.60, blue: 0.30))
                    .frame(width: 7, height: 7)
            }
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
                // 종료는 오른쪽 끝, 색을 빼서 실수로 누르기 어렵게 둔다.
                Button("종료") { NSApplication.shared.terminate(nil) }
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.link)
            .font(.system(size: 13))
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
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("\(client.selectedPids.count)개 선택")
                    .font(.system(size: 13, weight: .semibold))
                Spacer()
                Button("해제") { client.clearSelection() }
                    .buttonStyle(.link).font(.system(size: 13))
            }

            if let command = client.killCommand {
                // 복사하기 전에 무엇이 복사되는지 보여준다 —
                // 보이지 않는 것을 클립보드에 넣지 않는다.
                Text(command)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .textSelection(.enabled)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .fill(Color.primary.opacity(0.05))
                    )

                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(command, forType: .string)
                    copied = true
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: copied ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 11))
                        Text(copied ? "복사됨 — 터미널에 붙여넣으세요" : "kill 명령 복사")
                    }
                }
                .font(.system(size: 13))
            }

            // 선택해둔 사이에 끝난 세션이 있으면 조용히 빼지 않고 말한다.
            if client.staleSelectionCount > 0 {
                Text("\(client.staleSelectionCount)개는 이미 끝나서 제외했습니다")
                    .font(.system(size: 12))
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
            .font(.system(size: 12))
            .foregroundStyle(.orange)
            .padding(.horizontal, 12).padding(.vertical, 6)
    }
}

private struct StateGroup: View {
    let state: SessionState
    let rows: [BoardClient.Row]
    @ObservedObject var client: BoardClient

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // 아이콘에만 색을 남기고 **라벨은 기본색**으로 둔다 —
            // 라벨 전체가 색이면 알록달록해지고, 정작 색이 신호 구실을 못 한다.
            HStack(spacing: 6) {
                Image(systemName: state.symbol)
                    .font(.system(size: 8))
                    .foregroundStyle(color)
                Text(state.label)
                    .font(.system(size: 12, weight: .semibold))
                Text("\(rows.count)")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            ForEach(rows) { row in
                SessionRow(row: row, client: client)
            }
        }
    }

    /// 색은 `SessionState.rgb` 한 곳에서만 정한다 (Models.swift).
    private var color: Color {
        if state == .idle { return .secondary }
        let (r, g, b) = state.rgb
        return Color(red: r, green: g, blue: b)
    }
}

/// 컨텍스트 비율 표시. **판정을 한 곳에 둔다** —
/// 여러 곳에서 각자 조건을 풀어 쓰면 임계값이 조용히 갈라진다.
private struct ContextBadge: View {
    let ratio: Double?

    var body: some View {
        if let ratio {
            Text("\(Int(ratio * 100))%")
                .font(.system(size: 12, weight: isCritical ? .bold : .regular,
                              design: .monospaced))
                .foregroundStyle(color)
                .opacity(isQuiet ? 0.55 : 1)
                // 위험할 때만 칩을 두른다. 평소엔 배경이 없어야 이것이 눈에 띈다.
                .padding(.horizontal, isCritical ? 5 : 0)
                .padding(.vertical, isCritical ? 1 : 0)
                .background {
                    if isCritical {
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .fill(color.opacity(0.15))
                    }
                }
        }
    }

    /// 컨텍스트 경고 (#23). **평소에 조용해야 경고가 보인다** —
    /// 70% 미만은 흐린 회색으로 물러나고, 85% 부터는 확실히 붉게 낸다.
    ///
    /// ⚠️ 다른 색은 채도를 낮췄지만 **85% 만은 낮추지 않는다.**
    /// 한 번 낮췄다가 되돌렸다 — 옆의 앰버(70%)와 구별이 안 됐고,
    /// 그러면 "지금 새 세션을 열어야 한다"는 신호가 전달되지 않는다.
    private var color: Color {
        guard let ratio else { return .secondary }
        if ratio >= 0.85 { return Color(red: 0.85, green: 0.29, blue: 0.26) }
        if ratio >= 0.70 { return Color(red: 0.78, green: 0.60, blue: 0.30) }
        return .secondary
    }

    private var isQuiet: Bool { (ratio ?? 0) < 0.70 }
    /// 85% 이상은 **굵게** 낸다 — 색약이거나 화면이 어두워도 무게로 갈린다.
    private var isCritical: Bool { (ratio ?? 0) >= 0.85 }
}

/// 세션 한 개 = 한 줄. **같은 프로젝트의 세션도 각자 줄을 가진다** —
/// 프로젝트당 한 줄이면 나머지가 화면에서 사라진다 (BoardClient.Row 주석 참고).
private struct SessionRow: View {
    let row: BoardClient.Row
    @ObservedObject var client: BoardClient

    private var project: Project { row.project }
    private var session: Session { row.session }

    private var isExpanded: Bool { client.expanded.contains(session.sessionId) }
    private var isSelected: Bool { client.selected.contains(session.sessionId) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            header
            if isExpanded { detail }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            // 체크박스는 **행 펼침과 따로 논다** — 선택하려다 펼쳐지면 성가시다.
            //
            // ⚠️ 아이콘 크기와 **클릭 영역은 다르다.** 16pt 아이콘을 그대로 두면
            // 손이 정확해야 눌린다. frame + contentShape 로 28pt 영역을 만들어
            // 아이콘 주변 여백을 눌러도 잡히게 한다 (macOS 권장 타겟은 28pt 이상).
            Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                .font(.system(size: 16))
                .foregroundStyle(isSelected ? Color.accentColor : Color.secondary.opacity(0.4))
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
                .onTapGesture { client.toggleSelected(session.sessionId) }

            Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 12)

            Text(project.name).font(.system(size: 13))

            // 한 프로젝트에 세션이 여럿이면 그 사실을 알린다 —
            // 안 그러면 current 하나만 보고 "이게 전부"로 읽는다.
            // 같은 폴더에 세션이 여럿이면 몇 번째인지 알린다 —
            // 이름이 같은 줄이 둘 이상 뜨므로 구분할 표시가 있어야 한다.
            if row.siblingCount > 1 {
                Text("#\(session.ordinal)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 8)

            // **이 세션 자기 값이다.** 프로젝트 최댓값을 대신 내지 않는다 —
            // 세션마다 자기 줄을 가지므로 각자 자기 값을 내면 된다.
            ContextBadge(ratio: session.contextRatio)
        }
        // 행 전체가 펼침 타겟이다. 체크박스만 자기 몫을 먼저 가져간다.
        .padding(.vertical, 1)
        .contentShape(Rectangle())
        .onTapGesture { client.toggleExpanded(session.sessionId) }
    }

    private var detail: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(session.details, id: \.0) { label, value in
                HStack(alignment: .top, spacing: 8) {
                    Text(label)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .frame(width: 76, alignment: .leading)
                    Text(value)
                        .font(.system(size: 12, design: .monospaced))
                        .textSelection(.enabled)
                }
            }

            if let title = session.title {
                Text(title)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .padding(.top, 3)
            }

            // 경로는 마지막에. 길어서 위에 두면 나머지를 밀어낸다.
            Text(project.cwd)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary.opacity(0.7))
                .lineLimit(1)
                .truncationMode(.head)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        // 펼쳐진 영역을 옅은 판으로 감싼다 — 어디까지가 이 행의 상세인지
        // 경계가 보여야 여러 개를 동시에 펼쳤을 때 섞이지 않는다.
        .background(
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color.primary.opacity(0.04))
        )
        .padding(.leading, 36)
        .padding(.bottom, 2)
    }

}
