package in.org.projecteka.hiu.consent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import in.org.projecteka.hiu.common.ActiveSessionContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor
@Builder
@NoArgsConstructor
@Data
public class ConsentRequestData {
    private Consent consent;

    /**
     * Not accepted from client JSON; set server-side from {@link ActiveSessionContext} after login.
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String abdmHfrId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String abdmHfrName;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String visitLocationUuid;

    public void applyActiveSessionMetadata(ActiveSessionContext context) {
        if (context == null) {
            return;
        }
        if (StringUtils.hasText(context.getEffectiveHiuId())) {
            this.abdmHfrId = context.getEffectiveHiuId();
        }
        if (StringUtils.hasText(context.getEffectiveHiuName())) {
            this.abdmHfrName = context.getEffectiveHiuName();
        }
        if (StringUtils.hasText(context.getVisitLocationUuid())) {
            this.visitLocationUuid = context.getVisitLocationUuid();
        }
    }
}
