package in.org.projecteka.hiu;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;

import java.util.Optional;

@Value
@Builder
@Getter
public class Caller {
    String username;
    Boolean isServiceAccount;
    String role;
    boolean verified;
    String visitLocationUuid;
    String abdmHfrId;
    String abdmHfrName;

    public Caller(String username, Boolean isServiceAccount, String role, boolean verified,
                  String visitLocationUuid, String abdmHfrId, String abdmHfrName) {
        this.username = username;
        this.isServiceAccount = isServiceAccount;
        this.role = role;
        this.verified = verified;
        this.visitLocationUuid = visitLocationUuid;
        this.abdmHfrId = abdmHfrId;
        this.abdmHfrName = abdmHfrName;
    }
  
    public Optional<String> getRole() {
        return Optional.ofNullable(role);
    }

    public Optional<String> getVisitLocationUuid() {
        return Optional.ofNullable(visitLocationUuid);
    }

    public Optional<String> getAbdmHfrId() {
        return Optional.ofNullable(abdmHfrId);
    }

    public Optional<String> getAbdmHfrName() {
        return Optional.ofNullable(abdmHfrName);
    }
}
