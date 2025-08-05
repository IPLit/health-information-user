package in.org.projecteka.hiu.user;

import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import lombok.AllArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Mono;

import java.util.Objects;

import static in.org.projecteka.hiu.ClientError.dbOperationFailure;

@AllArgsConstructor
public class UserRepository {
    private static final String SELECT_USER_BY_USERNAME = "SELECT username, password, role, verified, hpin FROM " +
            "\"user\" WHERE username = $1";
    private static final String INSERT_USER = "Insert into \"user\" values ($1, $2, $3, $4)";
    private static final String UPDATE_PASSWORD = "UPDATE \"user\" SET password=$2, verified=true WHERE username=$1";

    private final PgPool readWriteClient;
    private final PgPool readOnlyClient;

    private final Logger logger = LogManager.getLogger(UserRepository.class);

    public Mono<User> with(String username) {
        return Mono.create(monoSink ->
                readOnlyClient.preparedQuery(SELECT_USER_BY_USERNAME)
                        .execute(Tuple.of(username),
                                handler -> {
                                    if (handler.failed()) {
                                        logger.error(handler.cause().getMessage(), handler.cause());
                                        monoSink.error(dbOperationFailure("Failed to fetch user."));
                                        return;
                                    }
                                    var iterator = handler.result().iterator();
                                    if (!iterator.hasNext()) {
                                        monoSink.success();
                                        return;
                                    }
                                    monoSink.success(tryFrom(iterator.next()));
                                }));
    }

    public Mono<String> getHpinForUser(String username){
        return with(username)
                .map(User::getHpin)
                .filter(Objects::nonNull)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("HPIN Not found for " + username)));
    }

    public Mono<Void> save(User user) {
        return Mono.create(monoSink ->
                readWriteClient.preparedQuery(INSERT_USER)
                .execute(
                        Tuple.of(user.getUsername(), user.getPassword(), user.getRole().toString(), user.isVerified()),
                        handler -> {
                            if (handler.failed()) {
                                logger.error(handler.cause().getMessage(), handler.cause());
                                monoSink.error(dbOperationFailure("Failed to save user."));
                                return;
                            }
                            monoSink.success();
                        }));
    }

    public Mono<Void> changePassword(String username, String password) {
        return Mono.create(monoSink ->
                readWriteClient.preparedQuery(UPDATE_PASSWORD)
                        .execute(
                                Tuple.of(username, password),
                                handler -> {
                                    if (handler.failed()) {
                                        logger.error(handler.cause().getMessage(), handler.cause());
                                        monoSink.error(dbOperationFailure("Failed to change password."));
                                        return;
                                    }
                                    monoSink.success();
                                }));
    }

    private User tryFrom(Row row) {
        try {
            return new User(row.getString("username"),
                    row.getString("password"),
                    row.getString("role") == null
                    ? Role.DOCTOR
                    : Role.valueOf(row.getString("role").toUpperCase()),
                    row.getBoolean("verified"),
                    row.getString("hpin") != null ? row.getString("hpin") : "");
        } catch (Exception e) {
            logger.error(e);
            return null;
        }
    }
}
