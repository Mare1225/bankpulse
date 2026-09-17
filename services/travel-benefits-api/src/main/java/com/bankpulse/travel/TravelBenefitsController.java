package com.bankpulse.travel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
public class TravelBenefitsController {
  private final RedemptionRepository repo;
  private final String secret;
  private final Counter eligibilityChecks; private final Counter credentialsIssued; private final Counter redemptionsRecorded;
  TravelBenefitsController(RedemptionRepository repo,@Value("${travel.credential-secret}") String secret, MeterRegistry registry){
    this.repo=repo;this.secret=secret;
    this.eligibilityChecks=Counter.builder("travel.eligibility.checks").description("Consultas de elegibilidad de miembro").register(registry);
    this.credentialsIssued=Counter.builder("travel.credentials.issued").description("Credenciales firmadas emitidas").register(registry);
    this.redemptionsRecorded=Counter.builder("travel.redemptions.recorded").description("Redenciones de beneficio registradas").register(registry);
  }

  @GetMapping("/eligibility/{memberId}")
  Eligibility eligibility(@PathVariable String memberId){
    eligibilityChecks.increment();
    boolean eligible = memberId != null && !memberId.isBlank();
    return new Eligibility(memberId, "LOUNGE-ANNUAL", eligible, eligible ? 4 : 0);
  }

  @PostMapping("/credentials")
  Credential credential(@Valid @RequestBody CredentialRequest r) throws Exception {
    credentialsIssued.increment();
    Instant expiresAt=Instant.now().plus(6, ChronoUnit.HOURS);
    String payload=r.memberId()+"|"+r.benefitCode()+"|"+expiresAt.toEpochMilli();
    Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
    String signature=HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    return new Credential(r.memberId(),r.benefitCode(),expiresAt,payload,signature,"OFFLINE_VERIFIABLE_DEMO");
  }

  @PostMapping("/redemptions") @ResponseStatus(HttpStatus.CREATED)
  Redemption redeem(@Valid @RequestBody RedemptionRequest r){ redemptionsRecorded.increment(); return repo.save(new Redemption(null,r.memberId(),r.benefitCode(),"RECORDED",r.usedAt()==null?Instant.now():r.usedAt(),Instant.now())); }

  @GetMapping("/redemptions/{memberId}") List<Redemption> list(@PathVariable String memberId){return repo.findByMemberId(memberId);}

  public record Eligibility(String memberId,String benefitCode,boolean eligible,int remainingUses){}
  public record CredentialRequest(@NotBlank String memberId,@NotBlank String benefitCode){}
  public record Credential(String memberId,String benefitCode,Instant expiresAt,String payload,String signature,String mode){}
  public record RedemptionRequest(@NotBlank String memberId,@NotBlank String benefitCode,Instant usedAt){}
}
