# Email Service Security/Performance/Correctness Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the security hole (unauthenticated open-relay endpoint), the correctness/concurrency bug
in ID generation, wire up the RabbitMQ queue that's currently configured but unused, and clean up dead
code/dependencies in `Spring-boot-email-service`.

**Architecture:** Controller validates and publishes to RabbitMQ (`EmailProducer`) and returns `202` immediately;
`EmailConsumer` (existing) is the only path that calls `EmailService`, which now uses a Mongo-native
`String` id, a server-configured `from` address, and constructor injection. Two `OncePerRequestFilter`s
(`RateLimitFilter`, `ApiKeyAuthFilter`) gate all requests except Swagger. A `@RestControllerAdvice`
standardizes error responses.

**Tech Stack:** Spring Boot 3.5.16, Spring Data MongoDB, Spring AMQP (RabbitMQ), Spring Mail, JUnit 5,
Mockito, AssertJ (all already on the classpath via `spring-boot-starter-test`).

**Environment note:** This machine only had a JRE 8 before this plan was written; JDK 17
(Eclipse Temurin) was installed via `winget install --id EclipseAdoptium.Temurin.17.JDK` and
`JAVA_HOME` was set persistently to
`C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`. Every command below assumes
`JAVA_HOME` is set — if a fresh shell doesn't have it, run:
`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"` (bash) before `./mvnw.cmd`.

**Baseline (recorded before this plan):** `./mvnw.cmd test` currently fails —
`SpringEmailApplicationTests.contextLoads` throws `DataSourceBeanCreationException: Failed to determine
a suitable driver class`, because `spring-boot-starter-data-jpa` autoconfigures a relational
`DataSource` that nothing in this Mongo-only project provides. Task 1 removes that dependency and is
expected to fix this specific failure. There is no live MongoDB/RabbitMQ/SMTP server in this
environment, so any check that requires one is called out explicitly as "manual verification" rather
than an automated test.

---

### Task 1: Update `pom.xml` — Spring Boot 3.5.16, drop dead JPA dependency, bump springdoc

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Edit the parent version and dependencies**

In `pom.xml`, change the parent block:

```xml
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>3.5.16</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
```

Remove the `spring-boot-starter-data-jpa` dependency block entirely:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
```

Bump the springdoc version (tested against Spring Boot 3.5.x):

```xml
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
			<version>2.8.17</version>
		</dependency>
```

- [ ] **Step 2: Verify the new dependency versions resolve (do NOT run a full compile yet)**

`EmailModel.java` and `EmailDto.java` still import `jakarta.persistence.*` at this point in the plan —
that package comes from `spring-boot-starter-data-jpa`, which this step just removed. A full
`./mvnw.cmd compile` will therefore fail with `package jakarta.persistence does not exist` until
Task 2 rewrites those two files. That's expected and NOT a bug in this task — don't try to fix it here.
This step only confirms the POM edits themselves are valid and the new versions exist in the repo.

Run: `./mvnw.cmd -q dependency:resolve`
Expected: no errors, exit code 0 (confirms Spring Boot 3.5.16's dependency management and
springdoc 2.8.17 resolve correctly). It is fine — expected, even — if this still lists/downloads
`jakarta.persistence`-related artifacts transitively from something else; the point of this check is
only that resolution succeeds, not that JPA is gone from the tree.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "Update Spring Boot to 3.5.16, drop unused JPA starter, bump springdoc"
```

Full compilation and the `SpringEmailApplicationTests` baseline check happen at the end of Task 2
(its Step 6/7), once `EmailModel.java` and `EmailDto.java` no longer reference `jakarta.persistence`.
Do not attempt them in this task.

---

### Task 2: Fix `EmailModel` id mapping, `EmailRepository` id type, and `EmailDto`

**Files:**
- Modify: `src/main/java/br/com/thalleseduardo/springemail/model/EmailModel.java`
- Modify: `src/main/java/br/com/thalleseduardo/springemail/repository/EmailRepository.java`
- Modify: `src/main/java/br/com/thalleseduardo/springemail/dto/EmailDto.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/model/EmailModelTest.java`

