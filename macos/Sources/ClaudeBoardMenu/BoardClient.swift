import Foundation

/// 앱이 보는 상태. 메뉴바 표시가 여기서 갈린다.
enum Health: Equatable {
    /// 정상. 스냅샷을 받고 있다.
    case ok
    /// 연결이 끊겼다. 재연결 중.
    case disconnected
    /// 백엔드가 아예 안 떠 있다.
    case backendDown
    /// 백엔드는 떴는데 수집이 실패했다 (errors 가 비어있지 않다).
    case collectFailed(String)

    var isHealthy: Bool { self == .ok }
}

/// 백엔드(`127.0.0.1:7777`)를 구독하고, **죽으면 되살린다.**
///
/// ## 왜 앱이 워치독을 겸하는가
///
/// 이 앱은 **어차피 항상 떠 있으므로** 백엔드를 감시할 수 있다.
///
/// ⚠️ 정정(2026-09-04): 여기 원래 "이 기계의 launchd 는 KeepAlive 가 동작하지 않는다"고
/// 적혀 있었는데 **틀렸다** — 같은 기계에서 caddy·concert-watch 가 KeepAlive:true 로 잘 돈다.
/// 관측된 사실은 그 잡의 종료 코드가 143(SIGTERM)이었다는 것뿐이고, launchd 는 그것을
/// 정상 종료로 보아 되살리지 않는다. 앱이 워치독을 겸하는 구조는 그대로 두되,
/// 근거를 "launchd 고장"으로 삼지 않는다 (docs/04-배포.md).
///
/// **데이터는 이 기계를 떠나지 않는다** (CLAUDE.md 절대 원칙).
/// 이 클래스는 `127.0.0.1` 외에 아무 데도 연결하지 않는다.
@MainActor
final class BoardClient: ObservableObject {
    @Published private(set) var snapshot: BoardSnapshot?
    @Published private(set) var health: Health = .disconnected
    @Published private(set) var lastError: String?

    /// 답변 대기 수 — 메뉴바에 항상 보이는 유일한 값이다 (docs/00-개요.md 목표 2).
    var waitingCount: Int { count(of: .waiting) }

    func count(of state: SessionState) -> Int {
        guard let snapshot else { return 0 }
        return snapshot.projects.reduce(0) { sum, p in
            sum + ([p.current] + p.others).filter { $0.state == state }.count
        }
    }

    /// 화면의 한 줄. **세션 하나 = 한 줄이다.**
    ///
    /// ⚠️ 처음엔 프로젝트 하나에 한 줄을 냈다. 그러면 같은 폴더에서 세션을 여러 개
    /// 띄웠을 때 `current` 만 보이고 나머지가 **화면에서 사라진다** —
    /// 실측(#37): current 54.4%, others 61.6% 인데 화면에는 54.4% 만 떴다.
    /// 최댓값을 대신 내는 것으로 때워봤지만, "두 개가 떠 있으면 두 개 다 보여야 한다"가
    /// 맞다. 실측으로 다 펴도 11줄 → 12줄이라 길어지지도 않는다.
    struct Row: Identifiable {
        let project: Project
        let session: Session
        /// 이 프로젝트에 살아있는 세션이 여럿인가. 여럿이면 화면에 번호를 낸다.
        let siblingCount: Int
        var id: String { session.sessionId }
    }

    struct Group: Identifiable {
        let state: SessionState
        let rows: [Row]
        var id: String { state.rawValue }
    }

    /// 상태 그룹. 답변 대기 → 멈춤 의심 → 작업 중 → 유휴 (docs/03-프론트.md "정렬").
    ///
    /// **세션마다 자기 상태로 분류된다.** 프로젝트 단위로 묶으면 `others` 의 세션이
    /// `current` 의 상태에 끌려가 엉뚱한 그룹에 들어간다.
    var groups: [Group] {
        guard let snapshot else { return [] }
        let all: [Row] = snapshot.projects.flatMap { project in
            let sessions = project.allSessions
            return sessions.map {
                Row(project: project, session: $0, siblingCount: sessions.count)
            }
        }
        // order 순으로 돈다 — CaseIterable 이라 상태가 늘어도 여기를 안 고쳐도 된다.
        let ordered = SessionState.allCases.sorted { $0.order < $1.order }
        return ordered.compactMap { (state: SessionState) -> Group? in
            let rows = all.filter { $0.session.state == state }
            guard !rows.isEmpty else { return nil }
            return Group(state: state, rows: Self.sort(rows, for: state))
        }
    }

