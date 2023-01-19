public class ReportType {
    public static enum ReportType {

        DEFAULT,
        COMPARISON

    }

    def static valueOf(String name) {
        switch(name) {
            case "DEFAULT":
                return ReportType.DEFAULT
            case "COMPARISON":
                return ReportType.COMPARISON
            default:
                return null
        }
    }

    def static DEFAULT = ReportType.DEFAULT
    def static COMPARISON = ReportType.COMPARISON
}