**Why:** `EmailModel.emailId` was annotated with `jakarta.persistence.Id` (a JPA annotation), which
Spring Data MongoDB does not recognize as the document's `_id` for a field not literally named `id`.
Combined with `EmailService.getLastEmailId()` (removed in Task 3), this caused an O(n) full-collection
scan per send and a race condition where concurrent requests could compute the same "next" id. The fix
is to let MongoDB generate its native `ObjectId` (as a `String`) and use the correct
`org.springframework.data.annotation.Id`. Separately, `emailFrom` is removed from the client-facing DTO
so the server — not the caller — controls the sending address (see Task 3).

- [ ] **Step 1: Write the failing test for the id annotation**

Create `src/test/java/br/com/thalleseduardo/springemail/model/EmailModelTest.java`:

```java
package br.com.thalleseduardo.springemail.model;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class EmailModelTest {

    @Test
    void emailId_isAnnotatedWithSpringDataIdAndIsAString() throws NoSuchFieldException {
        Field field = EmailModel.class.getDeclaredField("emailId");
        assertThat(field.isAnnotationPresent(Id.class)).isTrue();
        assertThat(field.getType()).isEqualTo(String.class);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./mvnw.cmd -q test -Dtest=EmailModelTest`
Expected: FAIL — `field.getType()` is `Long`, not `String` (or the field doesn't compile against the
new import yet).

- [ ] **Step 3: Rewrite `EmailModel.java`**

```java
package br.com.thalleseduardo.springemail.model;

import java.time.LocalDateTime;

import br.com.thalleseduardo.springemail.enums.StatusEmail;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "emails")
public class EmailModel {

    @Id
    private String emailId;

    private String ownerRef;

    private String emailFrom;

    private String emailTo;

    private String subject;

    private String text;

    private LocalDateTime sendDateEmail;

    private StatusEmail statusEmail;

}
```

- [ ] **Step 4: Update `EmailRepository.java`**

```java
package br.com.thalleseduardo.springemail.repository;

import br.com.thalleseduardo.springemail.model.EmailModel;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmailRepository extends MongoRepository<EmailModel, String> {
}
```

- [ ] **Step 5: Update `EmailDto.java`** (remove `emailFrom` and dead imports)

```java
package br.com.thalleseduardo.springemail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailDto {

    @NotBlank
    private String ownerRef;

    @NotBlank
    @Email
    private String emailTo;

    @NotBlank
    private String subject;

    @NotBlank
    private String text;
}
```

- [ ] **Step 6: Compile (this will show every call site that still references the old fields/types)**

Run: `./mvnw.cmd -q compile`
Expected: FAIL — `EmailService.java` won't compile yet (still uses `Long`/`getEmailFrom` from the
client). That's expected; it's fixed in Task 3. For this step, only confirm the compiler errors are
limited to `EmailService.java` (nothing else).

- [ ] **Step 7: Re-check the Task 1 baseline now that `jakarta.persistence` is gone from `EmailModel`/`EmailDto`**

This isn't expected to fully pass yet (see Step 6 — `EmailService.java` still needs Task 3), but run it
anyway to confirm the *only* compile errors are in `EmailService.java` and nothing else regressed:

Run: `./mvnw.cmd -q test -Dtest=SpringEmailApplicationTests`
Expected: FAIL to compile, with errors confined to `EmailService.java`. You should NOT see any error
mentioning `jakarta.persistence` or `DataSourceBeanCreationException` anymore — if you do, something
here is wrong and needs fixing before moving on.

- [ ] **Step 8: Commit model/repository/dto together (compile is fixed at the end of Task 3, but these three change as one logical unit)**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/model/EmailModel.java \
        src/main/java/br/com/thalleseduardo/springemail/repository/EmailRepository.java \
        src/main/java/br/com/thalleseduardo/springemail/dto/EmailDto.java \
        src/test/java/br/com/thalleseduardo/springemail/model/EmailModelTest.java
