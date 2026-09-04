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
    ///
    /// ⚠️ **이모지를 쓰지 않는다.** `⏳⚠▶·` 를 쓰다가 걷어냈다 —
    /// 컬러 이모지는 폰트마다 렌더링이 다르고 주변 UI 톤과 섞이지 않아 튄다.
    /// SF Symbol 은 폰트 굵기·크기를 따라가고 다크모드에서도 알아서 맞춰진다.
    ///
    /// **모양은 통일하고 색으로만 가른다** — 아이콘이 제각각이면 목록이 분주해지고,
    /// 상태 구분은 어차피 위치와 라벨이 먼저 한다.
    var symbol: String {
        switch self {
        case .waiting, .stalled, .working: return "circle.fill"
        case .idle: return "circle"
        }
    }
}

/// 상태 색. **한 곳에서만 정한다** — 색이 여러 곳에 흩어지면 갈라진다.
///
/// docs/03-프론트.md "시각 규칙" 의 앰버·레드·그린·회색을 따르되,
/// **채도를 낮춰서 쓴다.** 그 문서가 *"색이 아니라 위치와 라벨로 먼저 구분한다,
/// 색은 보조"* 라고 정했는데, 웹에서 쓰던 채도 높은 값을 그대로 가져왔더니
/// 색이 먼저 튀어서 그 원칙을 오히려 어기고 있었다.
///
/// SwiftUI 를 import 하지 않으려고 RGB 만 낸다 — `collect/` 처럼
/// 모델 계층은 프레임워크에 기대지 않는 편이 옮기기 쉽다.
extension SessionState {
    var rgb: (Double, Double, Double) {
        switch self {
        // 앰버 — 시선을 끌어야 하지만 형광은 아니다
        case .waiting: return (0.78, 0.60, 0.30)
        // 레드 — 벽돌색 쪽으로. 경고지만 비명은 아니다
        case .stalled: return (0.72, 0.40, 0.36)
        // 그린 — 세이지. 평상시 상태라 가장 조용해야 한다
        case .working: return (0.44, 0.60, 0.48)
        // 유휴는 시스템 secondary 를 쓴다 (여기 값은 안 쓰인다)
        case .idle:    return (0.55, 0.55, 0.55)
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

extension Project {
    /// 이 프로젝트의 모든 살아있는 세션. **`current` 만 보면 안 된다** —
    /// 실측(#37): `others` 에 있던 61.6% 세션이 화면에 아예 안 나왔고,
    /// 정작 `current` 는 54.4% 였다. **가장 위험한 것이 숨는 구조**였다.
    var allSessions: [Session] { [current] + others }
}

struct BoardSnapshot: Decodable {
    let generatedAt: String
    let elapsedMs: Int
    let projects: [Project]
    /// **반드시 노출한다** — 파싱이 조용히 실패하면 "세션이 없다"와 "읽지 못했다"가
    /// 구별되지 않는다 (docs/02-백엔드.md).
    let errors: [String]
}
