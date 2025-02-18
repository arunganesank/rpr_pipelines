public class ReportType {
    public static enum ReportTypeValues {

        DEFAULT,
        COMPARISON

    }

    def static valueOf(String name) {
        switch(name) {
            case "DEFAULT":
                return ReportTypeValues.DEFAULT
            case "COMPARISON":
                return ReportTypeValues.COMPARISON
            default:
                return null
        }
    }

    def static DEFAULT = ReportTypeValues.DEFAULT
    def static COMPARISON = ReportTypeValues.COMPARISON
}
