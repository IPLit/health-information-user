package in.org.projecteka.hiu.dataflow;

import in.org.projecteka.hiu.dataflow.model.DataFlowRequest;
import in.org.projecteka.hiu.dataflow.model.DataFlowRequestKeyMaterial;
import in.org.projecteka.hiu.dataflow.model.DataPartDetail;
import in.org.projecteka.hiu.dataflow.model.HealthInfoStatus;
import in.org.projecteka.hiu.dataflow.model.RequestStatus;
import in.org.projecteka.hiu.dataflow.model.PatientDataRequestDetail;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.AllArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.stream.Collectors;

import static in.org.projecteka.hiu.ClientError.dbOperationFailure;
import static in.org.projecteka.hiu.common.Serializer.from;
import static in.org.projecteka.hiu.common.Serializer.to;
import static in.org.projecteka.hiu.dataflow.model.RequestStatus.REQUESTED;
import static java.lang.String.format;

@AllArgsConstructor
public class DataFlowRepository {
    private static final String INSERT_TO_DATA_FLOW_REQUEST = "INSERT INTO data_flow_request (transaction_id, " +
            "consent_artefact_id, data_flow_request, request_id) VALUES ($1, $2, $3, $4)";
    private static final String INSERT_DATA_FLOW_REQUEST = "INSERT INTO data_flow_request (request_id, " +
            "consent_artefact_id, data_flow_request) VALUES ($1, $2, $3)";
    private static final String UPDATE_DATA_FLOW_REQUEST = "UPDATE data_flow_request SET transaction_id = $1, status = $2 " +
            "WHERE request_id = $3";
    private static final String INSERT_TO_DATA_FLOW_REQUEST_KEYS = "INSERT INTO data_flow_request_keys " +
            "(transaction_id, " +
            "key_pairs) VALUES ($1, $2)";
    private static final String GET_KEY_FOR_ID = "SELECT key_pairs FROM data_flow_request_keys WHERE transaction_id =" +
            " $1";
    private static final String SELECT_TRANSACTION_IDS_FROM_DATA_FLOW_REQUEST = "SELECT transaction_id FROM " +
            "data_flow_request WHERE consent_artefact_id = $1 and status = $2";
    private static final String INSERT_HEALTH_DATA_AVAILABILITY = "INSERT INTO data_flow_parts (transaction_id, " +
            "part_number, status) VALUES ($1, $2, $3)";
    private static final String SELECT_DATA_FLOW_REQUEST_FOR_TRANSACTION =
            "SELECT  ca.consent_request_id, consent_artefact -> 'permission' ->> 'dataEraseAt' as consent_expiry_date, dfr.data_flow_request " +
                    "FROM data_flow_request dfr " +
                    "INNER JOIN consent_artefact ca ON dfr.consent_artefact_id=ca.consent_artefact_id " +
                    "WHERE dfr.transaction_id=$1";
    private static final String UPDATE_HEALTH_DATA_AVAILABILITY = "UPDATE data_flow_parts SET status = $1, errors = " +
            "$2, latest_res_date = $3 WHERE transaction_id = $4 AND part_number = $5";
    private static final String SELECT_CONSENT_ID = "SELECT consent_artefact_id FROM data_flow_request WHERE " +
            "transaction_id = $1";

    private static final String FETCH_DATA_PART_DETAILS = "select " +
            "ca.consent_artefact -> 'hip' ->> 'id' as hipId, " +
            "ca.consent_artefact -> 'requester' ->> 'name' as requester, " +
            "dfp.transaction_id, dfp.status, ca.consent_request_id, ca.consent_artefact_id " +
            "from data_flow_parts dfp " +
            "join data_flow_request dfr on dfp.transaction_id = dfr.transaction_id " +
            "join consent_artefact ca on dfr.consent_artefact_id = ca.consent_artefact_id " +
            "where ca.consent_request_id in (%s)";

