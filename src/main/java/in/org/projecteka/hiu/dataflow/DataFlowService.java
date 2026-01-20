package in.org.projecteka.hiu.dataflow;

import in.org.projecteka.hiu.ClientError;
import in.org.projecteka.hiu.HiuProperties;
import in.org.projecteka.hiu.LocalDicomServerProperties;
import in.org.projecteka.hiu.clients.HealthInformationClient;
import in.org.projecteka.hiu.common.Gateway;
import in.org.projecteka.hiu.common.cache.CacheAdapter;
import in.org.projecteka.hiu.consent.ConsentRepository;
import in.org.projecteka.hiu.consent.TokenUtils;
import in.org.projecteka.hiu.dataflow.model.DataFlowRequestKeyMaterial;
import in.org.projecteka.hiu.dataflow.model.DataFlowRequestResult;
import in.org.projecteka.hiu.dataflow.model.DataNotificationRequest;
import in.org.projecteka.hiu.dataflow.model.Entry;
import in.org.projecteka.hiu.dataflow.model.HealthInfoStatus;
import in.org.projecteka.hiu.dataprocessor.BinaryResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.CompositionResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.ConditionResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.DiagnosticReportResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.DocumentReferenceResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.HITypeResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.HealthDataRepository;
import in.org.projecteka.hiu.dataprocessor.ImmunizationRecommendationProcessor;
import in.org.projecteka.hiu.dataprocessor.ImmunizationResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.MedicationRequestResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.ObservationResourceProcessor;
import in.org.projecteka.hiu.dataprocessor.model.BundleContext;
import in.org.projecteka.hiu.dataprocessor.model.DataContext;
import in.org.projecteka.hiu.dataprocessor.model.HealthInfoNotificationRequest;
import in.org.projecteka.hiu.dataprocessor.model.HiStatus;
import in.org.projecteka.hiu.dataprocessor.model.Notification;
import in.org.projecteka.hiu.dataprocessor.model.Notifier;
import in.org.projecteka.hiu.dataprocessor.model.ProcessedEntry;
import in.org.projecteka.hiu.dataprocessor.model.SessionStatus;
import in.org.projecteka.hiu.dataprocessor.model.StatusNotification;
import in.org.projecteka.hiu.dataprocessor.model.StatusResponse;
import in.org.projecteka.hiu.dataprocessor.model.Type;
import in.org.projecteka.hiu.dicomweb.OrthancDicomWebServer;
import lombok.AllArgsConstructor;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.util.Pair;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.joining;
import static reactor.core.publisher.Mono.defer;

@AllArgsConstructor
public class DataFlowService {
    private final int MbInBytes = 1000000;
    private final int dataFlowSizeLimitInMb = 27;
    public static final String MEDIA_APPLICATION_FHIR_JSON = "application/fhir+json";
    public static final String MEDIA_APPLICATION_FHIR_XML = "application/fhir+xml";
    private static final String COULD_NOT_RECEIVE_DATA = "Couldn't receive data";
    public static final String TRANSACTION_ID = "transactionId";
    public static final String PATH_TO_FILE = "pathToFile";
    private static final String DATA_PART_NUMBER = "partNumber";
    private final DataFlowRepository dataFlowRepository;
    private final DataAvailabilityPublisher dataAvailabilityPublisher;
    private final DataFlowServiceProperties dataFlowServiceProperties;
    private final LocalDataStore localDataStore;
    private final CacheAdapter<String, DataFlowRequestKeyMaterial> dataFlowCache;
    private final HealthDataRepository healthDataRepository;
    private final Gateway gateway;
    private final Decryptor decryptor;
    private final HealthInformationClient healthInformationClient;
    private final HiuProperties hiuProperties;
    private final ConsentRepository consentRepository;
    private final FhirContext fhirContext = FhirContext.forR4();
    private final LocalDicomServerProperties dicomServerProperties;
    private final List<HITypeResourceProcessor> resourceProcessors = new ArrayList<>();

    private static final Logger logger = LoggerFactory.getLogger(DataFlowService.class);

