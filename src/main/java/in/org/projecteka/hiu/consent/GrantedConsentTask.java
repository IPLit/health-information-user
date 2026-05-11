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
import java.util.UUID;

import org.springframework.data.util.Pair;

import static in.org.projecteka.hiu.common.Constants.getCmSuffix;
import static reactor.core.publisher.Flux.fromIterable;
import static reactor.core.publisher.Mono.defer;
import static reactor.core.publisher.Mono.error;

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

    private Mono<Void> perform(ConsentArtefactReference reference, String consentRequestId, String cmSuffix, String hipId) {
        var requestId = UUID.randomUUID();
        return gatewayResponseCache.put(requestId.toString(), consentRequestId)
                .then(defer(() -> {
                    var consentArtefactRequest = ConsentArtefactRequest
                            .builder()
                            .consentId(reference.getId())
                            .build();
                    return gatewayClient.requestConsentArtefact(consentArtefactRequest, cmSuffix, requestId, hipId);
                }));
    }

    @Override
    public Mono<Void> perform(ConsentNotification consentNotification, LocalDateTime timeStamp, UUID requestID) {
        var consentRequestId = consentNotification.getConsentRequestId();
        return consentRepository.get(consentRequestId)
                .switchIfEmpty(defer(() -> {
                    logger.error("Response came for unknown consent request {}", consentRequestId);
                    return error(ClientError.consentRequestNotFound());
                }))
                .flatMap(consentRequest -> consentRepository.updateConsentRequestAsGranted(consentRequest, consentRequestId)
                        .thenReturn(consentRequest))
                //.flatMap(consentRequest -> consentRepository.updateConsentRequestStatus(GRANTED,
                //        consentRequestId).thenReturn(consentRequest))
                .map(consentRequest -> {
                    var cmSuffix = getCmSuffix(consentRequest.getPatient().getId());
                    var hiuId = consentRequest.getHip()!=null ? consentRequest.getHip().getId() : "";
                    return Pair.of(cmSuffix, hiuId);
                })
                .flatMap(pair -> gatewayClient.sendConsentOnNotify(pair.getFirst(), buildConsentOnNotifyRequestForReference(consentNotification.getConsentArtefacts(), 
                requestID), pair.getSecond())
                        .thenReturn(pair))
                .flatMapMany(pair -> fromIterable(consentNotification.getConsentArtefacts())
                        .flatMap(reference -> perform(reference, consentRequestId, pair.getFirst(), pair.getSecond())))
                .ignoreElements();
    }

}