package in.org.projecteka.hiu.common;

import in.org.projecteka.hiu.Caller;
import in.org.projecteka.hiu.HiuProperties;
import lombok.AllArgsConstructor;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves {@link ActiveSessionContext} for the current reactive security principal.
 * Use in any authenticated controller: {@code activeSessionContextService.current().flatMap(ctx -> ...)}
 * and read {@link ActiveSessionContext#getEffectiveHiuId()} / {@link ActiveSessionContext#getEffectiveHiuName()}
 * (defaults come from {@code hiu.id} / {@code hiu.name} when the JWT has no HFR claims).
 */
@Component
@AllArgsConstructor
public class ActiveSessionContextService {

    private final HiuProperties hiuProperties;

    public ActiveSessionContext fromCaller(Caller caller) {
        return ActiveSessionContext.from(caller, hiuProperties);
    }

    public Mono<ActiveSessionContext> current() {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(this::fromCaller);
    }
}
