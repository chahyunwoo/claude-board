import Foundation

/// 백엔드 `domain/` 레코드와 1:1. **null 을 성실하게 표기한다** —
/// 역순 리더가 상한(512KB)에 걸리면 title·lastPrompt·branch 가 실제로 비어서 온다
/// (docs/03-프론트.md). 낙관적으로 non-optional 로 적으면 디코딩이 통째로 실패한다.
struct Session: Decodable, Identifiable {
    let sessionId: String
    let pid: Int
    let state: SessionState
    let title: String?
    let lastPrompt: String?
    let branch: String?
    let permissionMode: String?
    let model: String?
    let contextTokens: Int?
    let contextLimit: Int?
    let contextRatio: Double?
    let lastActivityAt: String?
    let startedAt: String?
    let ordinal: Int

    var id: String { sessionId }

    /// 상세에 보여줄 항목들. **값이 없으면 그 줄을 아예 안 낸다** —
    /// "모름"을 나열하는 것보다 조용한 편이 낫다.
    var details: [(String, String)] {
        var rows: [(String, String)] = [("PID", String(pid))]
        if let model { rows.append(("모델", model)) }
        if let permissionMode { rows.append(("권한", permissionMode)) }
        if let branch { rows.append(("브랜치", branch)) }
        if let text = Self.elapsed(since: startedAt) { rows.append(("시작", text)) }
        if let text = Self.elapsed(since: lastActivityAt) { rows.append(("마지막 활동", text)) }
        if let contextTokens, let contextLimit {
            rows.append(("컨텍스트", "\(contextTokens.formatted()) / \(contextLimit.formatted())"))
        }
        return rows
    }

    /// ISO8601 → "3시간 전". **절대 시각보다 경과가 낫다** —
    /// 판단하려는 건 "언제였나"가 아니라 "얼마나 방치됐나"다.
    ///
    /// ⚠️ 백엔드는 밀리초를 붙여 보낸다(`.230Z`). 기본 포맷터는 그걸 못 읽으니
    /// `withFractionalSeconds` 를 켜고, 그래도 실패하면 기본형으로 한 번 더 본다.
    static func elapsed(since iso: String?) -> String? {
        guard let iso else { return nil }
        let withMs = ISO8601DateFormatter()
        withMs.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = withMs.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
        guard let date else { return nil }

        let seconds = Int(Date().timeIntervalSince(date))
        if seconds < 60 { return "방금" }
        if seconds < 3600 { return "\(seconds / 60)분 전" }
        if seconds < 86400 { return "\(seconds / 3600)시간 전" }
        return "\(seconds / 86400)일 전"
    }
}

/// 상태 4종. `ENDED` 는 없다 (#17) — 백엔드가 pid 없는 항목을 걸러낸다.
///
/// **여기서 상태를 다시 판별하지 않는다.** 백엔드가 준 값을 그대로 쓴다 —
/// 판정을 두 곳에 두면 갈라지고, 그러면 도구 전체가 거짓말을 한다 (CLAUDE.md).
enum SessionState: String, Decodable, Equatable, CaseIterable {
    case waiting = "WAITING"
    case stalled = "STALLED"
    case working = "WORKING"
    case idle = "IDLE"

    /// docs/03-프론트.md "정렬" — 답변 대기가 최상단, 가장 높은 가치다.
    var order: Int {
        switch self {
        case .waiting: return 1
        case .stalled: return 2
        case .working: return 3
        case .idle: return 4
        }
    }

    var label: String {
        switch self {
        case .waiting: return "답변 대기"
        case .stalled: return "멈춤 의심"
        case .working: return "작업 중"
        case .idle: return "유휴"
        }
    }

    /// 색이 아니라 **위치와 라벨로** 먼저 구분한다. 기호는 보조다 (docs/03-프론트.md).
    var symbol: String {
        switch self {
        case .waiting: return "⏳"
        case .stalled: return "⚠"
        case .working: return "▶"
        case .idle: return "·"
        }
    }
}

struct Project: Decodable, Identifiable {
    let cwd: String
    let name: String
    let current: Session
    let others: [Session]
    let sessionCount: Int

    var id: String { cwd }
}

struct BoardSnapshot: Decodable {
    let generatedAt: String
    let elapsedMs: Int
    let projects: [Project]
    /// **반드시 노출한다** — 파싱이 조용히 실패하면 "세션이 없다"와 "읽지 못했다"가
    /// 구별되지 않는다 (docs/02-백엔드.md).
    let errors: [String]
}
