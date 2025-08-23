package in.org.projecteka.hiu.common;

import java.time.LocalDateTime;
import java.lang.IllegalArgumentException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static in.org.projecteka.hiu.common.Constants.TIMESTAMP_PATTERN;
import static java.time.LocalDateTime.now;

public class Utils {

    public static String getISOTimestamp(){
        return now().format(DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN));
    }

    public static LocalDateTime parseTimeStamp(String timestamp) {
        try {
            return LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(String.format("Invalid timestamp %s. Expected pattern is %s", timestamp, TIMESTAMP_PATTERN));
        }
    }
}
