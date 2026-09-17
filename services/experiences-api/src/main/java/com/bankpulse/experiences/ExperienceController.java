package com.bankpulse.experiences;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/experiences")
public class ExperienceController {
  private final ExperienceRepository repo;
  private final Counter catalogQueries; private final Counter guaranteesCreated;
  ExperienceController(ExperienceRepository repo, MeterRegistry registry) {
    this.repo = repo;
    this.catalogQueries = Counter.builder("experiences.catalog.queries").description("Consultas al catálogo de experiencias").register(registry);
    this.guaranteesCreated = Counter.builder("experiences.guarantees.created").description("Garantías de pago generadas desde experiencias").register(registry);
  }

  @GetMapping
  List<Experience> list(@RequestParam(required=false) String city) {
    catalogQueries.increment();
    return city == null ? repo.findByActiveTrue() : repo.findByCityIgnoreCaseAndActiveTrue(city);
  }

  @GetMapping("/{id}")
  Experience byId(@PathVariable String id) { guaranteesCreated.increment(); return repo.findById(id).orElseThrow(); }

  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  Experience create(@Valid @RequestBody CreateExperience request) {
    return repo.save(new Experience(null, request.name(), request.city(), request.category(), request.price(), request.capacity(), true, Instant.now()));
  }

  public record CreateExperience(@NotBlank String name, @NotBlank String city, @NotBlank String category,
                                 @DecimalMin("0.01") BigDecimal price, @Min(1) int capacity) {}
}
