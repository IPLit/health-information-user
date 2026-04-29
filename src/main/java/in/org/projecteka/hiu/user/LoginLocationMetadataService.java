package in.org.projecteka.hiu.user;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

@AllArgsConstructor
public class LoginLocationMetadataService {
    private static final String VISIT_LOCATION_TAG = "Visit Location";
    private static final String ATTR_ABDM_HFR_ID = "ABDM HFR ID";
    private static final String ATTR_ABDM_HFR_NAME = "ABDM HFR Name";

    private final WebClient webClient;

    public Mono<LoginLocationMetadata> fromLoginLocation(String loginLocationUuid) {
        if (!StringUtils.hasText(loginLocationUuid)) {
            return Mono.empty();
        }
        return getLocation(loginLocationUuid)
                .flatMap(this::visitLocationFor)
                .flatMap(this::extractMetadata)
                .onErrorResume(throwable -> Mono.empty());
    }

    private Mono<JsonNode> visitLocationFor(JsonNode location) {
        if (isVisitLocation(location)) {
            return Mono.just(location);
        }

        var parentUuid = textAt(location, "parentLocation", "uuid");
        if (!StringUtils.hasText(parentUuid)) {
            return Mono.empty();
        }
        return getLocation(parentUuid).flatMap(this::visitLocationFor);
    }

    private Mono<LoginLocationMetadata> extractMetadata(JsonNode visitLocation) {
        return Mono.just(LoginLocationMetadata.builder()
                .visitLocationUuid(textAt(visitLocation, "uuid"))
                .abdmHfrId(attributeValue(visitLocation, ATTR_ABDM_HFR_ID).orElse(null))
                .abdmHfrName(attributeValue(visitLocation, ATTR_ABDM_HFR_NAME).orElse(null))
                .build());
    }

    private Mono<JsonNode> getLocation(String locationUuid) {
        return webClient.get()
                .uri("/ws/rest/v1/location/{uuid}?v=full", locationUuid)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    private boolean isVisitLocation(JsonNode location) {
        var tags = location.path("tags");
        if (!tags.isArray()) {
            return false;
        }
        for (JsonNode tag : tags) {
            var display = textAt(tag, "display");
            var name = textAt(tag, "name");
            if (VISIT_LOCATION_TAG.equalsIgnoreCase(display) || VISIT_LOCATION_TAG.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> attributeValue(JsonNode location, String attributeTypeName) {
        var attributes = location.path("attributes");
        if (!attributes.isArray()) {
            return Optional.empty();
        }
        for (JsonNode attribute : attributes) {
            var typeDisplay = textAt(attribute.path("attributeType"), "display");
            var typeName = textAt(attribute.path("attributeType"), "name");
            if (attributeTypeName.equalsIgnoreCase(typeDisplay) || attributeTypeName.equalsIgnoreCase(typeName)) {
                var value = textAt(attribute, "value");
                if (StringUtils.hasText(value)) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    private String textAt(JsonNode node, String... fields) {
        JsonNode cursor = node;
        for (String field : fields) {
            cursor = cursor.path(field);
        }
        return cursor.isMissingNode() || cursor.isNull() ? null : cursor.asText();
    }
}
