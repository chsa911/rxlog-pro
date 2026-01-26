package com.rxlog.register;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

@SpringBootApplication(
    exclude = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
