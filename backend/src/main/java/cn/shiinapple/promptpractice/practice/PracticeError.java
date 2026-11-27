package cn.shiinapple.promptpractice.practice;

final class PracticeError extends RuntimeException {
    final int status;
    final String code;

    PracticeError(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
