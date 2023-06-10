package in.org.projecteka.hiu.consent;

import in.org.projecteka.hiu.ClientError;
import in.org.projecteka.hiu.clients.GatewayServiceClient;
import in.org.projecteka.hiu.common.cache.CacheAdapter;
import in.org.projecteka.hiu.consent.model.ConsentArtefactReference;
import in.org.projecteka.hiu.consent.model.ConsentArtefactRequest;
import in.org.projecteka.hiu.consent.model.ConsentNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static in.org.projecteka.hiu.common.Constants.getCmSuffix;
import static in.org.projecteka.hiu.consent.model.ConsentStatus.GRANTED;
import static reactor.core.publisher.Flux.fromIterable;
import static reactor.core.publisher.Mono.defer;
import static reactor.core.publisher.Mono.error;
import static reactor.core.publisher.Mono.just;

public class GrantedConsentTask extends ConsentTask {
    private static final Logger logger = LoggerFactory.getLogger(GrantedConsentTask.class);
    private final GatewayServiceClient gatewayClient;
    private final CacheAdapter<String, String> gatewayResponseCache;

    public GrantedConsentTask(ConsentRepository consentRepository,
                              GatewayServiceClient gatewayClient,
                              CacheAdapter<String, String> gatewayResponseCache) {
        super(consentRepository);
        this.gatewayClient = gatewayClient;
        this.gatewayResponseCache = gatewayResponseCache;
    }

    private Mono<Void> perform(ConsentArtefactReference reference, String consentRequestId, String cmSuffix) {
        var requestId = UUID.randomUUID();
        logger.info("ConsentArtefactReference.reference: " + reference);
        return gatewayResponseCache.put(requestId.toString(), consentRequestId)
                .then(defer(() -> {
                    var consentArtefactRequest = ConsentArtefactRequest
                            .builder()
                            .consentId(reference.getId())
                            .timestamp(LocalDateTime.now(ZoneOffset.UTC))
                            .requestId(requestId)
                            .build();
                    return gatewayClient.requestConsentArtefact(consentArtefactRequest, cmSuffix);
                }));
    }

    @Override
    public Mono<Void> perform(ConsentNotification consentNotification, LocalDateTime timeStamp, UUID requestID) {
        var consentRequestId = consentNotification.getConsentRequestId();
        logger.info("consentNotification.getConsentArtefacts: " + consentNotification.getConsentArtefacts());
        return consentRepository.get(consentRequestId)
                .switchIfEmpty(defer(() -> {
                    logger.error("Response came for unknown consent request {}", consentRequestId);
                    return error(ClientError.consentRequestNotFound());
                }))
                .flatMap(consentRequest -> consentRepository.updateConsentRequestStatus(GRANTED,
                        consentRequestId).thenReturn(consentRequest))
                .map(consentRequest -> getCmSuffix(consentRequest.getPatient().getId()))
                .flatMapMany(cmSuffix -> {
                    List<ConsentArtefactReference> collect = consentNotification.getConsentArtefacts().stream().sorted().collect(Collectors.toList());
                    logger.info("collect: " + collect);
                    logger.info("collect.get(0): " + collect.get(0));
                    return perform(collect.get(0), consentRequestId, cmSuffix);
                })
                // .flatMapMany(cmSuffix -> fromIterable(consentNotification.getConsentArtefacts())
                //      .flatMap(reference -> perform(reference, consentRequestId, cmSuffix)))
                //.flatMapMany(cmSuffix ->
                //        perform(consentNotification.getConsentArtefacts().get(1), consentRequestId, cmSuffix))
                .ignoreElements();
    }
}