    public Mono<Void> handleNotification(DataNotificationRequest dataNotificationRequest) {
        logger.warn("[DataFlowService] Received data transfer handleNotification for transactionId={}", dataNotificationRequest.getTransactionId());
        List<Entry> invalidEntries = dataNotificationRequest.getEntries().parallelStream().filter(entry ->
                !(hasLink(entry) || hasContent(entry))).collect(Collectors.toList());

        if (!invalidEntries.isEmpty()) {
            return Mono.error(ClientError.invalidEntryError("Entry must either have content or provide a link."));
        }

        int dataFlowPartNo = 1;
        return validateAndRetrieveRequestedConsent(dataNotificationRequest.getTransactionId())
                .flatMap(consentRequestId -> serializeDataTransferred(dataNotificationRequest, consentRequestId,
                        dataFlowPartNo))
                .flatMap(contentReference -> saveDataAvailability(contentReference, dataFlowPartNo))
                .flatMap(this::notifyDataProcessor);
    }

    private Mono<Map<String, String>> saveDataAvailability(Map<String, String> contentReference, int partNumber) {
        contentReference.put(DATA_PART_NUMBER, String.valueOf(partNumber));
        return dataFlowRepository.insertDataPartAvailability(contentReference.get(TRANSACTION_ID),
                partNumber,
                HealthInfoStatus.RECEIVED)
                .thenReturn(contentReference);
    }

    private Mono<Void> notifyDataProcessor(Map<String, String> contentRef) {
        return dataAvailabilityPublisher.broadcastDataAvailability(contentRef);
    }

    public Mono<Void> updateDataFlowRequest(DataFlowRequestResult dataFlowRequestResult) {
        String requestId = dataFlowRequestResult.getResponse().getRequestId();
        logger.info("updateDataFlowRequest requestId: " + requestId);
        if (dataFlowRequestResult.getError() != null) {
            logger.error("[DataFlowService] Received error response for data flow request. HIU " +
                            "requestId={}, error_code= {}, message= {}",
                    requestId,
                    dataFlowRequestResult.getError().getCode(),
                    dataFlowRequestResult.getError().getMessage());
            return Mono.empty();
        }
        if (dataFlowRequestResult.getHiRequest() == null) {
            logger.error("[DataFlowService] Received null response for data flow request. HIU " +
                    "requestId={}", requestId);
            return Mono.empty();
        }
        var transactionId = dataFlowRequestResult.getHiRequest().getTransactionId().toString();
        var sessionStatus = dataFlowRequestResult.getHiRequest().getSessionStatus();

        logger.info("[DataFlowService] Received response for data flow request. HIU " +
                "transactionId={}, sessionStatus={}, requestId={}", transactionId, sessionStatus, requestId);
        return dataFlowRepository.updateDataRequest(transactionId, sessionStatus, requestId)
                .then(defer(() -> dataFlowCache.get(requestId)))
                .flatMap(dataFlowRequestKeyMaterial ->
                        dataFlowRepository.addKeys(transactionId, dataFlowRequestKeyMaterial));
    }

    private Mono<Map<String, String>> serializeDataTransferred(DataNotificationRequest dataNotificationRequest,
                                                               String consentRequestId, int dataFlowPartNo) {
        Path pathToFile = Paths.get(dataFlowServiceProperties.getLocalStoragePath(),
                getLocalDirectoryName(consentRequestId),
                getLocalDirectoryName(dataNotificationRequest.getTransactionId()),
                localFileNameToSave(dataNotificationRequest.getTransactionId(), dataFlowPartNo));
        return localDataStore.serializeDataToFile(dataNotificationRequest, pathToFile)
                .thenReturn(createContentAvailabilityRef(dataNotificationRequest, pathToFile));
    }

    private Map<String, String> createContentAvailabilityRef(DataNotificationRequest dataNotificationRequest, Path pathToFile) {
        Map<String, String> contentRef = new HashMap<>();
        contentRef.put(TRANSACTION_ID, dataNotificationRequest.getTransactionId());
        contentRef.put(PATH_TO_FILE, pathToFile.toString());
        return contentRef;
    }

    private String localFileNameToSave(String transactionId, int dataFlowPartNo) {
        //TODO: potentially append part (e.g. page number)
        return String.format("%s_%d.json", TokenUtils.encode(transactionId), dataFlowPartNo);
    }