    /// 그룹 안 정렬. **그룹마다 다르다** (#18).
    ///
    /// 작업 중·유휴는 이름순이어야 한다 — `lastActivityAt` 이 5초마다 갱신되므로
    /// 그것으로 정렬하면 내용이 안 바뀌었는데 순서가 계속 뒤집힌다.
    /// "방치된 것이 위로"의 가치는 답변 대기·멈춤 의심에서만 나온다.
    ///
    /// 같은 프로젝트의 세션끼리는 `ordinal` 로 갈라 순서가 흔들리지 않게 한다.
    private static func sort(_ rows: [Row], for state: SessionState) -> [Row] {
        if state == .working || state == .idle {
            return rows.sorted {
                ($0.project.name, $0.session.ordinal) < ($1.project.name, $1.session.ordinal)
            }
        }
        return rows.sorted {
            ($0.session.lastActivityAt ?? "", $0.session.ordinal)
                < ($1.session.lastActivityAt ?? "", $1.session.ordinal)
        }
    }

    // MARK: - 선택과 kill 명령
    //
    // **앱은 세션을 죽이지 않는다.** 명령어를 클립보드에 넣어줄 뿐이고,
    // 실행은 터미널에서 사용자가 한다 — 붙여넣기 전에 눈으로 확인하는 단계가 있어
    // 조회 전용 원칙(docs/00-개요.md)의 "잘못 눌러 작업이 날아가는" 경로가 아니다.

    /// 펼쳐진 행. sessionId 기준.
    @Published var expanded: Set<String> = []

    /// 선택된 세션. **pid 가 아니라 sessionId 로 잡는다** —
    /// pid 는 프로세스가 죽으면 OS 가 재사용하므로, 선택해둔 사이에 값이 바뀌면
    /// 엉뚱한 프로세스를 죽이는 명령이 만들어진다. pid 는 명령을 만드는 순간
    /// 최신 스냅샷에서 다시 읽는다.
    @Published var selected: Set<String> = []

    func toggleExpanded(_ sessionId: String) {
        if expanded.contains(sessionId) { expanded.remove(sessionId) } else { expanded.insert(sessionId) }
    }

    func toggleSelected(_ sessionId: String) {
        if selected.contains(sessionId) { selected.remove(sessionId) } else { selected.insert(sessionId) }
    }

    func clearSelection() { selected.removeAll() }

    /// 스냅샷에 살아있는 모든 세션. 프로젝트의 current + others 를 편다.
    private var allSessions: [Session] {
        guard let snapshot else { return [] }
        return snapshot.projects.flatMap { [$0.current] + $0.others }
    }

    /// 선택된 것 중 **지금 스냅샷에 실제로 살아있는** 세션의 pid.
    ///
    /// 선택한 뒤 세션이 끝났으면 그 pid 는 빠진다 — 죽은 세션의 pid 를 명령에 넣으면
    /// 재사용된 남의 프로세스를 죽일 수 있다.
    var selectedPids: [Int] {
        allSessions.filter { selected.contains($0.sessionId) }
            .map(\.pid)
            .sorted()
    }

    /// 선택이 스냅샷에서 사라진 개수. 화면에 알려서 조용히 빠지지 않게 한다.
    var staleSelectionCount: Int {
        let alive = Set(allSessions.map(\.sessionId))
        return selected.subtracting(alive).count
    }

    /// 클립보드에 넣을 kill 명령.
    ///
    /// `-9` 를 쓰지 않는다 — SIGTERM 이면 Claude Code 가 기록을 정리하고 끝낼 수 있다.
    /// SIGKILL 은 그 기회를 뺏는다.
    var killCommand: String? {
        let pids = selectedPids
        guard !pids.isEmpty else { return nil }
        return "kill " + pids.map(String.init).joined(separator: " ")
    }

    private let streamURL = URL(string: "http://127.0.0.1:7777/api/stream")!
    private var task: Task<Void, Never>?

    /// 백엔드를 되살린 횟수. 무한 재시작을 막는다.
    private var restartAttempts = 0
    private var lastRestartAt: Date?
    private static let maxRestarts = 5
    /// 이 시간이 지나면 시도 횟수를 리셋한다 — 며칠 뒤의 한 번은 "반복 실패"가 아니다.
    private static let restartWindow: TimeInterval = 600

