package in.org.projecteka.hiu.user;

import in.org.projecteka.hiu.ClientError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static in.org.projecteka.hiu.user.TestBuilders.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

class SessionServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    BCryptPasswordEncoder passwordEncoder;

    @Mock
    LoginLocationMetadataService loginLocationMetadataService;

    @BeforeEach
    void init() {
        initMocks(this);
    }

    public static byte[] sharedSecret() {
        SecureRandom random = new SecureRandom();
        byte[] sharedSecret = new byte[32];
        random.nextBytes(sharedSecret);
        return sharedSecret;
    }

    private static String toBase64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void returnSessionForValidUser() {
        String plainUsername = "test-user";
        String plainPassword = "test-password";
        var session = SessionRequest.builder()
                .username(toBase64(plainUsername))
                .password(toBase64(plainPassword))
                .loginLocationUuid(null)
                .build();
        var user = user().username(plainUsername).build();
        when(userRepository.with(plainUsername)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(plainPassword, user.getPassword())).thenReturn(true);
        var sessionService = new SessionService(userRepository, passwordEncoder, new JWTGenerator(sharedSecret()), loginLocationMetadataService);

        Mono<Session> sessionPublisher = sessionService.forNew(session);

        StepVerifier.create(sessionPublisher)
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void returnErrorWhenSessionRequestIsNull() {
        var sessionService = new SessionService(userRepository, passwordEncoder, new JWTGenerator(sharedSecret()), loginLocationMetadataService);

        Mono<Session> sessionPublisher = sessionService.forNew(null);

        StepVerifier.create(sessionPublisher)
                .expectErrorSatisfies(throwable -> assertThat(throwable).isExactlyInstanceOf(ClientError.class))
                .verify();
    }

    @Test
    void returnErrorWhenPasswordDoesNotMatch() {
        String plainUsername = "test-user";
        String plainPassword = "wrong-password";
        var session = SessionRequest.builder()
                .username(toBase64(plainUsername))
                .password(toBase64(plainPassword))
                .loginLocationUuid(null)
                .build();
        var user = user().username(plainUsername).build();
        when(userRepository.with(plainUsername)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(plainPassword, user.getPassword())).thenReturn(false);
        var sessionService = new SessionService(userRepository, passwordEncoder, new JWTGenerator(sharedSecret()), loginLocationMetadataService);

        Mono<Session> sessionPublisher = sessionService.forNew(session);

        StepVerifier.create(sessionPublisher)
                .expectErrorSatisfies(throwable -> assertThat(throwable).isExactlyInstanceOf(ClientError.class))
                .verify();
    }
}