    private String getLocalDirectoryName(String consentRequestId) {
        return String.format("%s", TokenUtils.encode(consentRequestId));
    }

    private Mono<String> validateAndRetrieveRequestedConsent(String transactionId) {
        return dataFlowRepository.retrieveDataFlowRequest(transactionId)
                .filter(dataMap -> !hasConsentArtefactExpired((LocalDateTime) dataMap.get("consentExpiryDate")))
                .switchIfEmpty(Mono.error(ClientError.consentArtefactGone()))
                .map(dataMap -> (String) dataMap.get("consentRequestId"))
                .doOnError(throwable -> logger.error(throwable.getMessage(), throwable));
    }

    private boolean hasConsentArtefactExpired(LocalDateTime dataEraseAt) {
        return dataEraseAt != null && dataEraseAt.isBefore(LocalDateTime.now(in.org.projecteka.hiu.common.Utils.zOffset));
    }

    private boolean hasContent(Entry entry) {
        return (entry.getContent() != null && !entry.getContent().isBlank() && !IsLinkable(entry.getContent()));
    }

    private boolean hasLink(Entry entry) {
        return (entry.getContent() != null && !entry.getContent().isBlank() && IsLinkable(entry.getContent()));
    }

    public Mono<Void> handleTransferHealthInformation(DataNotificationRequest dataNotificationRequest) {
        logger.warn("[DataFlowService] Received handleTransferHealthInformation for transactionId={}", dataNotificationRequest.getTransactionId());
        List<Entry> invalidEntries = dataNotificationRequest.getEntries().parallelStream().filter(entry ->
                !(hasLink(entry) || hasContent(entry))).collect(Collectors.toList());
        if (!invalidEntries.isEmpty()) {
            logger.error("Entry must either have content or provide a link.");
            return Mono.error(ClientError.invalidEntryError("Entry must either have content or provide a link."));
        }

        if (resourceProcessors.isEmpty()) {
            resourceProcessors.addAll(Arrays.<HITypeResourceProcessor>asList(
                    new CompositionResourceProcessor(),
                    new DocumentReferenceResourceProcessor(),
                    new ObservationResourceProcessor(),
                    new ConditionResourceProcessor(),
                    new MedicationRequestResourceProcessor(),
                    new DiagnosticReportResourceProcessor(new OrthancDicomWebServer(dicomServerProperties)),
                    new ImmunizationResourceProcessor(),
                    new ImmunizationRecommendationProcessor(),
                    new BinaryResourceProcessor()
            ));
        }

        String consentRequestId = dataNotificationRequest.getTransactionId();
        Path dataFilePath = Paths.get(dataFlowServiceProperties.getLocalStoragePath(),
                getLocalDirectoryName(consentRequestId),
                getLocalDirectoryName(dataNotificationRequest.getTransactionId()));

        return dataFlowRepository.getConsentId(consentRequestId)
            .flatMap(consentId ->
                consentRepository.getHipId(consentId).map(hipId -> Pair.of(consentId, hipId)))
            .flatMap(pair -> Mono.just(createDataContext(dataNotificationRequest, dataFilePath, pair.getSecond(), pair.getFirst())))
            .flatMap(context -> dataFlowRepository.getKeys(context.getTransactionId())
                .flatMap(keyMaterial -> {
                    if (keyMaterial != null) {
                        processEntries(context, keyMaterial);
                        return Mono.empty();
                    } else {
                        logger.error("Could not create handleTransferHealthInformation context for transactionId={}", dataNotificationRequest.getTransactionId());
                        return Mono.error(new RuntimeException("Could not create context"));
                    }
                })
            );
    }

    private DataContext createDataContext(DataNotificationRequest dataNotificationRequest, Path dataFilePath, String hipId, String consentId) {
        try {
            logger.info("Created context with hipId as {} and data file path {}", hipId, dataFilePath);
            return DataContext.builder()
                    .notifiedData(dataNotificationRequest)
                    .dataFilePath(dataFilePath)
                    .dataPartNumber("1")
                    .trackedResources(new ArrayList<>())
                    .hipId(hipId)
                    .consentId(consentId)
                    .build();
        } catch (Exception e) {
            logger.error("Could not create context from data file path", e);
            throw new RuntimeException(e);
        }
    }

