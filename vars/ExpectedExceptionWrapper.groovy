public class ExpectedExceptionWrapper extends RuntimeException {

    Boolean retry
    Boolean abort

    public ExpectedExceptionWrapper() {
        super()
    }

    public ExpectedExceptionWrapper(String message) {
        super(message)
    }

    public ExpectedExceptionWrapper(Throwable cause) {
        super(cause)
    }

    public ExpectedExceptionWrapper(String message, Throwable cause) {
        super(message, cause)
    }

}