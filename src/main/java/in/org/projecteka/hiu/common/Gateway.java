package in.org.projecteka.hiu.common;

import in.org.projecteka.hiu.GatewayProperties;
import in.org.projecteka.hiu.clients.Token;
import in.org.projecteka.hiu.clients.GatewayAuthenticationClient;
import in.org.projecteka.hiu.common.cache.CacheAdapter;
import lombok.AllArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Mono;

@AllArgsConstructor
public class Gateway {
    private final Logger logger = LogManager.getLogger(Gateway.class);

    private final GatewayProperties gatewayProperties;
    private final GatewayAuthenticationClient gatewayAuthenticationClient;
    private CacheAdapter<String, String> accessTokenCache;

    public Mono<String> token() {
        return accessTokenCache.get("hiu:gateway:accessToken")
                .switchIfEmpty(tokenUsingSecret())
                .doOnError(error -> logger.error("Error getting token from cache or secret "));
    }

    private Mono<String> tokenUsingSecret() {
        return gatewayAuthenticationClient.getTokenFor(gatewayProperties.getClientId(), gatewayProperties.getClientSecret())
                .filter(tokenVal -> tokenVal != null && tokenVal.getBearerToken() != null && !tokenVal.getBearerToken().isEmpty())
                .flatMap(tokenVal -> {
                    logger.debug("Gateway access token generated! " + tokenVal.getBearerToken());
                    return accessTokenCache.put("hiu:gateway:accessToken", tokenVal.getBearerToken())
                            .thenReturn(tokenVal.getBearerToken());
                });
    }
}