    private void processEntries(DataContext context, DataFlowRequestKeyMaterial keyMaterial) {
        String transactionId = context.getTransactionId();
        try {
            logger.info(String.format(
                "Received data for transaction: %s. Number of entries: %d. Processing data.",
                context.getTransactionId(), context.getNumberOfEntries()));
            List<String> dataErrors = new ArrayList<>();
            if (context.getNotifiedData().getEntries()!=null && context.getNotifiedData().getEntries().size() > 0) {
                List<Entry> entries = context.getNotifiedData().getEntries();
                List<StatusResponse> statusResponses = new ArrayList<>();
                String dataPartNumber = context.getDataPartNumber();
                for (int indexProcessed = 0; entries!=null && indexProcessed < entries.size(); indexProcessed++) {
                    Entry entry = entries.get(indexProcessed);
                    int currentIndex = indexProcessed + 1;
                    logger.info("Processing entry {}/{} for care-context: {}", currentIndex,
                        context.getNumberOfEntries(), entry.getCareContextReference());
                    Entry entryToProcess = entry;
                    if (hasLink(entry)) {
                        healthInformationClient.informationFrom(entry.getContent()).doOnSuccess(healthInformation -> {
                            boolean isError = false;
                            if (healthInformation == null) {
                                isError = true;
                                dataErrors.add("Health Information not found");
                                healthDataRepository
                                        .insertErrorFor(transactionId, dataPartNumber, entry.getCareContextReference())
                                    .doOnSuccess(errorResult -> {
                                        statusResponses.add(getStatusResponse(entry, HiStatus.ERRORED, COULD_NOT_RECEIVE_DATA));
                                    }).subscribe();
                                logger.error("Health Information not found for care-context: {}", entry.getCareContextReference());
                            } else {
                                Entry processedEntry = Entry.builder()
                                        .content(healthInformation.getContent())
                                        .checksum(entry.getChecksum())
                                        .media(entry.getMedia())
                                        .careContextReference(entry.getCareContextReference())
                                        .build();
                                logger.info("Fetched content for care-context: {}", entry.getCareContextReference());
                                var result = processEntryContent(context, processedEntry, keyMaterial);
                                if (result.hasErrors()) {
                                    isError = true;
                                    dataErrors.addAll(result.getErrors());
                                    healthDataRepository
                                            .insertErrorFor(transactionId, dataPartNumber, processedEntry.getCareContextReference())
                                        .doOnSuccess(errorResult -> {
                                            statusResponses.add(getStatusResponse(entry, HiStatus.ERRORED, COULD_NOT_RECEIVE_DATA));
                                        }).subscribe();
                                    logger.error("Errors in processing entry for care-context: {}. Errors: {}",
                                            entry.getCareContextReference(), String.join(",", result.getErrors()));
                                } else {
                                    logger.info("Successfully processed entry for care-context: {}", entry.getCareContextReference());
                                    context.addTrackedResources(result.getTrackedResources());
                                    Optional<Pair<String, String>> originIdAndName = identifyOrigin(result.getOrigins());
                                    String originId = originIdAndName.isPresent() ? originIdAndName.get().getFirst() : context.getHipId();
                                    healthDataRepository.insertDataFor(transactionId,
                                            dataPartNumber,
                                            result.getResource(),
                                            result.latestResourceDate(),
                                            processedEntry.getCareContextReference(),
                                            result.getUniqueResourceId(),
                                            result.getDocumentType(),
                                            originId)
                                    .doOnSuccess(dataResult -> {
                                        statusResponses.add(getStatusResponse(entry, HiStatus.OK, "Data received successfully"));
                                        logger.info("Processed entry for care-context: {}", entry.getCareContextReference());
                                    }).subscribe();
                                }
                            }
                        }).subscribe();
                    } else {
                        boolean isError = false;
                        var result = processEntryContent(context, entry, keyMaterial);
                        if (result.hasErrors()) {
                            isError = true;
                            dataErrors.addAll(result.getErrors());
                            healthDataRepository
                                    .insertErrorFor(transactionId, dataPartNumber, entryToProcess.getCareContextReference())
                                .doOnSuccess(errorResult -> {
                                    statusResponses.add(getStatusResponse(entry, HiStatus.ERRORED, COULD_NOT_RECEIVE_DATA));
                                }).subscribe();
                            logger.error("Errors in processing entry for care-context: {}. Errors: {}",
                                    entry.getCareContextReference(), String.join(",", result.getErrors()));
                        } else {
                            logger.info("Successfully processed entry for care-context: {}", entry.getCareContextReference());
                            context.addTrackedResources(result.getTrackedResources());
                            Optional<Pair<String, String>> originIdAndName = identifyOrigin(result.getOrigins());
                            String originId = originIdAndName.isPresent() ? originIdAndName.get().getFirst() : context.getHipId();
                            healthDataRepository.insertDataFor(transactionId,
                                    dataPartNumber,
                                    result.getResource(),
                                    result.latestResourceDate(),
                                    entryToProcess.getCareContextReference(),
                                    result.getUniqueResourceId(),
                                    result.getDocumentType(),
                                    originId)
                            .doOnSuccess(dataResult -> {
                                statusResponses.add(getStatusResponse(entry, HiStatus.OK, "Data received successfully"));
                                logger.info("Processed entry for care-context: {}", entry.getCareContextReference());
                            }).subscribe();
                        }
                    }
                }
                var status = dataErrors.size() == context.getNumberOfEntries() ? HealthInfoStatus.ERRORED : HealthInfoStatus.PARTIAL;
                if (!dataErrors.isEmpty()) {
                    var errors = dataErrors.stream().map("[ERROR]"::concat).collect(joining());
                    var allErrors = "[ERROR]".concat(errors);
                    logger.error("Error occurred while processing data from HIP. Transaction id: {}. Errors: {}",
                            context.getTransactionId(), allErrors);
                    statusResponses.add(getStatusResponse(context.getNotifiedData().getEntries().get(0), HiStatus.ERRORED,
                    "Error occurred while processing data from HIP"));
                    updateDataProcessStatus(context, allErrors, status, context.latestResourceDate()).subscribe();
                    notifyHealthInfoStatus(context, statusResponses, SessionStatus.FAILED);
                } else {
                    logger.info("Successfully processed data from HIP for transaction id: {}.", context.getTransactionId());
                    statusResponses.add(getStatusResponse(context.getNotifiedData().getEntries().get(0), HiStatus.OK,
                    "Data received successfully"));
                    updateDataProcessStatus(context, "", HealthInfoStatus.SUCCEEDED, context.latestResourceDate()).subscribe();
                    notifyHealthInfoStatus(context, statusResponses, SessionStatus.TRANSFERRED);
                }
            } else {
                String errorMsg = "No entries found in notification request for transactionId: " + context.getTransactionId();
                logger.error("Error occurred while processing data from HIP. Transaction id: {}. Error: {}",
                        context.getTransactionId(), "No entries found in notification request");
                // List<StatusResponse> statResponses = new ArrayList<>();
                // statResponses.add(getStatusResponse(context.getNotifiedData().getEntries().get(0), HiStatus.ERRORED,
                    // errorMsg));
                updateDataProcessStatus(context, errorMsg, HealthInfoStatus.ERRORED, context.latestResourceDate()).subscribe();
                // notifyHealthInfoStatus(context, statResponses, SessionStatus.FAILED);
            }
        } catch (Exception ex) {
            logger.error("Error occurred while processing data from HIP. Transaction id: {}.", context.getTransactionId());
            logger.error(ex.getMessage(), ex);
            // List<StatusResponse> statResponses = new ArrayList<>();
            // statResponses.add(getStatusResponse(context.getNotifiedData().getEntries().get(0), HiStatus.ERRORED,
            //     "Error occurred while processing data from HIP"));
            updateDataProcessStatus(context, ex.getMessage(), HealthInfoStatus.ERRORED, context.latestResourceDate()).subscribe();
            // notifyHealthInfoStatus(context, statResponses, SessionStatus.FAILED);
        }
    }

