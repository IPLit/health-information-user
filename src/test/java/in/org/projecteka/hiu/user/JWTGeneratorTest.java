package in.org.projecteka.hiu.user;

import com.nimbusds.jose.JWSObject;
import org.junit.jupiter.api.Test;

import java.text.ParseException;

import static in.org.projecteka.hiu.user.TestBuilders.user;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class JWTGeneratorTest {

    @Test
    void verifyToken() throws ParseException {
        var jwsSignerSecret = "RiPzliMKlBf67mgLpvn6LBg2Fz5OqBBmJNzi4nQ9YMZy0pq2uu".getBytes();
        var user = user().build();
        var tokenWithBearerAlias = new JWTGenerator(jwsSignerSecret).tokenFrom(user, null);

        assertThat(tokenWithBearerAlias.startsWith("Bearer ")).isTrue();

        var token = tokenWithBearerAlias.substring(tokenWithBearerAlias.lastIndexOf(" ") + 1);
        var jwsObject = JWSObject.parse(token);
        var algorithm = jwsObject.getHeader().getAlgorithm().getName();
        var payload = jwsObject.getPayload().toString();

        assertThat(algorithm).isEqualTo("HS256");
        assertThat(payload.contains("role")).isTrue();
        assertThat(payload.contains("isVerified")).isTrue();
        assertThat(payload.contains("exp")).isTrue();
        assertThat(payload.contains("username")).isTrue();
    }
}
