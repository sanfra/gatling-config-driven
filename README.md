# gatling-config-driven

A Gatling 3 simulation with no hardcoded numbers. Everything — users, durations, thresholds, HTTP headers — comes from YAML. Three layers of override let you run the same codebase against local, staging, and production without touching Scala.

## Why

The default pattern in most Gatling projects is to change a constant in the simulation class, recompile, and re-run. This breaks down fast when you have multiple environments or CI pipelines that need different load profiles and different pass/fail thresholds. YAML config solves that — the code stays frozen, the data changes.

## Prerequisites

- Java 17+
- Maven 3.8+

## Quick start

    mvn gatling:test

Reads `test-config.yml` from the classpath, runs against `http://localhost:8000`.

## Environments

    mvn gatling:test -Denv=staging
    mvn gatling:test -Denv=prod

Loads `test-config-staging.yml` or `test-config-prod.yml` on top of the base file. The override file only needs the fields that differ — everything else is inherited. Merge is deep: nested sections are combined field by field, not replaced wholesale.

## One-off overrides

Any YAML field can be overridden from the command line using dot-notation:

    mvn gatling:test -Denv=staging -Dload.plateau.duration=120

    mvn gatling:test \
      -Dload.rampUp.toUsers=20 \
      -Dload.plateau.users=20 \
      -Dload.plateau.duration=60 \
      -Dload.rampDown.fromUsers=20

CLI overrides have the highest priority. Useful for quick smoke tests and CI parameter injection without creating a new config file.

## Project structure

    src/test/
    ├── resources/
    │   ├── test-config.yml             # base defaults
    │   ├── test-config-staging.yml     # staging overrides
    │   └── test-config-prod.yml        # production smoke-test overrides
    └── scala/com/example/
        ├── PerformanceSimulation.scala # simulation — reads cfg.* everywhere
        └── config/
            ├── TestConfig.scala        # loader: YAML → deep merge → -D overrides → validate
            └── SimulationConfig.scala  # typed case classes mirroring the YAML structure

## Config file structure

```yaml
http:
  baseUrl: "http://localhost:8000"
  headers:
    Accept: "application/json"
  connectTimeout:  5000
  responseTimeout: 10000

load:
  warmup:
    users:    10
    duration: 20        # seconds
  rampUp:
    fromUsers: 10       # must match warmup.users
    toUsers:   500
    duration:  300
  plateau:
    users:    500       # must match rampUp.toUsers
    duration: 600
  rampDown:
    fromUsers: 500      # must match plateau.users
    toUsers:   0
    duration:  300

scenarios:
  - name:    "hello"
    enabled: true
    weight:  71         # weights of enabled scenarios must sum to 100
    pause:
      min:   800        # ms
      max:   1200
  - name:    "work"
    enabled: true
    weight:  24
    params:
      delay: "0.2"      # scenario-specific params
  - name:    "error"
    enabled: true
    weight:  5

assertions:
  meanRtMaxMs:       500
  p95RtMaxMs:        1000
  p99RtMaxMs:        2000
  maxRtMaxMs:        5000
  minSuccessPercent: 95
  perScenario:              # optional per-scenario overrides
    hello:
      p95RtMaxMs: 500
    error:
      successPercent: 100   # /api/fail always responds — even as 500

metadata:
  environment: "local"
  version:     "dev"
  branch:      "feature"
```

The loader validates three consistency rules at startup and throws with a clear message if any fails:

- `plateau.users` must equal `rampUp.toUsers`
- `rampDown.fromUsers` must equal `plateau.users`
- Weights of enabled scenarios must sum to 100

## Adding a scenario

1. Add the entry in `test-config.yml` (adjust weights to keep the sum at 100):

```yaml
scenarios:
  - name:    "login"
    enabled: true
    weight:  15
    params:
      username: "testuser"
```

2. Add the builder in `PerformanceSimulation.scala`:

```scala
private def buildLoginScenario(sc: ScenarioConfig) =
  scenario(sc.name)
    .exec(http("login").post("/api/auth/login")
      .check(status.is(200))
      .check(jsonPath("$.token").saveAs("authToken")))
    .pause(sc.pause.min.milliseconds, sc.pause.max.milliseconds)
```

3. Wire it in `buildScenario`:

```scala
case "login" => buildLoginScenario(sc)
```

No other changes needed.

## CI/CD

GitHub Actions:

```yaml
- name: Run performance test
  env:
    VERSION: ${{ github.ref_name }}
    BRANCH:  ${{ github.ref_name }}
  run: |
    mvn gatling:test \
      -Denv=staging \
      -Dmetadata.version=$VERSION \
      -Dmetadata.branch=$BRANCH
```

## License

MIT — see [LICENSE](LICENSE).
