# Car Rental (Spring Boot)

REST application implementing a car rental system with inventory by **car type** (Sedan, SUV, Van).

## Tech
- Java 21, Spring Boot 3.3.x
- Spring Authorization Server (JWT) + Resource Server
- PostgreSQL + Flyway
- Redis cache (availability)
- Amazon S3 (via MinIO locally) for license uploads
- Hypersistence Utils (PostgreSQL `tsrange` + `jsonb` mappings)
- Mail (MailHog)
- Docker & Testcontainers

## Quick start (Docker)
```bash
docker compose up --build
```
Services:
- App: http://localhost:8080
- MailHog UI: http://localhost:8025
- MinIO console: http://localhost:9001 (minioadmin/minioadmin)
- Postgres: localhost:5432 (car_rental / car_rental)
- Redis: localhost:6379

### First run
Flyway seeds car types (SEDAN, SUV, VAN).

### OAuth clients
- `car-rental-admin`, `car-rental-public` (client_credentials)
  - token endpoint: `POST /oauth2/token`
  - client secret: `admin-secret`
  - scope: `admin:write`

## REST (selected)

### Public
- `GET /api/cars/types`
- `GET /api/cars/types/{typeId}`
- `GET /api/cars/search?from=ISO&to=ISO`
- `GET /api/cars/types/{typeId}?from=ISO&to=ISO`

### Auth
- `POST /api/auth/register`
- `GET /api/auth/verify?token=...`
- OAuth2:
  - `GET /.well-known/jwks.json` (JWKs)
  - `POST /oauth2/token`

### Booking (ROLE_USER, scope `bookings:write`)
- `POST /api/bookings` (multipart, `Idempotency-Key` required)
- `GET /api/bookings/{bookingId}`
- `POST /api/bookings/{bookingId}/cancel`

### Admin (client credentials, scope `admin:write`)
- `GET /api/admin/bookings?status=...&from=ISO&to=ISO`
  - Each item contains `carRegistrationNumber` (may be null for `TO_CONFIRM`)
- `POST /api/admin/bookings/{id}/confirm` body: `{ "carRegistrationNumber": "XYZ-123" }`
- `POST /api/admin/bookings/{id}/reject`
- `GET /api/admin/stats?from=ISO&to=ISO`
  - Stats are **by car type**

## Availability logic
Availability = `totalQuantity - overlappingBookingsCount`
Overlaps include statuses: `TO_CONFIRM`, `BOOKED`, `OCCUPIED`.
`POST /api/bookings` **reserves** capacity immediately (status `TO_CONFIRM`).
Redis cache: keys `avail:{typeId}:{fromEpoch}:{toEpoch}`, TTL 300s; invalidated on create/confirm/reject/cancel/finish.

## Email & License uploads
- On booking creation, an email is sent to the user (captured by MailHog).
- Driver license image is stored in MinIO (`car-rental` bucket).

## Build locally
```bash
mvn clean verify
```

## Tests
Integration tests (Testcontainers) spin Postgres, Redis, MailHog, MinIO.

## Configuration (env)
See `src/main/resources/application.yml` and `docker-compose.yml`.

## Notes
- Times are handled in **UTC**. PostgreSQL stores `tsrange` (without tz) using UTC-normalized timestamps.
- Currency: USD. Daily price by car type. Days = ceil((end-start)/24h).
- Idempotency keys kept 24h (table `idempotency_keys`).

