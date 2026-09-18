# Slice 0 exit criterion: one command starts the stack and every service is
# ready. `make up` is that command.

SHELL := /bin/sh
COMPOSE := docker compose
MIX := $(COMPOSE) run --rm --no-deps -T central mix
DB_URL_TEST := ecto://dispatch:dispatch@postgres:5432/dispatch_test

.DEFAULT_GOAL := help
.PHONY: help up down logs ps shell setup migrate seed reset \
        test test-unit test-integration test-web test-android test-e2e \
        format format-check lint typecheck security audit \
        openapi openapi-check migration-check secret-scan build ci

help: ## List targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

# --- stack ------------------------------------------------------------------

up: ## Start the full development stack and wait for health
	$(COMPOSE) up -d --wait
	@echo "central   http://localhost:4000/health/ready"
	@echo "oidc      http://localhost:5556/dex/.well-known/openid-configuration"
	@echo "postgres  localhost:5432 (dispatch/dispatch)"

down: ## Stop the stack and remove volumes
	$(COMPOSE) down -v

logs: ## Follow service logs
	$(COMPOSE) logs -f

ps: ## Show service status
	$(COMPOSE) ps

shell: ## Open an IEx shell in the central service
	$(COMPOSE) exec central iex -S mix

# --- database ---------------------------------------------------------------

setup: ## Fetch dependencies and create the development database
	$(MIX) setup

migrate: ## Apply migrations (never automatic on boot; Section 22)
	$(MIX) ash.migrate

seed: ## Load development seed data
	$(MIX) run priv/repo/seeds.exs

reset: ## Drop, recreate, and migrate the development database
	$(MIX) ash.reset

# --- CI gates (Section 33.5) ------------------------------------------------

format: ## Apply Elixir formatting
	$(MIX) format

format-check: ## Gate: format
	$(MIX) format --check-formatted

lint: ## Gate: lint
	$(MIX) credo --strict
	./scripts/check-invariants.sh
	./scripts/check-layout.sh

typecheck: ## Gate: typecheck
	$(MIX) dialyzer

test-unit: ## Gate: unit (no database required)
	$(MIX) test --exclude integration

test-integration: ## Gate: integration-postgres
	$(COMPOSE) run --rm -T -e MIX_ENV=test -e DATABASE_URL=$(DB_URL_TEST) central mix test.integration

test-web: ## Gate: web-unit
	cd web && node --test test/*.test.js

test-android: ## Gate: android-unit
	cd apps/android-driver && ./gradlew testDebugUnitTest

test-e2e: ## Gate: playwright
	cd e2e && npx playwright test

test: test-unit test-integration test-web ## Run every test suite that needs no Android SDK

security: ## Static security analysis
	$(MIX) sobelow --config

audit: ## Gate: dependency-audit
	$(MIX) deps.audit
	$(MIX) hex.audit

openapi: ## Regenerate contracts/openapi.json
	$(MIX) openapi.generate

openapi-check: ## Gate: openapi-diff
	$(MIX) openapi.check

migration-check: ## Gate: migration-check
	$(MIX) ash_postgres.generate_migrations --check

secret-scan: ## Gate: secret-scan
	# Git mode, matching CI. A working-tree scan would report build artifacts
	# that are gitignored and never committed, which is noise rather than a leak.
	docker run --rm -v "$(PWD):/repo" zricethezav/gitleaks:latest detect \
	  --source=/repo --redact --config=/repo/.gitleaks.toml

build: ## Gate: container-build
	docker build -f infra/containers/central.Dockerfile --target runtime -t dispatch-central:local .

ci: format-check lint typecheck test-unit test-integration test-web openapi-check audit ## Gates runnable without an Android SDK