    private static final String FETCH_PATIENT_DATA_REQUEST_DETAILS = "SELECT " +
            "pcr.hip_id, pcr.data_request_id::text, pcr.patient_id, " +
            "pcr.date_created AS patient_data_requested_at, " +
            "dfr.date_created AS data_flow_requested_at, " +
            "dfp.status AS data_part_status, pcr.consent_request_id::text, ca.consent_artefact_id " +
            "FROM patient_consent_request pcr " +
            "LEFT JOIN consent_artefact ca ON pcr.consent_request_id = ca.consent_request_id::uuid " +
            "LEFT JOIN data_flow_request dfr ON dfr.consent_artefact_id = ca.consent_artefact_id " +
            "LEFT JOIN data_flow_parts dfp ON dfr.transaction_id = dfp.transaction_id " +
            "WHERE pcr.data_request_id IN (%s)";

    private static final Logger logger = LogManager.getLogger(DataFlowRepository.class);
    private final PgPool readWriteClient;
    private final PgPool readOnlyClient;

    public Mono<Void> addDataRequest(String transactionId,
                                     String consentId,
                                     DataFlowRequest dataFlowRequest,
                                     String requestId) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(INSERT_TO_DATA_FLOW_REQUEST)
                .execute(Tuple.of(transactionId, consentId, from(dataFlowRequest), requestId),
                        handler -> {
                            if (handler.failed()) {
                                monoSink.error(dbOperationFailure("Failed to insert to data flow request"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> addDataFlowRequest(String requestId, String consentId, DataFlowRequest dataFlowRequest) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(INSERT_DATA_FLOW_REQUEST)
                .execute(Tuple.of(requestId, consentId, from(dataFlowRequest)),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to insert to data flow request"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> updateDataRequest(String transactionId, RequestStatus status, String requestId) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(UPDATE_DATA_FLOW_REQUEST)
                .execute(Tuple.of(transactionId, status.toString(), requestId),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to update data flow request"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> addKeys(String transactionId, DataFlowRequestKeyMaterial dataFlowRequestKeyMaterial) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(INSERT_TO_DATA_FLOW_REQUEST_KEYS)
                .execute(Tuple.of(transactionId, from(dataFlowRequestKeyMaterial)),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to insert to data flow request"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<DataFlowRequestKeyMaterial> getKeys(String transactionId) {
        return Mono.create(monoSink -> readOnlyClient.preparedQuery(GET_KEY_FOR_ID)
                .execute(Tuple.of(transactionId),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to fetch encryption keys"));
                                return;
                            }
                            var iterator = handler.result().iterator();
                            if (!iterator.hasNext()) {
                                logger.error(format("Could not found encryption keys for %s", transactionId));
                                monoSink.error(dbOperationFailure("Failed to fetch encryption keys"));
                                return;
                            }
                            var row = iterator.next();
                            var keyPairsJson = row.getValue("key_pairs").toString();
                            monoSink.success(to(keyPairsJson, DataFlowRequestKeyMaterial.class));
                        }));
    }

    public Mono<String> getTransactionId(String consentArtefactId) {
        return Mono.create(monoSink -> readOnlyClient.preparedQuery(SELECT_TRANSACTION_IDS_FROM_DATA_FLOW_REQUEST)
                .execute(Tuple.of(consentArtefactId, REQUESTED.toString()),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to get transaction Id from consent Id"));
                                return;
                            }
                            var iterator = handler.result().iterator();
                            if (!iterator.hasNext()) {
                                logger.error(format("No transaction id found for consent artefact %s",
                                        consentArtefactId));
                                monoSink.success();
                                return;
                            }
                            monoSink.success(iterator.next().getString(0));
                        }));
    }

    public Mono<String> getConsentId(String transactionId) {
        return Mono.create(monoSink -> readOnlyClient.preparedQuery(SELECT_CONSENT_ID)
                .execute(Tuple.of(transactionId),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to get consent Id from transaction Id"));
                                return;
                            }
                            var iterator = handler.result().iterator();
                            if (!iterator.hasNext()) {
                                logger.error(format("Could not find consent artefact id for %s", transactionId));
                                monoSink.error(dbOperationFailure("Failed to get consent Id from transaction Id"));
                                return;
                            }
                            monoSink.success(iterator.next().getString(0));
                        }));
    }


    public Mono<Map<String, Object>> retrieveDataFlowRequest(String transactionId) {
        return Mono.create(monoSink -> readOnlyClient.preparedQuery(SELECT_DATA_FLOW_REQUEST_FOR_TRANSACTION)
                .execute(Tuple.of(transactionId),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(
                                        dbOperationFailure("Failed to identify data flow request for transaction Id"));
                                return;
                            }
                            var iterator = handler.result().iterator();
                            if (!iterator.hasNext()) {
                                monoSink.error(
                                        dbOperationFailure("Failed to identify data flow request for transaction Id"));
                                return;
                            }
                            Row row = iterator.next();
                            Map<String, Object> flowRequestTransaction = new HashMap<>();
                            flowRequestTransaction.put("consentRequestId", row.getString("consent_request_id"));
                            flowRequestTransaction.put("consentExpiryDate",
                                    LocalDateTime.parse(row.getString("consent_expiry_date")));
                            var request = row.getValue("data_flow_request").toString();
                            flowRequestTransaction.put("dataFlowRequest", to(request, DataFlowRequest.class));
                            monoSink.success(flowRequestTransaction);
                        }));
    }

    public Mono<Void> insertDataPartAvailability(String transactionId, int partNumber, HealthInfoStatus status) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(INSERT_HEALTH_DATA_AVAILABILITY)
                .execute(Tuple.of(transactionId, String.valueOf(partNumber), status.toString()),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to insert health data availability"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> updateDataFlowWithStatus(String transactionId, String dataPartNumber, String allErrors,
                                               HealthInfoStatus status, LocalDateTime latestResourceDate) {
        return Mono.create(monoSink -> readWriteClient.preparedQuery(UPDATE_HEALTH_DATA_AVAILABILITY)
                .execute(Tuple.of(status.toString(), allErrors, latestResourceDate, transactionId, dataPartNumber),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to update health data availability"));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Flux<DataPartDetail> fetchDataPartDetails(List<String> consentRequestIds) {
        var generatedQuery = String.format(FETCH_DATA_PART_DETAILS, joinByComma(consentRequestIds));
        if (consentRequestIds.isEmpty()) {
            return Flux.empty();
        }
        return Flux.create(fluxSink -> readWriteClient.preparedQuery(generatedQuery)
                .execute(handler -> {
                    if (handler.failed()) {
                        logger.error(handler.cause().getMessage(), handler.cause());
                        fluxSink.error(dbOperationFailure("Failed to fetch data part details"));
                        return;
                    }
                    for (Row row : handler.result()) {
                        fluxSink.next(DataPartDetail.builder()
                                .transactionId(row.getString("transaction_id"))
                                .hipId(row.getString("hipid"))
                                .consentArtifactId(row.getString("consent_artefact_id"))
                                .consentRequestId(row.getString("consent_request_id"))
                                .status(HealthInfoStatus.valueOf(row.getString("status")))
                                .requester(row.getString("requester"))
                                .build());
                    }
                    fluxSink.complete();
                }));
    }

    public Flux<PatientDataRequestDetail> fetchPatientDataRequestDetails(Set<String> dataRequestIds) {
        var generatedQuery = String.format(FETCH_PATIENT_DATA_REQUEST_DETAILS, joinByComma(dataRequestIds));
        if (dataRequestIds.isEmpty()) {
            return Flux.empty();
        }
        return Flux.create(fluxSink -> readWriteClient.preparedQuery(generatedQuery)
                .execute(handler -> {
                    if (handler.failed()) {
                        logger.error(handler.cause().getMessage(), handler.cause());
                        fluxSink.error(dbOperationFailure("Failed to fetch patient data request details"));
                        return;
                    }

                    for (Row row : handler.result()) {
                        fluxSink.next(PatientDataRequestDetail.builder()
                                .hipId(row.getString("hip_id"))
                                .dataRequestId(row.getString("data_request_id"))
                                .patientDataRequestedAt(row.getLocalDateTime("patient_data_requested_at"))
                                .dataFlowRequestedAt(row.getLocalDateTime("data_flow_requested_at"))
                                .dataPartStatus(HealthInfoStatus.fromString(row.getString("data_part_status")))
                                .consentRequestId(row.getString("consent_request_id"))
                                .consentArtefactId(row.getString("consent_artefact_id"))
                                .patientId(row.getString("patient_id"))
                                .build());
                    }
                    fluxSink.complete();
                }));
    }


    private String joinByComma(Collection<String> list) {
        return list.stream().map(e -> String.format("'%s'", e)).collect(Collectors.joining(", "));
    }
}
