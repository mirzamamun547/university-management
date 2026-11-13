# University Management System (CSE 3230 – Lab 4)

Spring Boot 3 · Java 21 · Spring Security · Spring Data JPA · PostgreSQL · Docker Compose

## Run

```bash
docker compose up --build        # app on http://localhost:8080, PostgreSQL in a second container
# or, without Docker (in-memory H2):
mvn spring-boot:run
```

A first admin is created on startup: **admin / admin123** (override with `ADMIN_USERNAME`, `ADMIN_PASSWORD`).

## Web UI

Open **http://localhost:8080/** (or the host port you mapped in `compose.yaml`) and sign in.
Admins see Admins / Teachers / Students tabs and can add, edit and delete. Teachers and students only get a "My profile" page where they can view and edit their own details.
The page (`src/main/resources/static`) is plain HTML/CSS/JS and calls the REST API below.

## Access rules (RBAC)

| Action                                   | ADMIN | TEACHER        | STUDENT        |
|------------------------------------------|:-----:|:--------------:|:--------------:|
| `/api/admins/**` (everything)            |  yes  |      no        |      no        |
| List / create / delete teachers          |  yes  |      no        |      no        |
| List / create / delete students          |  yes  |      no        |      no        |
| `GET`/`PUT /api/teachers/{id}`           |  yes  | only own `id`  |      no        |
| `GET`/`PUT /api/students/{id}`           |  yes  |      no        | only own `id`  |

Teachers and students can only view and update their **own** profile (they cannot rename their own
username). Unauthenticated -> `401`, anything not allowed -> `403`. Passwords are stored as BCrypt
hashes and never returned.

## REST API

| Method | Path                     | Success | Notes |
|--------|--------------------------|---------|-------|
| POST   | `/api/auth/login`        | 200     | JSON `{username,password}`, starts a session |
| GET    | `/api/auth/me`           | 200     | current user |
| POST   | `/api/auth/logout`       | 200     | invalidates the session |
| GET    | `/api/{resource}`        | 200     | list |
| GET    | `/api/{resource}/{id}`   | 200     | 404 if missing |
| POST   | `/api/{resource}`        | 201     | `Location` header; 400 invalid, 409 duplicate username/email |
| PUT    | `/api/{resource}/{id}`   | 200     | omit `password` to keep the current one |
| DELETE | `/api/{resource}/{id}`   | 204     | you cannot delete your own account (409) |

`{resource}` is `admins`, `teachers` or `students`. Body: `username, password, fullName, email`, plus `department` (teachers) or `studentNumber` (students).

## Try it

```bash
# login (session cookie saved to jar)
curl -c jar -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' localhost:8080/api/auth/login

# admin creates a teacher and a student
curl -b jar -H 'Content-Type: application/json' -X POST localhost:8080/api/teachers \
  -d '{"username":"t1","password":"secret123","fullName":"Dr. Rahman","email":"t1@uni.edu","department":"CSE"}'
curl -b jar -H 'Content-Type: application/json' -X POST localhost:8080/api/students \
  -d '{"username":"s1","password":"secret123","fullName":"Karim","email":"s1@uni.edu","studentNumber":"2107001"}'

# RBAC: a student cannot list students or see teachers (403) ...
curl -u s1:secret123 -i localhost:8080/api/students
curl -u s1:secret123 -i localhost:8080/api/teachers
# ... but can read their own profile (use the id returned when it was created)
curl -u s1:secret123 localhost:8080/api/students/2

curl -b jar -X POST localhost:8080/api/auth/logout
```

HTTP Basic is also enabled so tools like Postman/curl can use `-u user:pass` directly.

## Project layout

```
controller/  AbstractUserController (shared CRUD) + Admin/Teacher/Student controllers, AuthController
service/     UserService (business rules, hashing)
security/    SecurityConfig (RBAC rules, logout, 401/403 JSON), AppUserDetailsService
model/       AppUser (single table, `role` column), Role
dto/         UserRequest, UserResponse (no password), LoginRequest
exception/   GlobalExceptionHandler -> JSON errors
config/      DataSeeder (first admin)
```

Tests: `mvn test` runs `RbacIntegrationTest` (CRUD for all roles + 401/403/400/409 cases).

CSRF is disabled because this is a JSON API used from scripts; enable it if you add a browser front end.

## Tests and CI

| What | Where | Run with | Needs |
|---|---|---|---|
| 6 unit tests (Mockito, no Spring, no DB) | `src/test/java/.../service/UserServiceTest.java` | `mvn test` | Java 21 + Maven |
| RBAC tests (MockMvc, in-memory H2) | `RbacIntegrationTest.java` | `mvn test` | same |
| 3 integration tests (whole app + real database) | `UniversityFlowIT.java` | `mvn verify -DskipUnitTests=true` | same (H2), or PostgreSQL |
| Docker smoke test (image + compose + HTTP) | `scripts/smoke-test.sh` | `docker compose up -d --build` then `bash scripts/smoke-test.sh` | Docker |

`*Test` classes run in Maven Surefire, `*IT` classes in Failsafe. By default the integration tests use H2.
To run them against PostgreSQL (what CI does):

```bash
docker run -d --name ums-test-db -p 5432:5432 -e POSTGRES_DB=ums_test -e POSTGRES_USER=ums \
  -e POSTGRES_PASSWORD=ums_password postgres:16-alpine

export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ums_test
export SPRING_DATASOURCE_USERNAME=ums
export SPRING_DATASOURCE_PASSWORD=ums_password
mvn verify -DskipUnitTests=true
```
(On Windows Command Prompt use `set NAME=value` instead of `export`.)

### GitHub Actions (`.github/workflows/ci.yml`)
Runs on every push to `main`/`master`, on pull requests, and manually (Actions tab -> Run workflow).

1. **Unit tests**: `mvn test`
2. **Integration tests (PostgreSQL)**: starts a `postgres:16-alpine` Docker service container, runs `mvn verify -DskipUnitTests=true`
3. **Docker build and compose smoke test**: `docker compose up -d --build`, then `scripts/smoke-test.sh`
   (login, role checks, logout against the real containers), logs on failure, `docker compose down -v` at the end

Each job needs the one before it, so a green run means all three passed. Test reports are uploaded as artifacts.
