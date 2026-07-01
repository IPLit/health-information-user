package in.org.projecteka.hiu.patient;

import in.org.projecteka.hiu.ClientError;
import in.org.projecteka.hiu.OpenMrsProperties;
import in.org.projecteka.hiu.GatewayProperties;
import in.org.projecteka.hiu.HiuProperties;
import in.org.projecteka.hiu.clients.AbhaAddressServiceClient;
import in.org.projecteka.hiu.clients.GatewayServiceClient;
import in.org.projecteka.hiu.clients.Patient;
import in.org.projecteka.hiu.common.DelayTimeoutException;
import in.org.projecteka.hiu.common.GatewayResponse;
import in.org.projecteka.hiu.common.Utils;
import in.org.projecteka.hiu.common.cache.CacheAdapter;
import in.org.projecteka.hiu.consent.PatientConsentService;
import in.org.projecteka.hiu.patient.model.*;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static in.org.projecteka.hiu.ClientError.gatewayTimeOut;
import static in.org.projecteka.hiu.ErrorCode.PATIENT_NOT_FOUND;
import static in.org.projecteka.hiu.common.Constants.getCmSuffix;
import static in.org.projecteka.hiu.common.CustomScheduler.scheduleThis;
import static in.org.projecteka.hiu.consent.model.consentmanager.ConsentAcknowledgementStatus.OK;
import static java.time.Duration.ofMillis;
import static org.slf4j.LoggerFactory.getLogger;
import static reactor.core.publisher.Mono.defer;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.error;

@AllArgsConstructor
public class PatientService {
    private static final Logger logger = getLogger(PatientService.class);
    private final GatewayServiceClient gatewayServiceClient;
    private final CacheAdapter<String, Patient> cache;
    private final HiuProperties hiuProperties;
    private final GatewayProperties gatewayProperties;
    private final PatientConsentService patientConsentService;
    private final AbhaAddressServiceClient abhaAddressServiceClient;
    private final OpenMrsProperties openMrsProperties;

    private static boolean isInitialized = false;
    private static WebClient bahmniWebClient = null;
    private static final String PATH_PATIENT_SEARCH_WITHIN_CUSTOMER = "/ws/rest/v1/bahmnicore/distro/patientSearchWithinCustomer?patientAttributes=phoneNumber&patientSearchResultsConfig=phoneNumber&s=byIdOrName&startIndex=0&identifier=";

    public void initBahmniWebClient() {
        if (StringUtils.hasText(openMrsProperties.getBaseUrl())) {
            bahmniWebClient = WebClient.builder().baseUrl(openMrsProperties.getBaseUrl())
            .defaultHeaders(headers -> headers.setBasicAuth(
                openMrsProperties.getUsername(), openMrsProperties.getPassword()))
            .build();
            isInitialized = true;
        }
    }


    private Mono<Patient> apply(AbhaAddressSearchResponse response) {
        Patient patient = response.toPatient();
        return cache.put(patient.getIdentifier(),patient).thenReturn(patient);
    }

    public Mono<Patient> tryFind(String id) {
        return findPatientWith(id)
                .onErrorResume(error -> error instanceof ClientError &&
                                ((ClientError) error).getError().getError().getCode() == PATIENT_NOT_FOUND,
                        error -> {
                            logger.error("Consent request created for unknown user.");
                            logger.error(error.getMessage(), error);
                            return empty();
                        });
    }

    public Mono<Patient> findPatientWith(String id) {
        return validatePatientInBahmni(id).then(findPatientFromCm(id));
    }

    private Mono<Void> validatePatientInBahmni(String id) {
        if (!StringUtils.hasText(id) || !openMrsProperties.isLocalisedSearch()) {
            return Mono.empty();
        }
        if (!isInitialized) {
            initBahmniWebClient();
        }
        if (bahmniWebClient == null) {
            return Mono.error(ClientError.networkServiceCallFailed());
        }
        return bahmniWebClient.get()
                .uri(PATH_PATIENT_SEARCH_WITHIN_CUSTOMER + id)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(response -> hasBahmniPatient(response)
                        ? Mono.<Void>empty()
                        : patientNotFoundInBahmni(id))
                .onErrorResume(WebClientResponseException.class, e -> {
                    logger.error("Bahmni patient search failed for identifier: {} - {}", id, e.getMessage());
                    return Mono.error(ClientError.networkServiceCallFailed());
                });
    }

    private Mono<Void> patientNotFoundInBahmni(String id) {
        logger.info("No patient details found for identifier: {} in Bahmni!", id);
        return Mono.error(ClientError.patientNotFound(
                "No patient details found for identifier " + id + " in Bahmni!"));
    }

    private boolean hasBahmniPatient(JsonNode response) {
        if (response == null) {
            return false;
        }
        JsonNode results = response.path("results");
        if (results==null || !results.isArray() || results.isEmpty()) {
            return false;
        }
        JsonNode patient = results.get(0);
        return patient != null && patient.has("uuid") && StringUtils.hasText(patient.get("uuid").asText());
    }

    private Mono<Patient> findPatientFromCm(String id) {
        return getFromCache(id, () ->
        {
            logger.info("about to get patient details from CM for: {}", id);
            var cmSuffix = getCmSuffix(id);
            var request = getFindPatientRequest(id);
            return scheduleThis(abhaAddressServiceClient.findPatientWith(request, cmSuffix))
                    .timeout(ofMillis(gatewayProperties.getRequestTimeout()))
                    .responseFrom(this::apply)
                    .onErrorResume(DelayTimeoutException.class, discard -> error(gatewayTimeOut()))
                    .onErrorResume(TimeoutException.class, discard -> error(gatewayTimeOut()));
        });
    }

    private FindPatientRequest getFindPatientRequest(String id) {
        return new FindPatientRequest(id);
    }

    private Mono<Patient> getFromCache(String key, Supplier<Mono<Patient>> function) {
        return cache.get(key).switchIfEmpty(defer(function));
    }

    public Mono<Void> perform(HiuPatientStatusNotification hiuPatientStatusNotification) {
        final String healthId = hiuPatientStatusNotification.notification.patient.id;
        final String status = hiuPatientStatusNotification.notification.status.toString();
        if (status.equals(Status.DELETED.toString())) {
            return patientConsentService.deleteHealthId(healthId)
                .then(gatewayServiceClient.sendPatientStatusOnNotify(healthId.split("@")[1], buildPatientStatusOnNotify(hiuPatientStatusNotification.requestId)));
        }
        return null;
    }


    private PatientStatusNotification buildPatientStatusOnNotify(UUID requestID) {
        var requestId = UUID.randomUUID();
        var patientOnNotifyRequest = PatientStatusNotification
                .builder()
                .timestamp(LocalDateTime.now(Utils.zOffset))
                .requestId(requestId);
        GatewayResponse gatewayResponse = new GatewayResponse(requestID.toString());
        patientOnNotifyRequest.resp(gatewayResponse).build();
        return patientOnNotifyRequest.acknowledgement(PatientStatusAcknowledgment.builder().status(OK).build()).build();
    }
}