git commit -m "Use Mongo-native String id and drop client-controlled emailFrom from EmailDto"
```

---

### Task 3: Rewrite `EmailService` — remove race condition, fix from-address, add logging

**Files:**
- Modify: `src/main/java/br/com/thalleseduardo/springemail/service/EmailService.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/service/EmailServiceTest.java`

**Why:** `getLastEmailId()` (`findAll()` on every send) is removed — MongoDB now assigns the id on save.
`emailFrom` is no longer taken from client input; it comes from `app.mail.from`, closing the open-relay
hole where any caller could send mail impersonating any address using this service's SMTP credentials.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/br/com/thalleseduardo/springemail/service/EmailServiceTest.java`:

```java
package br.com.thalleseduardo.springemail.service;

import br.com.thalleseduardo.springemail.enums.StatusEmail;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.repository.EmailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    private static final String CONFIGURED_FROM = "no-reply@example.com";

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private JavaMailSender emailSender;

    @Test
    void sendEmail_success_setsStatusSentAndForcesConfiguredFromAddress() {
        EmailService emailService = new EmailService(emailRepository, emailSender, CONFIGURED_FROM);
        EmailModel emailModel = new EmailModel();
        emailModel.setEmailFrom("attacker@evil.com");
        emailModel.setEmailTo("dest@example.com");
        emailModel.setSubject("Hi");
        emailModel.setText("Body");
        when(emailRepository.save(any(EmailModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmailModel result = emailService.sendEmail(emailModel);

        assertThat(result.getStatusEmail()).isEqualTo(StatusEmail.SENT);
        assertThat(result.getEmailFrom()).isEqualTo(CONFIGURED_FROM);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo(CONFIGURED_FROM);
    }

    @Test
    void sendEmail_mailException_setsStatusErrorAndStillSaves() {
        EmailService emailService = new EmailService(emailRepository, emailSender, CONFIGURED_FROM);
        EmailModel emailModel = new EmailModel();
        emailModel.setEmailTo("dest@example.com");
        emailModel.setSubject("Hi");
        emailModel.setText("Body");
        doThrow(new MailSendException("smtp down")).when(emailSender).send(any(SimpleMailMessage.class));
        when(emailRepository.save(any(EmailModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmailModel result = emailService.sendEmail(emailModel);

        assertThat(result.getStatusEmail()).isEqualTo(StatusEmail.ERROR);
        verify(emailRepository).save(emailModel);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile** (current `EmailService` constructor takes no args)

Run: `./mvnw.cmd -q test -Dtest=EmailServiceTest`
Expected: FAIL (compilation error — no constructor `EmailService(EmailRepository, JavaMailSender, String)`).

- [ ] **Step 3: Rewrite `EmailService.java`**

```java
package br.com.thalleseduardo.springemail.service;

import br.com.thalleseduardo.springemail.enums.StatusEmail;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailRepository emailRepository;
    private final JavaMailSender emailSender;
    private final String mailFrom;

    public EmailService(EmailRepository emailRepository,
                         JavaMailSender emailSender,
                         @Value("${app.mail.from}") String mailFrom) {
        this.emailRepository = emailRepository;
        this.emailSender = emailSender;
        this.mailFrom = mailFrom;
    }

    public EmailModel sendEmail(EmailModel emailModel) {
        emailModel.setSendDateEmail(LocalDateTime.now());
        emailModel.setEmailFrom(mailFrom);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(emailModel.getEmailTo());
            message.setSubject(emailModel.getSubject());
            message.setText(emailModel.getText());
            emailSender.send(message);

            emailModel.setStatusEmail(StatusEmail.SENT);
        } catch (MailException e) {
            log.error("Failed to send email to {}", emailModel.getEmailTo(), e);
            emailModel.setStatusEmail(StatusEmail.ERROR);
        }
        return emailRepository.save(emailModel);
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./mvnw.cmd -q test -Dtest=EmailServiceTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Full compile check**

