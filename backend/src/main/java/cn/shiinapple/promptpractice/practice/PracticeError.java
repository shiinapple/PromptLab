package cn.shiinapple.promptpractice.practice;

import java.util.Map;

public final class PracticeError extends RuntimeException {
    final int status;
    final String code;
    final Map<String, ?> details;

    public PracticeError(int status, String code) {
        this(status, code, Map.of());
    }

    public PracticeError(int status, String code, Map<String, ?> details) {
        super(code);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }
}