    private StatusResponse getStatusResponse(Entry entry, HiStatus hiStatus, String msg) {
        return StatusResponse.builder()
                .careContextReference(entry.getCareContextReference())
                .hiStatus(hiStatus)
                .description(msg)
                .build();
    }

    private Mono<Void> updateDataProcessStatus(DataContext context, String allErrors, HealthInfoStatus status, LocalDateTime latestResourceDate) {
        return dataFlowRepository.updateDataFlowWithStatus(context.getTransactionId(),
                context.getDataPartNumber(),
                allErrors,
                status,
                latestResourceDate);
    }

    private void notifyHealthInfoStatus(DataContext context,
                                        List<StatusResponse> statusResponses,
                                        SessionStatus sessionStatus) {
        HealthInfoNotificationRequest healthInfoNotificationRequest =
                getHealthInfoNotificationRequest(context, statusResponses, sessionStatus);
        gateway.token()
            .flatMap(token -> 
                consentRepository.getConsentMangerId(healthInfoNotificationRequest.getNotification().getConsentId())
                .map(cmId -> Pair.of(cmId, token)))
            .flatMap(pair -> healthInformationClient.notifyHealthInfo(healthInfoNotificationRequest, pair.getSecond(), pair.getFirst()));
    }

    private HealthInfoNotificationRequest getHealthInfoNotificationRequest(DataContext context,
                                                                           List<StatusResponse> statusResponses,
                                                                           SessionStatus sessionStatus) {
        return HealthInfoNotificationRequest.builder()
            .notification(Notification.builder()
                    .consentId(context.getConsentId())
                    .transactionId(context.getTransactionId())
                    .doneAt(LocalDateTime.now(ZoneOffset.UTC))
                    .notifier(Notifier.builder()
                            .type(Type.HIU)
                            .id(context.getHipId()) // hiuProperties.getId()
                            .build())
                    .statusNotification(StatusNotification.builder()
                            .sessionStatus(sessionStatus)
                            .hipId(context.getHipId())
                            .statusResponses(statusResponses)
                            .build())
                    .build())
            .build();
    }

