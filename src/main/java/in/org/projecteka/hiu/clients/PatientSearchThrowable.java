package in.org.projecteka.hiu.clients;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;

@EqualsAndHashCode(callSuper = true)
@Value
@Builder
public class PatientSearchThrowable extends Throwable {
    private enum ErrorCode {
        NOTFOUND,
        UNKNOWN
    }

    ErrorCode code;
    String message;

    static PatientSearchThrowable notFound() {
        return notFound("User does not exist");
    }

    public static PatientSearchThrowable notFound(String message) {
        return new PatientSearchThrowable(ErrorCode.NOTFOUND, message);
    }

    static PatientSearchThrowable unknown() {
        final String somethingWentWrong = "Something went wrong";
        return new PatientSearchThrowable(ErrorCode.UNKNOWN, somethingWentWrong);
    }
}
