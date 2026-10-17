.PHONY: up down test synthetic backup restore-check
up:
	docker compose up --build -d --wait
down:
	docker compose down
test:
	docker compose run --rm -v "$(CURDIR)/docs:/contracts:ro" api pytest -q
	cd frontend && npm ci && npm test && npm run build
synthetic:
	python3 samples/generate.py --days 60
backup:
	@test -n "$(OUT)" || (echo 'Set OUT to an absolute private path outside the checkout'; exit 1)
	@case "$(OUT)" in /*) ;; *) echo 'OUT must be absolute'; exit 1;; esac
	@umask 077; docker compose exec -T db pg_dump -U evinsights -d evinsights -Fc > "$(OUT)".partial && mv "$(OUT)".partial "$(OUT)"
restore-check:
	@test -f "$(OUT)" || (echo 'Set OUT to an existing dump'; exit 1)
	@set -eu; name=evinsights_restore_$$$$; docker compose exec -T db createdb -U evinsights $$name; trap 'docker compose exec -T db dropdb -U evinsights "$$name"' EXIT; docker compose exec -T db psql -v ON_ERROR_STOP=1 -U evinsights -d $$name -c 'CREATE EXTENSION IF NOT EXISTS timescaledb; SELECT timescaledb_pre_restore();'; docker compose exec -T db pg_restore -U evinsights --exit-on-error -d $$name < "$(OUT)"; docker compose exec -T db psql -v ON_ERROR_STOP=1 -U evinsights -d $$name -c 'SELECT timescaledb_post_restore(); SELECT count(*) AS raw_samples FROM telemetry_samples'
