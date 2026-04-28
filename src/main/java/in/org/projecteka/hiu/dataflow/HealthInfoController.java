package in.org.projecteka.hiu.dataflow;

import in.org.projecteka.hiu.Caller;
import in.org.projecteka.hiu.consent.TokenUtils;
import in.org.projecteka.hiu.dataflow.model.HealthInformation;
import in.org.projecteka.hiu.dataflow.model.HealthInformationFetchRequest;
import in.org.projecteka.hiu.dataflow.model.PatientHealthInformation;
import in.org.projecteka.hiu.dataflow.model.DataRequestStatusResponse;
import in.org.projecteka.hiu.dataflow.model.DataRequestStatusCheckRequest;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Map;

import static in.org.projecteka.hiu.common.Constants.API_PATH_FETCH_PATIENT_HEALTH_INFO;
import static in.org.projecteka.hiu.common.Constants.API_PATH_GET_INFO_FOR_SINGLE_CONSENT_REQUEST;
import static in.org.projecteka.hiu.common.Constants.API_PATH_GET_ATTACHMENT;
import static in.org.projecteka.hiu.common.Constants.CM_API_PATH_GET_ATTACHMENT;
import static in.org.projecteka.hiu.common.Constants.API_PATH_GET_HEALTH_INFO_STATUS;

@SuppressWarnings("MVCPathVariableInspection")
@RestController
@AllArgsConstructor
public class HealthInfoController {

    private static final Map<String, String> FILE_EXTENSION_TO_CONTENT_TYPE = Map.of("pdf", "application/pdf",
                "dcm", "application/dicom",
                "doc", "application/msword",
                "rtf", "text/rtf",
                "jpeg", "image/jpeg",
                "png", "image/png",
                "wav", "audio/wav",
                "mpeg", "video/mpeg");

    private final HealthInfoManager healthInfoManager;
    private final DataFlowServiceProperties serviceProperties;

    @GetMapping(API_PATH_GET_INFO_FOR_SINGLE_CONSENT_REQUEST)
    public Mono<HealthInformation> fetchHealthInformation1(
            @PathVariable(value = "consent-request-id") String consentRequestId,
            @RequestParam(defaultValue = "${hiu.dataflowservice.defaultPageSize}") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(Caller::getUsername)
                .flatMapMany(username -> healthInfoManager.fetchHealthInformation(consentRequestId, username))
                .collectList()
                .map(dataEntries -> HealthInformation.builder()
                        .size(dataEntries.size())
                        .limit(Math.min(limit, serviceProperties.getMaxPageSize()))
                        .offset(offset)
                        .entries(dataEntries).build());
    }

    @PostMapping(API_PATH_FETCH_PATIENT_HEALTH_INFO)
    public Mono<PatientHealthInformation> fetchHealthInformation2(@RequestBody HealthInformationFetchRequest dataRequest) {
        var limit = Math.min(dataRequest.getLimit(serviceProperties.getDefaultPageSize()), serviceProperties.getMaxPageSize());
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(Caller::getUsername)
                .flatMap(username -> healthInfoManager.fetchHealthInformation(
                        dataRequest.getRequestIds(), username, limit, dataRequest.getOffset()))
                .map(tuple -> PatientHealthInformation.builder()
                        .size(tuple.getT2())
                        .limit(limit)
                        .offset(dataRequest.getOffset())
                        .entries(tuple.getT1()).build());
    }

    @GetMapping(value = {API_PATH_GET_ATTACHMENT, CM_API_PATH_GET_ATTACHMENT})
    public Mono<ResponseEntity<FileSystemResource>> fetchHealthInformation3(
            @PathVariable(value = "consent-request-id") String consentRequestId,
            @PathVariable(value = "file-name") String fileName) {
        try {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(Caller::getUsername)
                .flatMapMany(username -> healthInfoManager.getTransactionIdForConsentRequest(consentRequestId, username))
                .map(transactionId -> Paths.get(
                        serviceProperties.getLocalStoragePath(),
                        new TokenUtils().encode(consentRequestId),
                        new TokenUtils().encode(transactionId), fileName))
                .filter(Files::exists)
                .collectList()
                .filter(filePaths -> !filePaths.isEmpty())
                .map(filePaths -> {
                    var filePath = filePaths.get(0);
                    System.out.println("filePath: " + filePath);
                    long fileSize = 0;
                    try {
                        fileSize = Files.size(filePath);
                        System.out.println("fileSize: " + fileSize);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                                            + filePath.getFileName().toString() + "\"")
                            .header(HttpHeaders.CONTENT_TYPE, responseContentType(filePath))
                            .contentLength(fileSize)
                            .contentType(MediaType.parseMediaType(responseContentType(filePath)))
                            .body(new FileSystemResource(filePath));
                })
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            return Mono.just(ResponseEntity.notFound().build());
        }
    }

    @PostMapping(API_PATH_GET_HEALTH_INFO_STATUS)
    public Mono<DataRequestStatusResponse> fetchHealthInformationStatus4(@RequestBody DataRequestStatusCheckRequest dataRequest) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> (Caller) securityContext.getAuthentication().getPrincipal())
                .map(Caller::getUsername)
                .flatMapMany(username -> healthInfoManager.fetchHealthInformationStatus(dataRequest.getRequestIds(), username))
                .collectList()
                .map(DataRequestStatusResponse::new);
    }

    @SneakyThrows
    private MediaType responseContentTypeOld(Path filePath) {
        String contentType = Files.probeContentType(filePath);
        System.out.println("contentType: " + contentType);
        if (contentType == null || contentType.isEmpty()) {
            contentType = FILE_EXTENSION_TO_CONTENT_TYPE.get(filePath.toString().substring(filePath.toString().lastIndexOf(".") + 1));
        }
        MediaType mType = MediaType.parseMediaType(contentType);
        System.out.println("MediaType: " + mType);
        return mType;
    }

    private String responseContentType(Path path) {
        try {
                String contentType = Files.probeContentType(path);
                System.out.println("contentType: " + contentType);
                return contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM.toString();
        } catch (IOException e) {
                System.out.println("IOException: " + e.getMessage());
                return MediaType.APPLICATION_OCTET_STREAM.toString();
        }
    }
}
