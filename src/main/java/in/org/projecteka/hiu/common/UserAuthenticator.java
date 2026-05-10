package in.org.projecteka.hiu.common;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.MACVerifier;
import in.org.projecteka.hiu.Caller;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Mono;

import java.text.ParseException;

import static java.lang.String.format;

public class UserAuthenticator implements Authenticator {

    private MACVerifier verifier = null;
    private final Logger logger = LogManager.getLogger(UserAuthenticator.class);

    public UserAuthenticator(byte[] sharedSecret) throws JOSEException {
        try {
            verifier = new MACVerifier(sharedSecret);
        } catch (Exception e) {
            logger.error("Error in setup MACVerifier, " + sharedSecret, e);
        }
    }

    @Override
    public Mono<Caller> verify(String token) {
        try {
            logger.debug(format("Verify user access with token: %s", token));
            var parts = token.split(" ");
            if (parts.length != 2)
                return Mono.empty();

            var jwsObject = JWSObject.parse(parts[1]);
            if (!isValidToken(jwsObject)) {
                logger.error(format("Unauthorized user access with token: %s", token));
                return Mono.empty();
            }
            var jsonObject = jwsObject.getPayload().toJSONObject();
            var isVerified = Boolean.parseBoolean(jsonObject.getAsString("isVerified"));
            return Mono.just(new Caller(
                    jsonObject.getAsString("username"),
                    false,
                    jsonObject.getAsString("role"),
                    isVerified,
                    jsonObject.getAsString("abdmHfrId"),
                    jsonObject.getAsString("abdmHfrName")));
        } catch (ParseException | JOSEException e) {
            logger.error(format("Unauthorized user access with token: %s %s", token, e));
        }
        return Mono.empty();
    }

    private boolean isValidToken(JWSObject jwsObject) throws JOSEException {
        return jwsObject.verify(verifier);
    }
}
