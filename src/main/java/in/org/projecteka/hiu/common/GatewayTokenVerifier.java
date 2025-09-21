package in.org.projecteka.hiu.common;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import in.org.projecteka.hiu.ServiceCaller;
import in.org.projecteka.hiu.user.Role;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

public class GatewayTokenVerifier {
    private ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
    private final Logger logger = LogManager.getLogger(GatewayTokenVerifier.class);

    public GatewayTokenVerifier(JWKSet jwkSet) {
        var immutableJWKSet = new ImmutableJWKSet<>(jwkSet);
        jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT));
        JWSAlgorithm expectedJWSAlg = JWSAlgorithm.RS256;
        JWSKeySelector<SecurityContext> keySelector;
        keySelector = new JWSVerificationKeySelector<>(expectedJWSAlg, immutableJWKSet);
        jwtProcessor.setJWSKeySelector(keySelector);
        jwtProcessor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().build(),
                new HashSet<>(Arrays.asList("sub", "exp", "clientId"))));
    }

    public Mono<ServiceCaller> verify(String token) {
        try {
            logger.debug(format("Verify gateway access with token: %s", token));
            var parts = token.split(" ");
            if (parts.length == 2) {
                var credentials = parts[1];
                return Mono.justOrEmpty(jwtProcessor.process(credentials, null))
                        .flatMap(jwtClaimsSet -> {
                            try {
                                var clientId = jwtClaimsSet.getStringClaim("clientId");
                                return Mono.just(new ServiceCaller(clientId, getRoles(jwtClaimsSet)));
                            } catch (ParseException e) {
                                logger.error(format("Unauthorized gateway access with token: %s", token), e);
                                return Mono.empty();
                            }
                        });
            }
            logger.error(format("Unauthorized access with token: %s", token));
            return Mono.empty();
        } catch (ParseException | BadJOSEException | JOSEException e) {
            logger.error("Unauthorized gateway access with token: " + token, e);
            return Mono.empty();
        }
    }

    private List<Role> getRoles(JWTClaimsSet jwtClaimsSet) {
        Object realmAccessObj = jwtClaimsSet.getClaim("realm_access");
        if (realmAccessObj!=null && realmAccessObj instanceof JSONObject) {
            JSONObject realmAccess = (JSONObject) realmAccessObj;
            return ((JSONArray) realmAccess.get("roles"))
                    .stream()
                    .map(Object::toString)
                    .map(mayBeRole -> Role.valueOfIgnoreCase(mayBeRole).orElse(null))
                    .filter(Objects::nonNull)
                    .collect(toList());
        }
        return List.of(Role.GATEWAY);
    }

}
