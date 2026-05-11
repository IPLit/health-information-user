package in.org.projecteka.hiu.common;

import in.org.projecteka.hiu.Caller;
import in.org.projecteka.hiu.HiuProperties;
import lombok.Builder;
import lombok.Value;
import org.springframework.util.StringUtils;

/**
 * Effective HIU identity for the current request: session HFR metadata (when present) over
 * {@link HiuProperties} defaults. Use {@link #from(Caller, HiuProperties)} after resolving the
 * authenticated {@link Caller} (e.g. via {@link ActiveSessionContextService}).
 */
@Value
@Builder
public class ActiveSessionContext {

    String defaultHiuId;
    String defaultHiuName;
    String abdmHfrId;
    String abdmHfrName;

    public static ActiveSessionContext from(Caller caller, HiuProperties hiuProperties) {
        return ActiveSessionContext.builder()
                .defaultHiuId(hiuProperties.getId())
                .defaultHiuName(hiuProperties.getName())
                .abdmHfrId(caller.getAbdmHfrId().orElse(null))
                .abdmHfrName(caller.getAbdmHfrName().orElse(null))
                .build();
    }

    /**
     * Facility / HIU id for gateway calls: ABDM HFR ID from login session when set, else configured HIU id.
     */
    public String getEffectiveHiuId() {
        return StringUtils.hasText(abdmHfrId) ? abdmHfrId : defaultHiuId;
    }

    /**
     * Display name: ABDM HFR Name from session when set, else configured HIU name.
     */
    public String getEffectiveHiuName() {
        return StringUtils.hasText(abdmHfrName) ? abdmHfrName : defaultHiuName;
    }
}
