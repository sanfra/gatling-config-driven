package com.example

import com.example.config.{ScenarioConfig, TestConfig}
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

// All load parameters come from YAML — no hardcoded numbers here.
// Run examples:
//   mvn gatling:test
//   mvn gatling:test -Denv=staging
//   mvn gatling:test -Denv=staging -Dload.plateau.duration=300
//   mvn gatling:test -Dload.rampUp.toUsers=10 -Dload.plateau.duration=60 -Dload.rampDown.fromUsers=10
class PerformanceSimulation extends Simulation {

  private val cfg = TestConfig.load()

  private val httpProtocol = {
    val base = http
      .baseUrl(cfg.http.baseUrl)
      .maxRedirects(cfg.http.maxRedirects)

    cfg.http.headers.foldLeft(base) { case (proto, (name, value)) =>
      proto.header(name, value)
    }
  }

  private def buildHelloScenario(sc: ScenarioConfig) =
    scenario(sc.name)
      .exec(
        http("hello")
          .get("/api/hello")
          .check(status.is(200))
          .check(header("Content-Type").contains("application/json"))
          .check(bodyString.contains("hello"))
          .check(jsonPath("$.requestId").saveAs("requestId"))
          .check(responseTimeInMillis.lte(cfg.assertions.scenarioP95(sc.name)))
      )
      .pause(sc.pause.min.milliseconds, sc.pause.max.milliseconds)

  private def buildWorkScenario(sc: ScenarioConfig) =
    scenario(sc.name)
      .exec(
        http("work")
          .get("/api/work")
          .queryParam("delay", sc.paramDouble("delay", 0.2).toString)
          .header("X-Correlation-Id", "#{requestId}")
          .check(status.is(200))
          .check(jsonPath("$.result").exists)
          .check(responseTimeInMillis.lte(cfg.assertions.scenarioP95(sc.name)))
      )
      .pause(sc.pause.min.milliseconds, sc.pause.max.milliseconds)

  // /api/fail always returns 500 — success here means the endpoint is up
  private def buildErrorScenario(sc: ScenarioConfig) =
    scenario(sc.name)
      .exec(
        http("fail-endpoint")
          .get("/api/fail")
          .check(status.is(500))
          .check(jsonPath("$.error").exists)
      )
      .pause(sc.pause.min.milliseconds, sc.pause.max.milliseconds)

  // Add new scenarios here — wire them in this match
  private def buildScenario(sc: ScenarioConfig) = sc.name match {
    case "hello" => buildHelloScenario(sc)
    case "work"  => buildWorkScenario(sc)
    case "error" => buildErrorScenario(sc)
    case other   => throw new IllegalArgumentException(
      s"Unknown scenario: '$other'. Add it to PerformanceSimulation.buildScenario()"
    )
  }

  // Each scenario gets users proportional to its weight.
  // Rates are expressed as users/sec, derived from plateau.users.
  private def buildInjection(sc: ScenarioConfig) = {
    val load   = cfg.load
    val weight = sc.weight / 100.0

    val warmupRps = Math.max(1.0, load.warmup.users  * weight / 10.0)
    val peakRps   = Math.max(1.0, load.plateau.users * weight / 10.0)
    val fromRps   = Math.max(1.0, load.rampUp.fromUsers * weight / 10.0)

    buildScenario(sc).inject(
      constantUsersPerSec(warmupRps).during(load.warmup.duration.seconds),
      rampUsersPerSec(fromRps).to(peakRps).during(load.rampUp.duration.seconds),
      constantUsersPerSec(peakRps).during(load.plateau.duration.seconds),
      rampUsersPerSec(peakRps).to(0).during(load.rampDown.duration.seconds)
    )
  }

  private val activeScenarios = cfg.scenarios
    .filter(_.enabled)
    .map(buildInjection)

  if (activeScenarios.isEmpty)
    throw new IllegalStateException("No active scenarios found in configuration!")

  setUp(activeScenarios: _*)
    .protocols(httpProtocol)
    .assertions(buildAssertions(): _*)

  private def buildAssertions() = {
    val a = cfg.assertions

    val global = Seq(
      assertions.global.responseTime.mean.lt(a.meanRtMaxMs),
      assertions.global.responseTime.percentile(95).lt(a.p95RtMaxMs),
      assertions.global.responseTime.percentile(99).lt(a.p99RtMaxMs),
      assertions.global.responseTime.max.lt(a.maxRtMaxMs),
      assertions.global.successfulRequests.percent.gt(a.minSuccessPercent.toDouble)
    )

    val perScenario = cfg.scenarios
      .filter(sc => sc.enabled && a.perScenario.contains(sc.name))
      .flatMap { sc =>
        val scenAssert = a.perScenario(sc.name)
        val p95Assert  = assertions.details(sc.name).responseTime.percentile(95)
          .lt(scenAssert.p95RtMaxMs)

        val successAssert = scenAssert.successPercent.map { pct =>
          assertions.details(sc.name).successfulRequests.percent.is(pct.toDouble)
        }

        Seq(p95Assert) ++ successAssert.toSeq
      }

    global ++ perScenario
  }
}
