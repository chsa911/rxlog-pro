package com.rxlog.register.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** This updates checksums in flyway_schema_history to match the current scripts. */
@Configuration
public class FlywayRepairConfig {
  @Bean
  public FlywayMigrationStrategy repairThenMigrate() {
    return (Flyway flyway) -> {
      try {
        flyway.repair();
      } catch (Exception ignored) {
      }
      flyway.migrate();
    };
  }
}