    private boolean IsLinkable(String serializedBundle) {
        byte [] allBytes = serializedBundle.getBytes(StandardCharsets.UTF_8);
        return allBytes.length >= DataSizeLimitInBytes();
    }

    private int DataSizeLimitInBytes() {
        return dataFlowSizeLimitInMb * MbInBytes;
    }

    private ProcessedEntry processEntryContent(DataContext context,
                                               Entry entry,
                                               DataFlowRequestKeyMaterial keyMaterial) {
        logger.info("Processing entry for care-context: {} with media {}", entry.getCareContextReference(), entry.getMedia());
        
        var mayBeParser = getEntryParser(entry.getMedia());
        return mayBeParser.map(parser -> {
            return processEntryWithParser(context, entry, keyMaterial, parser);
        }).orElseGet(() -> {
            logger.error("No parser found for media type: {}", entry.getMedia());
            ProcessedEntry result = new ProcessedEntry();
            result.addError("Can not process entry content, invalid media type. Supported media types are " +
                    MEDIA_APPLICATION_FHIR_JSON + " and " + MEDIA_APPLICATION_FHIR_XML);
            return result;
        });
    }

    private ProcessedEntry processEntryWithParser(DataContext context,
                                                  Entry entry,
                                                  DataFlowRequestKeyMaterial keyMaterial,
                                                  IParser parser) {

        logger.info("Parser {} found for Entry with media type: {}", parser.getClass().getName(), entry.getMedia());

        ProcessedEntry result = new ProcessedEntry();
        String decryptedContent;
        try {
            decryptedContent = decryptor.decrypt(context.getKeyMaterial(), keyMaterial, entry.getContent());
        } catch (Exception e) {
            logger.error("Error while decrypting {exception}", e);
            result.addError("Could not read encrypted content from file");
            return result;
        }
        Bundle bundle = parser.parseResource(Bundle.class, decryptedContent);
        if (!isValidBundleType(bundle)) {
            result.addError("Can not process entry content, invalid envelope." +
                    "Entry content is either not a FHIR Bundle type COLLECTION or DOCUMENT. " +
                    "For Document bundle type (e.g Discharge Summary), the first entry must be composition.");
            return result;
        }
        Function<ResourceType, HITypeResourceProcessor> resourceProcessor = this::identifyResourceProcessor;
        BundleContext bundleContext = new BundleContext(bundle, resourceProcessor);
        try {
            logger.info("Processing bundle id: {} with timestamp {} with resourceProcessor {}", bundle.getId(), bundle.getTimestamp(), resourceProcessor.getClass().getName());
            if (bundle.getTimestamp() == null) {
                bundle.setTimestamp(new Date());
            }
            bundle.getEntry().forEach(bundleEntry -> {
                ResourceType resourceType = bundleEntry.getResource().getResourceType();
                logger.info("bundle entry resource type: {}", resourceType);
                HITypeResourceProcessor processor = identifyResourceProcessor(resourceType);
                if (processor != null) {
                    logger.info("bundle entry resource type {} processing... with {}", resourceType, processor.getClass().getName());
                    processor.process(bundleEntry.getResource(), context, bundleContext, null);
                }
            });
            result.setEncoded(parser.encodeResourceToString(bundle));
            result.setUniqueResourceId(bundleContext.getBundleUniqueId());
            result.setDocumentType(bundleContext.getDocumentType());
            result.setOrigins(bundleContext.getOrigins());
            result.addTrackedResources(bundleContext.getTrackedResources(), bundleContext.getBundleDate());
            return result;
        } catch (Exception e) {
            logger.error("Could not process bundle {exception}", e);
            result.addError(String.format("Could not process bundle with id: %s, error-message: %s",
                    bundle.getId(), e.getMessage()));
            return result;
        }
    }

