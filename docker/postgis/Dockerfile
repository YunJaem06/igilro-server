# Official PostgreSQL 17.11 index includes linux/arm64 and linux/amd64.
# Pin the multi-architecture index, rather than a single architecture manifest.
FROM postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-17-postgis-3 postgresql-17-postgis-3-scripts \
    && rm -rf /var/lib/apt/lists/*

# No init SQL: Flyway owns extension creation and schema changes.
