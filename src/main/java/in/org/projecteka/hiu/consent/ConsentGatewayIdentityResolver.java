package in.org.projecteka.hiu.consent;

import in.org.projecteka.hiu.HiuProperties;
import in.org.projecteka.hiu.consent.model.ConsentRequestData;
import lombok.Value;
import org.springframework.util.StringUtils;

/**
 * Resolves HIU id/name sent to the consent manager from {@link ConsentRequestData}:
 * session-scoped HFR fields (when present) take precedence, then consent.hipId, then {@link HiuProperties}.
 */
public final class ConsentGatewayIdentityResolver {

    private ConsentGatewayIdentityResolver() {
    }

    public static ResolvedHiuIdentity resolve(ConsentRequestData data, HiuProperties hiuProperties) {
        if (StringUtils.hasText(data.getAbdmHfrId())) {
            return new ResolvedHiuIdentity(
                    data.getAbdmHfrId(),
                    StringUtils.hasText(data.getAbdmHfrName()) ? data.getAbdmHfrName() : hiuProperties.getName());
        }
        var hiuId = StringUtils.hasText(data.getAbdmHfrId()) ? data.getAbdmHfrId() : hiuProperties.getId();
        var hiuName = StringUtils.hasText(data.getAbdmHfrName()) ? data.getAbdmHfrName() : hiuProperties.getName();
        return new ResolvedHiuIdentity(hiuId, hiuName);
    }

    @Value
    public static class ResolvedHiuIdentity {
        String hiuId;
        String hiuName;
    }
}
