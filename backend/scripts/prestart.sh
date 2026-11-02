#!/bin/sh
set -eu

# Always run from the project root, regardless of invocation directory.
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

COMPOSE_FILE="$PROJECT_ROOT/compose.yaml"

echo "======================================"
echo " Starting AttributionIQ (Local)"
echo "======================================"

# 1. Validate required tools and configuration.
if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: Docker CLI is not installed."
    exit 1
fi

if ! command -v colima >/dev/null 2>&1; then
    echo "ERROR: Colima is not installed."
    exit 1
fi

if [ ! -f "$COMPOSE_FILE" ]; then
    echo "ERROR: compose.yaml not found at $COMPOSE_FILE"
    exit 1
fi

if [ ! -x "$PROJECT_ROOT/mvnw" ]; then
    echo "ERROR: Maven wrapper (mvnw) is missing or not executable."
    exit 1
fi

# 2. Start Colima only when Docker is unavailable.
if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is unavailable. Starting Colima..."

    if ! colima start; then
        echo "ERROR: Colima failed to start. Spring Boot will not start."
        exit 1
    fi
fi

if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is still unavailable."
    exit 1
fi

echo "Docker is ready."

# 3. Start PostgreSQL and wait for its Compose health check.
echo "Ensuring PostgreSQL is running..."

if ! docker compose -f "$COMPOSE_FILE" up -d --wait postgres; then
    echo "ERROR: PostgreSQL failed to start or become healthy."
    docker compose -f "$COMPOSE_FILE" logs --tail=50 postgres || true
    exit 1
fi

echo "PostgreSQL is healthy."

# 4. Launch Spring Boot only after all prerequisites succeed.
echo "Starting AttributionIQ..."
exec ./mvnw spring-boot:run
