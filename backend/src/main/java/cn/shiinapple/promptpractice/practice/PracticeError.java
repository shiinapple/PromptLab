package cn.shiinapple.promptpractice.practice;

public final class PracticeError extends RuntimeException {
    final int status;
    final String code;

    public PracticeError(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