    func start() {
        task?.cancel()
        task = Task { await loop() }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    /// 끊기면 다시 붙는다.
    ///
    /// 서버는 30분마다 연결을 만료시킨다(`StreamController.TIMEOUT_MS`) —
    /// **정상 동작이므로** 그때도 조용히 재연결해야 한다 (#15 에서 실측).
    private func loop() async {
        while !Task.isCancelled {
            do {
                try await consume()
                // 정상 종료 = 서버가 만료시킨 것. 바로 다시 붙는다.
                health = .disconnected
            } catch is CancellationError {
                return
            } catch {
                health = await backendAlive() ? .disconnected : .backendDown
                lastError = error.localizedDescription
                if health == .backendDown {
                    await restartBackendIfNeeded()
                }
            }
            guard !Task.isCancelled else { return }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        }
    }

    /// SSE 를 읽는다. `event: snapshot` 만 받는다 —
    /// 15초마다 오는 주석 프레임(`:`)은 끊김 감지용이고 이벤트가 아니다.
    private func consume() async throws {
        var request = URLRequest(url: streamURL)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        // 기본 타임아웃(60초)이면 조용한 동안 끊긴다. 서버 만료(30분)보다 길게 둔다.
        request.timeoutInterval = 3600

        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }

        var isSnapshot = false
        for try await line in bytes.lines {
            if Task.isCancelled { throw CancellationError() }
            if line.hasPrefix("event:") {
                isSnapshot = line.dropFirst(6).trimmingCharacters(in: .whitespaces) == "snapshot"
            } else if line.hasPrefix("data:"), isSnapshot {
                apply(String(line.dropFirst(5)))
            }
        }
    }

    private func apply(_ json: String) {
        guard let data = json.data(using: .utf8) else { return }
        do {
            let decoded = try JSONDecoder().decode(BoardSnapshot.self, from: data)
            snapshot = decoded
            restartAttempts = 0          // 정상 수신 = 회복. 시도 횟수를 되돌린다.
            // errors 를 무시하지 않는다 — "세션이 없다"와 "읽지 못했다"는 다르다.
            health = decoded.errors.isEmpty ? .ok : .collectFailed(decoded.errors[0])
            lastError = decoded.errors.first
        } catch {
            health = .collectFailed("스냅샷을 읽지 못했습니다")
            lastError = error.localizedDescription
        }
    }

    /// 포트로 센다. 프로세스 이름은 `java` 라 이름으로는 못 가른다 (docs/05-검증.md 4번).
    private func backendAlive() async -> Bool {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:7777/api/sessions")!)
        request.timeoutInterval = 3
        guard let (_, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse else { return false }
        return http.statusCode == 200
    }

    // MARK: - 수동 제어
    //
    // 메뉴에서 직접 켜고 끈다. **이것은 세션 조작이 아니다** —
    // 이 도구 자신(백엔드 프로세스)을 켜고 끄는 것이라
    // 조회 전용 원칙(docs/00-개요.md)과 충돌하지 않는다.
    // 세션 종료·재개는 여전히 넣지 않는다.

    /// 사용자가 직접 멈춘 상태. 이때는 워치독이 되살리지 않는다 —
    /// 껐는데 자꾸 되살아나면 끌 수가 없다.
    @Published private(set) var pausedByUser = false

    func startBackend() {
        pausedByUser = false
        restartAttempts = 0
        run(["start"])
        start()
    }

    func stopBackend() {
        pausedByUser = true
        stop()
        run(["stop"])
        snapshot = nil
        health = .backendDown
        lastError = nil
    }

    private func run(_ arguments: [String]) {
        guard let script = scriptURL else {
            lastError = "실행 스크립트를 찾지 못했습니다"
            return
        }
        let process = Process()
        process.executableURL = script
        process.arguments = arguments
        do { try process.run() } catch { lastError = error.localizedDescription }
    }

    private var scriptURL: URL? {
        let url = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Documents/projects/hyunwoo/claude-board/scripts/claude-board")
        return FileManager.default.isExecutableFile(atPath: url.path) ? url : nil
    }

    /// 백엔드가 죽었으면 되살린다.
    ///
    /// ⚠️ **무한히 되살리지 않는다.** 계속 죽는다면 원인이 따로 있고,
    /// 그때 반복 재시작은 로그만 채우고 문제를 가린다.
    private func restartBackendIfNeeded() async {
        // 사용자가 직접 멈췄으면 건드리지 않는다. 껐는데 되살아나면 끌 수가 없다.
        guard !pausedByUser else { return }
        if let last = lastRestartAt, Date().timeIntervalSince(last) > Self.restartWindow {
            restartAttempts = 0
        }
        guard restartAttempts < Self.maxRestarts else {
            lastError = "백엔드를 \(Self.maxRestarts)번 되살렸지만 계속 죽습니다 — 로그를 보세요"
            return
        }
        restartAttempts += 1
        lastRestartAt = Date()

        run(["start"])
    }
}
