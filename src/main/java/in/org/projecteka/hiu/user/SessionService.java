package in.org.projecteka.hiu.user;

import in.org.projecteka.hiu.ClientError;
import in.org.projecteka.hiu.Error;
import in.org.projecteka.hiu.ErrorCode;
import in.org.projecteka.hiu.ErrorRepresentation;
import lombok.AllArgsConstructor;

import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Mono;

@AllArgsConstructor
public class SessionService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JWTGenerator jwtGenerator;
    private final LoginLocationMetadataService loginLocationMetadataService;
    private final Logger logger = LogManager.getLogger(SessionService.class);

    public Mono<Session> forNew(SessionRequest sessionRequest) {
        logger.info("sessionRequest {}", sessionRequest);
        return Mono.justOrEmpty(sessionRequest)
                .flatMap(request -> userRepository.with(new String(Base64.getDecoder().decode(request.getUsername()))))
                .filter(user -> passwordEncoder.matches(new String(Base64.getDecoder().decode(sessionRequest.getPassword())), user.getPassword()))
                .flatMap(user -> getLoginLocationMetadata(sessionRequest.getLoginLocationUuid())
                        .map(loginLocationMetadata -> new Session(jwtGenerator.tokenFrom(user, loginLocationMetadata)))
                            .defaultIfEmpty(new Session(jwtGenerator.tokenFrom(null, null))))
                .doOnError(logger::error)
                .switchIfEmpty(Mono.error(new ClientError(HttpStatus.UNAUTHORIZED,
                        new ErrorRepresentation(new Error(ErrorCode.INVALID_USERNAME_OR_PASSWORD,
                                "Invalid username or password")))));
    }

    private Mono<LoginLocationMetadata> getLoginLocationMetadata(String loginLocationUuid) {
        logger.info("loginLocationUuid {}", loginLocationUuid);
        if (!StringUtils.hasText(loginLocationUuid)) {
            return Mono.empty();
        }
        var loginLocationMetadata = loginLocationMetadataService.fromLoginLocation(loginLocationUuid);
        if (loginLocationMetadata == null) {
            return Mono.empty();
        }
        logger.info("loginLocationMetadata {}", loginLocationMetadata);
        return Mono.just(loginLocationMetadata);
    }
}