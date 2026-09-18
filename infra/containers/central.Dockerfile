# OCI image for the central OTP release (Section 19.1).
#
# Section 21.4 installs the configured agent runtime separately, so no runtime
# vendor is baked in here; Section 31 loads every secret at runtime, so none is
# embedded either.

ARG ELIXIR_IMAGE=hexpm/elixir:1.19.6-erlang-28.3.3-ubuntu-noble-20260911
ARG RUNNER_IMAGE=ubuntu:noble-20260911

# ---------------------------------------------------------------------------
# dev — the Compose target. Mounts the source and runs the Phoenix server.
# ---------------------------------------------------------------------------
FROM ${ELIXIR_IMAGE} AS dev

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential git curl ca-certificates inotify-tools \
    && rm -rf /var/lib/apt/lists/*

RUN mix local.hex --force && mix local.rebar --force

WORKDIR /app
ENV MIX_ENV=dev

COPY central/mix.exs central/mix.lock ./
RUN mix deps.get && mix deps.compile

COPY central ./

CMD ["mix", "phx.server"]

# ---------------------------------------------------------------------------
# builder — compiles the production release.
# ---------------------------------------------------------------------------
FROM ${ELIXIR_IMAGE} AS builder

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mix local.hex --force && mix local.rebar --force

WORKDIR /app
ENV MIX_ENV=prod

COPY central/mix.exs central/mix.lock ./
RUN mix deps.get --only prod && mix deps.compile

COPY central/config config
COPY central/priv priv
COPY central/lib lib

RUN mix compile --warnings-as-errors
RUN mix phx.digest
RUN mix release --overwrite

# ---------------------------------------------------------------------------
# runtime — the shipped image.
# ---------------------------------------------------------------------------
FROM ${RUNNER_IMAGE} AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
      libstdc++6 openssl libncurses6 locales ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8

ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en LC_ALL=en_US.UTF-8 MIX_ENV=prod

# Section 13 and 23.4: the release runs unprivileged, so a compromised process
# has no host-level capability to escalate into.
RUN useradd --create-home --shell /usr/sbin/nologin dispatch
WORKDIR /app
USER dispatch

COPY --from=builder --chown=dispatch:dispatch /app/_build/prod/rel/dispatch ./

EXPOSE 4000

# Section 32: liveness is process-only, so a slow dependency cannot cause a
# restart loop.
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:4000/health/live || exit 1

# Section 22 forbids auto-migration on boot; migrations are a deliberate step.
CMD ["/app/bin/dispatch", "start"]
