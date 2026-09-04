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
    let contextTokens: Int?
    let contextLimit: Int?
    let contextRatio: Double?
    let lastActivityAt: String?
    let ordinal: Int

    var id: String { sessionId }
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
