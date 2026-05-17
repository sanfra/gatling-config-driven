package com.example.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// @JsonIgnoreProperties allows adding new YAML fields without breaking compilation
@JsonIgnoreProperties(ignoreUnknown = true)
case class SimulationConfig(
  http:       HttpConfig,
  load:       LoadConfig,
  scenarios:  List[ScenarioConfig],
  assertions: AssertionConfig,
  metadata:   MetadataConfig
)

@JsonIgnoreProperties(ignoreUnknown = true)
case class HttpConfig(
  baseUrl:         String,
  headers:         Map[String, String] = Map.empty,
  connectTimeout:  Int                 = 5000,
  responseTimeout: Int                 = 10000,
  maxRedirects:    Int                 = 5
)

@JsonIgnoreProperties(ignoreUnknown = true)
case class LoadConfig(
  warmup:   WarmupConfig,
  rampUp:   RampConfig,
  plateau:  PlateauConfig,
  rampDown: RampDownConfig
) {
  def totalDurationSeconds: Int =
    warmup.duration + rampUp.duration + plateau.duration + rampDown.duration
}

@JsonIgnoreProperties(ignoreUnknown = true)
case class WarmupConfig(users: Int, duration: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
case class RampConfig(fromUsers: Int, toUsers: Int, duration: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
case class PlateauConfig(users: Int, duration: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
case class RampDownConfig(fromUsers: Int, toUsers: Int, duration: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
case class ScenarioConfig(
  name:    String,
  enabled: Boolean             = true,
  weight:  Int                 = 0,
  pause:   PauseConfig         = PauseConfig(800, 1200),
  params:  Map[String, String] = Map.empty
) {
  def param(key: String, default: String = ""): String       = params.getOrElse(key, default)
  def paramDouble(key: String, default: Double = 0.0): Double = params.get(key).map(_.toDouble).getOrElse(default)
}

@JsonIgnoreProperties(ignoreUnknown = true)
case class PauseConfig(min: Int, max: Int) // ms

@JsonIgnoreProperties(ignoreUnknown = true)
case class AssertionConfig(
  meanRtMaxMs:       Int                                    = 500,
  p95RtMaxMs:        Int                                    = 1000,
  p99RtMaxMs:        Int                                    = 2000,
  maxRtMaxMs:        Int                                    = 5000,
  minSuccessPercent: Int                                    = 95,
  perScenario:       Map[String, ScenarioAssertionConfig]   = Map.empty
) {
  def scenarioP95(name: String): Int =
    perScenario.get(name).map(_.p95RtMaxMs).getOrElse(p95RtMaxMs)
}

@JsonIgnoreProperties(ignoreUnknown = true)
case class ScenarioAssertionConfig(
  p95RtMaxMs:     Int         = 1000,
  successPercent: Option[Int] = None
)

@JsonIgnoreProperties(ignoreUnknown = true)
case class MetadataConfig(
  environment: String = "local",
  version:     String = "unknown",
  branch:      String = "unknown"
)
