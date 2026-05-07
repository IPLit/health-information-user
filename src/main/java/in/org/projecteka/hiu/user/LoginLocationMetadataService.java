package in.org.projecteka.hiu.user;

import com.fasterxml.jackson.databind.JsonNode;

import in.org.projecteka.hiu.OpenMrsProperties;
import lombok.AllArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

@AllArgsConstructor
public class LoginLocationMetadataService {
    private static final String VISIT_LOCATION_TAG = "Visit Location";
    private static final String ATTR_ABDM_HFR_ID = "ABDM HFR ID";
    private static final String ATTR_ABDM_HFR_NAME = "ABDM HFR Name";
    private OpenMrsProperties openMrsProperties = null;
    private WebClient webClient = null;
    private static boolean isInitialized = false;
    private static final Logger logger = LogManager.getLogger(LoginLocationMetadataService.class);

    public void initLoginLocationMetadataService() {
        if (StringUtils.hasText(openMrsProperties.getBaseUrl())) {
            webClient = WebClient.builder().baseUrl(openMrsProperties.getBaseUrl())
            .defaultHeaders(headers -> headers.setBasicAuth(
                openMrsProperties.getUsername(), openMrsProperties.getPassword()))
            .build();
            isInitialized = true;
        }
    }

    public LoginLocationMetadata fromLoginLocation(String loginLocationUuid) {
        if (!isInitialized) {
            initLoginLocationMetadataService();
        }
        if (!StringUtils.hasText(loginLocationUuid)) {
            return null;
        }
        var location = getLocation(loginLocationUuid)
                .flatMap(this::visitLocationFor)
                .map(this::extractMetadata)
                .block();
        logger.debug("location {}", location);
        return location;
    }

    private Mono<JsonNode> visitLocationFor(JsonNode location) {
        if (isVisitLocation(location)) {
            return Mono.just(location);
        }

        var parentLocation = location.path("parentLocation");
        if (parentLocation.isMissingNode() || parentLocation.isNull()) {
            return null;
        }
        var parentUuid = parentLocation.path("uuid").asText();
        if (!StringUtils.hasText(parentUuid)) {
            return null;
        }
        return getLocation(parentUuid).flatMap(this::visitLocationFor);
    }

    private LoginLocationMetadata extractMetadata(JsonNode visitLocation) {
        return LoginLocationMetadata.builder()
                .visitLocationUuid(visitLocation.path("uuid").asText())
                .abdmHfrId(attributeValue(visitLocation, ATTR_ABDM_HFR_ID).orElse(null))
                .abdmHfrName(attributeValue(visitLocation, ATTR_ABDM_HFR_NAME).orElse(null))
                .build();
    }

    private Mono<JsonNode> getLocation(String locationUuid) {
        return webClient.get()
                .uri("/ws/rest/v1/location/"+locationUuid+"?v=full")
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    private boolean isVisitLocation(JsonNode location) {
        var tags = location.path("tags");
        if (!tags.isArray()) {
            return false;
        }
        for (JsonNode tag : tags) {
            var display = tag.path("display");
            if (display.isMissingNode() || display.isNull()) {
                continue;
            }
            var displayText = display.asText();
            if (VISIT_LOCATION_TAG.equalsIgnoreCase(displayText)) {
                logger.debug("isVisitLocation: true for display: {}", displayText);
                return true;
            }
        }
        return false;
    }

    private Optional<String> attributeValue(JsonNode location, String attributeTypeName) {
        var attributes = location.path("attributes");
        if (!attributes.isArray()) {
            return null;
        }
        for (JsonNode attribute : attributes) {
            var typeDisplay = attribute.path("display");
            if (typeDisplay.isMissingNode() || typeDisplay.isNull()) {
                continue;
            }
            var typeDisplayText = typeDisplay.asText();
            if (typeDisplayText.toLowerCase().contains(attributeTypeName.toLowerCase())) {
                var value = typeDisplayText.split(":")[1];
                if (StringUtils.hasText(value)) {
                    logger.debug("attributeValue: {} for attribute type: {}", value, attributeTypeName);
                    return Optional.of(value.trim());
                }
            }
        }
        return null;
    }

}