    private HITypeResourceProcessor identifyResourceProcessor(ResourceType resourceType) {
        return resourceProcessors.stream().filter(p -> p.supports(resourceType)).findAny().orElse(null);
    }

    private boolean isValidBundleType(Bundle bundle) {
        Bundle.BundleType bundleType = bundle.getType();
        if (bundleType.equals(Bundle.BundleType.COLLECTION)) {
            return true;
        }
        if (!bundleType.equals(Bundle.BundleType.DOCUMENT)) {
            return false;
        }
        if (bundle.getEntry().isEmpty()) {
            return false;
        }
        Bundle.BundleEntryComponent firstEntry = bundle.getEntry().get(0);
        return firstEntry.getResource().getResourceType().equals(ResourceType.Composition);
    }

    private Optional<IParser> getEntryParser(String media) {
        if (media.equalsIgnoreCase(MEDIA_APPLICATION_FHIR_JSON)) {
            return Optional.of(fhirContext.newJsonParser());
        }
        if (media.equalsIgnoreCase(MEDIA_APPLICATION_FHIR_XML)) {
            return Optional.of(fhirContext.newXmlParser());
        }
        return Optional.empty();
    }

    private Optional<Identifier> getAffinityDomainIdentifier(List<String> domains, Organization organization) {
        if (!organization.hasIdentifier()) {
            return Optional.empty();
        }
        if (domains.isEmpty()) {
            return Optional.empty();
        }
        return organization.getIdentifier().stream().filter(identifier -> identifier.hasSystem())
                .filter(identifier -> domains.stream().anyMatch(domain -> identifier.getSystem().toUpperCase().contains(domain.toUpperCase())))
                .findFirst();
    }

    private List<String> getHFRAffinityDomains() {
        Optional<String> hfrAffinityDomains = Optional.ofNullable(hiuProperties.getHfrAffinityDomains());
        if (hfrAffinityDomains.isPresent()) {
            return Arrays.asList(hfrAffinityDomains.get().split(","));
        }
        return Collections.emptyList();
    }

    private Optional<Pair<String, String>> identifyOrigin(List<Organization> origins) {
        if ((origins == null) || origins.isEmpty()) {
            return Optional.empty();
        }
        List<String> hfrAffinityDomains = getHFRAffinityDomains();
        List<Organization> domainOrgList = origins.stream().filter(
                    org -> org.hasIdentifier() && getAffinityDomainIdentifier(hfrAffinityDomains, org).isPresent())
                .collect(Collectors.toList());
        if (domainOrgList.isEmpty()) {
            return Optional.empty();
        }
        Organization organization = domainOrgList.get(0);
        Optional<Identifier> identifier = getAffinityDomainIdentifier(hfrAffinityDomains, organization);
        return  identifier.isPresent() ? Optional.of(Pair.of(identifier.get().getValue(), organization.getName())) : Optional.empty();
    }

}
