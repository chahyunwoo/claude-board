package dev.hyunwoo.claudeboard.collect;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 파일을 끝에서부터 청크 단위로 읽어 줄을 역순으로 흘려준다.
 *
 * <p>세션 기록이 최대 수 MB 다. 전체를 파싱하면 갱신마다 수백 ms 가 날아가므로
 * <b>끝에서 필요한 만큼만</b> 읽는다. docs/00-개요.md "읽기는 파일 끝에서부터".
 *
 * <p>{@link #readBytes()} 로 지금까지 읽은 바이트 수를 알 수 있다 — 호출자가 상한을 강제하는 근거다.
 */
final class ReverseLineReader implements Closeable {

    private static final int CHUNK = 64 * 1024;

    private final RandomAccessFile file;
    private final Deque<String> buffered = new ArrayDeque<>();

    /** 아직 읽지 않은 영역의 끝. 0 이 되면 파일을 다 읽은 것이다. */
    private long pos;

    /**
     * 청크 경계에서 잘린 줄의 앞부분. 다음(더 앞쪽) 청크의 마지막 줄과 이어붙인다.
     * 이걸 빼먹으면 경계에 걸친 레코드가 깨진 JSON 으로 보인다.
     */
    private String carry = "";

    private long readBytes;

    ReverseLineReader(Path path) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "r");
        this.pos = file.length();
    }

    long readBytes() {
        return readBytes;
    }

    /** 다음 줄(뒤에서부터). 더 없으면 null. 빈 줄은 건너뛴다. */
    String nextLine() throws IOException {
        while (true) {
            while (buffered.isEmpty()) {
                if (pos == 0) {
                    return null;
                }
                fillFromPreviousChunk();
            }
            String line = buffered.pollFirst();
            if (!line.isBlank()) {
                return line;
            }
        }
    }

    /**
     * 앞쪽으로 한 청크 더 읽어 {@link #buffered} 를 채운다.
     *
     * <p>청크의 첫 조각은 줄 중간에서 잘렸을 수 있으므로 버퍼에 넣지 않고 {@link #carry} 로 넘긴다.
     * 단 {@code pos == 0} 이면 진짜 파일 시작이므로 잘린 것이 아니다 — 그 처리는 호출부가 한다.
     */
    private void fillFromPreviousChunk() throws IOException {
        int size = (int) Math.min(CHUNK, pos);
        pos -= size;

        byte[] buf = new byte[size];
        file.seek(pos);
        file.readFully(buf);
        readBytes += size;

        String chunk = new String(buf, StandardCharsets.UTF_8) + carry;
        String[] parts = chunk.split("\n", -1);

        // parts[0] 은 앞쪽이 잘렸을 수 있으므로 보류한다.
        // 단 pos == 0 이면 진짜 파일 시작이라 잘린 게 아니므로 그대로 내보낸다.
        int firstEmitted;
        if (pos == 0) {
            carry = "";
            firstEmitted = 0;
        } else {
            carry = parts[0];
            firstEmitted = 1;
        }

        // 뒤쪽 줄이 먼저 나가야 하므로 역순으로 넣는다.
        for (int i = parts.length - 1; i >= firstEmitted; i--) {
            buffered.addLast(stripCr(parts[i]));
        }
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
