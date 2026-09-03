package dev.hyunwoo.claudeboard.web;

import dev.hyunwoo.claudeboard.domain.BoardSnapshot;
import dev.hyunwoo.claudeboard.service.BoardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/sessions}. docs/02-백엔드.md 의 API 스키마와 대응한다.
 *
 * <p><b>여기서 수집하지 않는다</b> — 캐시를 읽기만 한다.
 * 수집의 ~200ms 는 {@code claude agents --json} 서브프로세스라 요청 경로에 두면
 * 그대로 응답 지연이 된다. {@link BoardService} 참고.
 */
@RestController
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping("/api/sessions")
    public BoardSnapshot sessions() {
        return boardService.snapshot();
    }
}