Run: `./mvnw.cmd -q compile`
Expected: no output, exit code 0 (confirms Task 2's model/dto/repository changes and this service
rewrite are all consistent).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/service/EmailService.java \
        src/test/java/br/com/thalleseduardo/springemail/service/EmailServiceTest.java
git commit -m "Remove O(n) racy id generation from EmailService, use configured from-address"
```

---

### Task 4: `ApiKeyAuthFilter`

**Files:**
- Create: `src/main/java/br/com/thalleseduardo/springemail/security/ApiKeyAuthFilter.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/security/ApiKeyAuthFilterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/br/com/thalleseduardo/springemail/security/ApiKeyAuthFilterTest.java`:

```java
package br.com.thalleseduardo.springemail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    private static final String VALID_KEY = "secret-key";

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new ApiKeyAuthFilter(VALID_KEY);
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @Test
    void missingHeader_returns401AndStopsChain() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void wrongKey_returns401AndStopsChain() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn("wrong");

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void correctKey_continuesChainWithoutTouchingStatus() throws Exception {
        when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn(VALID_KEY);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw.cmd -q test -Dtest=ApiKeyAuthFilterTest`
Expected: FAIL — compilation error, `ApiKeyAuthFilter` does not exist yet.

- [ ] **Step 3: Create `ApiKeyAuthFilter.java`**

```java
package br.com.thalleseduardo.springemail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(2)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-KEY";

    private final String configuredApiKey;

    public ApiKeyAuthFilter(@Value("${app.security.api-key}") String configuredApiKey) {
        this.configuredApiKey = configuredApiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || !providedKey.equals(configuredApiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Invalid or missing API key\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw.cmd -q test -Dtest=ApiKeyAuthFilterTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/security/ApiKeyAuthFilter.java \
        src/test/java/br/com/thalleseduardo/springemail/security/ApiKeyAuthFilterTest.java
git commit -m "Add API key authentication filter"
```

---

### Task 5: `RateLimitFilter`

**Files:**
- Create: `src/main/java/br/com/thalleseduardo/springemail/security/RateLimitFilter.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/security/RateLimitFilterTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/br/com/thalleseduardo/springemail/security/RateLimitFilterTest.java`:

```java
package br.com.thalleseduardo.springemail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(2, 60);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @Test
    void allowsUpToLimitThenReturns429() throws Exception {
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(2)).doFilter(request, response);
        verify(response).setStatus(429);
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw.cmd -q test -Dtest=RateLimitFilterTest`
Expected: FAIL — compilation error, `RateLimitFilter` does not exist yet.

- [ ] **Step 3: Create `RateLimitFilter.java`**

```java
package br.com.thalleseduardo.springemail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.security.rate-limit.max-requests:20}") int maxRequests,
                            @Value("${app.security.rate-limit.window-seconds:60}") long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000L;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String key = request.getRemoteAddr();
        if (!tryConsume(key)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Rate limit exceeded\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean tryConsume(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= windowMillis) {
                window.startedAt = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= maxRequests;
        }
    }

    private static final class Window {
        private volatile long startedAt;
        private final AtomicInteger count = new AtomicInteger(0);

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw.cmd -q test -Dtest=RateLimitFilterTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/security/RateLimitFilter.java \
        src/test/java/br/com/thalleseduardo/springemail/security/RateLimitFilterTest.java
git commit -m "Add in-memory per-IP rate limiting filter"
```

---

### Task 6: `EmailProducer`

**Files:**
- Create: `src/main/java/br/com/thalleseduardo/springemail/producer/EmailProducer.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/producer/EmailProducerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/br/com/thalleseduardo/springemail/producer/EmailProducerTest.java`:

```java
package br.com.thalleseduardo.springemail.producer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publish_sendsDtoToConfiguredQueue() {
        EmailProducer producer = new EmailProducer(rabbitTemplate, "email-queue");
        EmailDto dto = new EmailDto();
        dto.setOwnerRef("owner-1");
        dto.setEmailTo("dest@example.com");
        dto.setSubject("Hi");
        dto.setText("Body");

        producer.publish(dto);

        verify(rabbitTemplate).convertAndSend("email-queue", dto);
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw.cmd -q test -Dtest=EmailProducerTest`
Expected: FAIL — compilation error, `EmailProducer` does not exist yet.

- [ ] **Step 3: Create `EmailProducer.java`**

```java
package br.com.thalleseduardo.springemail.producer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailProducer {

    private final RabbitTemplate rabbitTemplate;
    private final String queue;

    public EmailProducer(RabbitTemplate rabbitTemplate,
                          @Value("${spring.rabbitmq.queue}") String queue) {
        this.rabbitTemplate = rabbitTemplate;
        this.queue = queue;
    }

    public void publish(EmailDto emailDto) {
        rabbitTemplate.convertAndSend(queue, emailDto);
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw.cmd -q test -Dtest=EmailProducerTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/producer/EmailProducer.java \
        src/test/java/br/com/thalleseduardo/springemail/producer/EmailProducerTest.java
git commit -m "Add RabbitMQ producer for EmailDto"
```

---

### Task 7: Rewire `EmailController` to publish instead of sending synchronously

**Files:**
- Modify: `src/main/java/br/com/thalleseduardo/springemail/controller/EmailController.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/controller/EmailControllerTest.java`

**Why:** Today the controller calls `EmailService.sendEmail` directly, blocking the HTTP response on
the SMTP round-trip and never using the RabbitMQ queue/consumer that's already configured. Publishing
and returning `202 Accepted` immediately is the actual performance win the queue was meant to provide.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/br/com/thalleseduardo/springemail/controller/EmailControllerTest.java`:

```java
package br.com.thalleseduardo.springemail.controller;

import br.com.thalleseduardo.springemail.producer.EmailProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EmailControllerTest {

    @Mock
    private EmailProducer emailProducer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailController(emailProducer)).build();
    }

    private record TestPayload(String ownerRef, String emailTo, String subject, String text) {
    }

    @Test
    void sendingEmail_validPayload_returns202AndPublishes() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(
                new TestPayload("owner-1", "dest@example.com", "Hi", "Body"));

        mockMvc.perform(post("/sending-email")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isAccepted());

        verify(emailProducer).publish(any());
    }

    @Test
    void sendingEmail_missingRequiredField_returns400AndDoesNotPublish() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(
                new TestPayload("", "dest@example.com", "Hi", "Body"));

        mockMvc.perform(post("/sending-email")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailProducer);
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw.cmd -q test -Dtest=EmailControllerTest`
Expected: FAIL — compilation error (`EmailController(EmailProducer)` constructor doesn't exist yet).

- [ ] **Step 3: Rewrite `EmailController.java`**

```java
package br.com.thalleseduardo.springemail.controller;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import br.com.thalleseduardo.springemail.producer.EmailProducer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailController {

    private final EmailProducer emailProducer;

    public EmailController(EmailProducer emailProducer) {
        this.emailProducer = emailProducer;
    }

    @PostMapping("/sending-email")
    public ResponseEntity<Void> sendingEmail(@RequestBody @Valid EmailDto emailDto) {
        emailProducer.publish(emailDto);
        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw.cmd -q test -Dtest=EmailControllerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/controller/EmailController.java \
        src/test/java/br/com/thalleseduardo/springemail/controller/EmailControllerTest.java
git commit -m "Controller publishes to RabbitMQ and returns 202 instead of sending synchronously"
```

---

### Task 8: `EmailConsumer` — structured logging, constructor injection

**Files:**
- Modify: `src/main/java/br/com/thalleseduardo/springemail/consumer/EmailConsumer.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/consumer/EmailConsumerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/br/com/thalleseduardo/springemail/consumer/EmailConsumerTest.java`:

```java
package br.com.thalleseduardo.springemail.consumer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import br.com.thalleseduardo.springemail.enums.StatusEmail;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailConsumerTest {

    @Mock
    private EmailService emailService;

    @Test
    void listen_copiesDtoFieldsAndDelegatesToService() {
        EmailConsumer consumer = new EmailConsumer(emailService);
        EmailDto dto = new EmailDto();
        dto.setOwnerRef("owner-1");
        dto.setEmailTo("dest@example.com");
        dto.setSubject("Hi");
        dto.setText("Body");

        EmailModel saved = new EmailModel();
        saved.setStatusEmail(StatusEmail.SENT);
        when(emailService.sendEmail(any(EmailModel.class))).thenReturn(saved);

        consumer.listen(dto);

        ArgumentCaptor<EmailModel> captor = ArgumentCaptor.forClass(EmailModel.class);
        verify(emailService).sendEmail(captor.capture());
        assertThat(captor.getValue().getOwnerRef()).isEqualTo("owner-1");
        assertThat(captor.getValue().getEmailTo()).isEqualTo("dest@example.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("Hi");
        assertThat(captor.getValue().getText()).isEqualTo("Body");
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw.cmd -q test -Dtest=EmailConsumerTest`
Expected: FAIL — compilation error (`EmailConsumer(EmailService)` constructor doesn't exist yet).

- [ ] **Step 3: Rewrite `EmailConsumer.java`**

```java
package br.com.thalleseduardo.springemail.consumer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.BeanUtils;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);

    private final EmailService emailService;

    public EmailConsumer(EmailService emailService) {
        this.emailService = emailService;
    }

    @RabbitListener(queues = "${spring.rabbitmq.queue}")
    public void listen(@Payload EmailDto emailDto) {
        EmailModel emailModel = new EmailModel();
        BeanUtils.copyProperties(emailDto, emailModel);
        emailModel = emailService.sendEmail(emailModel);
        log.info("Email status: {}", emailModel.getStatusEmail());
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw.cmd -q test -Dtest=EmailConsumerTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/consumer/EmailConsumer.java \
        src/test/java/br/com/thalleseduardo/springemail/consumer/EmailConsumerTest.java
git commit -m "EmailConsumer: constructor injection and SLF4J logging instead of println"
```

---

### Task 9: `GlobalExceptionHandler`

**Files:**
- Create: `src/main/java/br/com/thalleseduardo/springemail/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/br/com/thalleseduardo/springemail/exception/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/br/com/thalleseduardo/springemail/exception/GlobalExceptionHandlerTest.java`:

```java
package br.com.thalleseduardo.springemail.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private MethodArgumentNotValidException validationException;
    @Mock
    private BindingResult bindingResult;
    @Mock
    private HttpServletRequest request;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidation_returns400WithFieldErrors() {
        when(validationException.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors())
                .thenReturn(List.of(new FieldError("emailDto", "emailTo", "must not be blank")));
        when(request.getRequestURI()).thenReturn("/sending-email");

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(validationException, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) response.getBody().get("errors");
        assertThat(errors).containsEntry("emailTo", "must not be blank");
    }

    @Test
    void handleGeneric_returns500WithGenericMessage() {
        when(request.getRequestURI()).thenReturn("/sending-email");

        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(new RuntimeException("boom"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "Unexpected error");
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw.cmd -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — compilation error, `GlobalExceptionHandler` does not exist yet.

- [ ] **Step 3: Create `GlobalExceptionHandler.java`**

```java
package br.com.thalleseduardo.springemail.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                  HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("path", request.getRequestURI());
        body.put("errors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("path", request.getRequestURI());
        body.put("message", "Unexpected error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw.cmd -q test -Dtest=GlobalExceptionHandlerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/exception/GlobalExceptionHandler.java \
        src/test/java/br/com/thalleseduardo/springemail/exception/GlobalExceptionHandlerTest.java
git commit -m "Add global exception handler for consistent error responses"
```

---

### Task 10: `RabbitMQConfig` — add dead-letter queue

**Files:**
- Modify: `src/main/java/br/com/thalleseduardo/springemail/config/RabbitMQConfig.java`

**Why:** Today, an unhandled exception in `EmailConsumer.listen` (e.g. Mongo unreachable) would cause
the message to be requeued indefinitely (poison message loop) since there's no dead-letter routing.
This cannot be covered by an automated test in this environment (no live RabbitMQ broker) — verify
manually once a broker is available (see Step 3).

- [ ] **Step 1: Rewrite `RabbitMQConfig.java`**

```java
package br.com.thalleseduardo.springemail.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    private static final String DEAD_LETTER_EXCHANGE = "email.dlx";
    private static final String DEAD_LETTER_QUEUE = "email.dlq";

    @Value("${spring.rabbitmq.queue}")
    private String queue;

    @Bean
    public Queue queue() {
        return QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_LETTER_QUEUE);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

- [ ] **Step 2: Compile**

Run: `./mvnw.cmd -q compile`
Expected: no output, exit code 0.

- [ ] **Step 3: Manual verification note (record in commit body, don't skip)**

No RabbitMQ broker is available in this environment. Once deployed against a real broker: publish a
message, force `EmailConsumer.listen` to throw (e.g. temporarily point Mongo at an unreachable host),
and confirm the message lands in the `email.dlq` queue via the RabbitMQ management UI instead of
looping forever.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/br/com/thalleseduardo/springemail/config/RabbitMQConfig.java
git commit -m "Add dead-letter queue to the RabbitMQ email queue"
```

---

### Task 11: `application.yaml.example`

**Files:**
- Create: `src/main/resources/application.yaml.example`

**Why:** `src/main/resources/application.yaml` is (correctly) gitignored and does not exist on disk —
there is no committed template documenting which properties the app needs, including the two new ones
introduced by this refactor (`app.mail.from`, `app.security.api-key`).

- [ ] **Step 1: Create `src/main/resources/application.yaml.example`**

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/email-service
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    queue: email-queue
  mail:
    host: smtp.example.com
    port: 587
    username: your-smtp-username
    password: your-smtp-password
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true

app:
  mail:
    from: no-reply@example.com
  security:
    api-key: change-this-to-a-real-secret
    rate-limit:
      max-requests: 20
      window-seconds: 60

server:
  port: 8080
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yaml.example
git commit -m "Document required application.yaml properties in a committed example file"
```

---

### Task 12: README — document the new auth header and config setup

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a "Configuração" section right after "Como Executar", and a note about the header in "API Endpoints"**

Insert after the "Como Executar" section (after the `./mvnw spring-boot:run` code block) and before
"## API Endpoints":

```markdown
## Configuração

Copie `src/main/resources/application.yaml.example` para `src/main/resources/application.yaml` e
preencha com suas credenciais (MongoDB, RabbitMQ, SMTP). Esse arquivo é ignorado pelo git — nunca
commite credenciais reais.
```

Then, in "## API Endpoints", add this line right before the "Enviar e-mail" bullet:

```markdown
Todas as requisições exigem o header `X-API-KEY` com o valor configurado em `app.security.api-key`.
O envio agora é assíncrono: o endpoint responde `202 Accepted` imediatamente e o e-mail é processado
por um consumer RabbitMQ.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "Document API key requirement and async flow in README"
```

---

### Task 13: Full regression check

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./mvnw.cmd test`
Expected: `BUILD SUCCESS`, all tests green (the pre-existing `SpringEmailApplicationTests.contextLoads`
may still fail here if no local MongoDB/RabbitMQ/SMTP server is reachable — that's expected in this
environment and unrelated to this plan; every other test added in Tasks 2–9 must pass).

- [ ] **Step 2: If everything except `contextLoads` is green, no further action — this plan is complete.**

If any test added by this plan fails, stop and fix it before considering the plan done — do not
proceed past a red test.
