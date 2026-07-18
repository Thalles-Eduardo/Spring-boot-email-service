# Refactor do Spring-boot-email-service — Design

Data: 2026-07-18

## Contexto

Serviço pequeno (10 classes Java) que expõe `POST /sending-email`, valida um DTO, envia e-mail via
`JavaMailSender` e persiste o resultado no MongoDB. Há um `RabbitMQConfig` e um `EmailConsumer`
configurados, mas o Controller nunca publica na fila — chama o `EmailService` diretamente.

Auditoria encontrou, por categoria:

**Segurança**
- Endpoint `/sending-email` sem autenticação nem rate limiting.
- `emailFrom` é 100% controlado pelo cliente da API → qualquer chamador pode enviar e-mail/spam/phishing
  se passando por qualquer remetente, usando as credenciais SMTP do servidor (open-relay via API).
- `spring-boot-starter-parent` em 3.1.2 — linha 3.x inteira está EOL (fim do suporte 2026-06-30).

**Correção / concorrência**
- `EmailModel.emailId` é anotado com `jakarta.persistence.Id` (JPA), não com
  `org.springframework.data.annotation.Id` (Spring Data). Para um `@Document` do Mongo, isso significa
  que o Mongo não reconhece `emailId` como a chave real do documento.
- `EmailService.getLastEmailId()` carrega a coleção inteira (`findAll()`) a cada envio só para achar o
  "último" e incrementar — O(n) por request, e sob concorrência duas requisições podem calcular o mesmo
  próximo ID (race condition), causando IDs duplicados/sobrescrita silenciosa.
- Imports mortos (`UUID`, anotações JPA soltas) em DTO/Model/Repository.

**Arquitetura / performance**
- `spring-boot-starter-data-jpa` é dependência morta (projeto é 100% MongoDB) — infla build/startup.
- RabbitMQ configurado mas não usado no caminho real: Controller bloqueia a requisição HTTP até o SMTP
  responder, em vez de publicar na fila e responder imediatamente.
- Sem DLQ: uma exceção não tratada no consumer causaria requeue infinito (poison message).

**Erros / observabilidade**
- `EmailConsumer` loga com `System.out.println`.
- `EmailService` engole `MailException` silenciosamente (sem log).
- Sem `@RestControllerAdvice` — respostas de erro inconsistentes.

**Qualidade**
- Injeção de dependência via `@Autowired` em campo, não construtor.
- `EmailModel` tem anotações JPA (`@Column`) sem efeito nenhum em um documento Mongo.
- Cobertura de teste é só `contextLoads()`.

## Decisões (aprovadas pelo usuário)

1. **Implementar as correções no código**, não só relatório.
2. **Endpoint protegido**: header `X-API-KEY` validado via filtro + rate limiting simples em memória;
   `emailFrom` deixa de vir do cliente — passa a ser um valor fixo configurado no servidor
   (`app.mail.from`).
3. **RabbitMQ real**: Controller publica na fila via novo `EmailProducer` e responde `202 Accepted`;
   `EmailConsumer` continua sendo o único ponto que efetivamente envia e persiste. Adicionar DLQ básica.
4. **ID do documento**: trocar `emailId` de `Long` sequencial manual para `String` com `ObjectId` nativo
   do Mongo (`org.springframework.data.annotation.Id`), eliminando `getLastEmailId()`.
5. **Dependências**: atualizar `spring-boot-starter-parent` para `3.5.16` (última patch da linha 3.x,
   ainda que EOL — evita o salto maior para Spring Boot 4, que exige Jakarta EE 11 / Jackson 3 e tem
   ressalvas de compatibilidade no `springdoc-openapi`). Remover `spring-boot-starter-data-jpa`.

## Escopo do refactor

### 1. Modelo / Repositório
- `EmailModel.emailId`: `Long` → `String`, anotado com `org.springframework.data.annotation.Id`.
- Remover `@Column`, imports JPA e `UUID` não usado.
- `EmailRepository extends MongoRepository<EmailModel, String>`; remover import `UUID` morto.
- `EmailDto`: remover `emailFrom`, remover imports JPA/`UUID` mortos.

### 2. Configuração de mail/segurança
- Nova propriedade `app.mail.from` (endereço fixo de remetente, lido de config).
- `EmailService.sendEmail` usa esse valor fixo em vez de `emailModel.getEmailFrom()` vindo do cliente.
- Novo `ApiKeyAuthFilter` (`OncePerRequestFilter`): valida header `X-API-KEY` contra
  `app.security.api-key`; 401 se ausente/inválido.
- Novo rate limiter simples (bucket em memória por IP), aplicado no mesmo filtro ou em um
  `HandlerInterceptor` dedicado; 429 quando excedido.

### 3. RabbitMQ
- Novo `EmailProducer` com `RabbitTemplate.convertAndSend(queue, emailDto)`.
- `EmailController.sendingEmail`: valida o DTO, publica via `EmailProducer`, retorna `202 Accepted`
  (sem `EmailModel` ainda, já que o processamento é assíncrono).
- `EmailConsumer.listen`: mantém a lógica atual (copia para `EmailModel`, chama `EmailService.sendEmail`),
  troca `System.out.println` por `Logger` (SLF4J).
- `RabbitMQConfig`: adicionar fila de dead-letter (`x-dead-letter-exchange`/routing key) e a
  `Queue`/`Binding`/`Exchange` correspondentes.

### 4. Tratamento de erros
- Novo `@RestControllerAdvice` (`GlobalExceptionHandler`): `MethodArgumentNotValidException` → 400 com
  detalhes dos campos; exceção de auth → 401; genérica → 500 com corpo padronizado, sem stacktrace.
- `EmailService`: logar a `MailException` capturada (SLF4J) antes de marcar `ERROR`.

### 5. Qualidade
- Trocar `@Autowired` em campo por injeção via construtor em `EmailController`, `EmailConsumer`,
  `EmailService` (usar `@RequiredArgsConstructor` do Lombok ou construtor explícito).

### 6. Dependências / config
- `pom.xml`: `spring-boot-starter-parent` → `3.5.16`; remover `spring-boot-starter-data-jpa`.
- Criar `src/main/resources/application.yaml.example` documentando todas as propriedades necessárias
  (Mongo, RabbitMQ, Mail, `app.security.api-key`, `app.mail.from`) — sem segredos reais. Não criar o
  `application.yaml` real (não existe no disco, está no `.gitignore`, e não temos os segredos do
  usuário).

### 7. Testes
- `EmailServiceTest`: mocka `EmailRepository`/`JavaMailSender`; cobre envio OK, falha de SMTP (status
  `ERROR` + log), e confirma que não há mais `getLastEmailId()`/race condition (ID vem do Mongo).
- `ApiKeyAuthFilterTest`: request sem header → 401; header errado → 401; header certo → passa adiante.
- `EmailProducerTest` / `EmailControllerTest`: confirma que o Controller publica na fila e responde 202,
  sem chamar `EmailService` diretamente.
- `EmailConsumerTest`: confirma que o consumer chama `EmailService.sendEmail` com os dados corretos.

## Fora de escopo

- Autenticação de usuário final (OAuth2/JWT) — API key simples é suficiente para o caso de uso atual.
- Migração para Spring Boot 4.
- Persistir/expor histórico de e-mails via novos endpoints de consulta (não foi pedido).
- Infra (Docker Compose para Mongo/RabbitMQ) — fora do pedido original.
