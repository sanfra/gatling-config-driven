package com.example.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.io.{File, InputStream}
import scala.util.{Try, Using}

// Loads simulation config from YAML with three-level priority:
//   1. test-config.yml          (always loaded)
//   2. test-config-<env>.yml    (activated with -Denv=staging)
//   3. System properties -D     (highest priority, override anything)
object TestConfig {

  private val mapper = new ObjectMapper(new YAMLFactory())
    .registerModule(DefaultScalaModule)

  def load(): SimulationConfig = {
    val env       = sys.props.getOrElse("env", "")
    val overrides = collectSystemOverrides()
    load(env, overrides)
  }

  def load(env: String = "", overrides: Map[String, String] = Map.empty): SimulationConfig = {
    val base = loadFile("test-config.yml")

    val withEnv = if (env.nonEmpty) {
      val envFile = s"test-config-$env.yml"
      loadFileOptional(envFile) match {
        case Some(envConfig) =>
          println(s"[Config] Loaded environment override: $envFile")
          deepMerge(base, envConfig)
        case None =>
          println(s"[Config] No environment file found for: $envFile — using defaults")
          base
      }
    } else base

    val withOverrides = applyOverrides(withEnv, overrides)
    val config        = mapper.convertValue(withOverrides, classOf[SimulationConfig])
    validate(config)
    printSummary(config, env)
    config
  }

  private def loadFile(name: String): java.util.Map[String, Any] =
    getStream(name)
      .map(s => Using(s)(mapper.readValue(_, classOf[java.util.Map[String, Any]])).get)
      .getOrElse(throw new IllegalArgumentException(s"Config file not found: $name"))

  private def loadFileOptional(name: String): Option[java.util.Map[String, Any]] =
    getStream(name).map(s => Using(s)(mapper.readValue(_, classOf[java.util.Map[String, Any]])).get)

  private def getStream(name: String): Option[InputStream] =
    Option(getClass.getClassLoader.getResourceAsStream(name))
      .orElse { val f = new File(name);                         if (f.exists()) Some(new java.io.FileInputStream(f)) else None }
      .orElse { val f = new File(s"src/test/resources/$name");  if (f.exists()) Some(new java.io.FileInputStream(f)) else None }

  // Recursive deep merge — override wins field by field, not section by section
  private def deepMerge(
    base:      java.util.Map[String, Any],
    override_ : java.util.Map[String, Any]
  ): java.util.Map[String, Any] = {
    val result = new java.util.LinkedHashMap[String, Any](base)
    override_.forEach { (key, value) =>
      (result.get(key), value) match {
        case (bm: java.util.Map[_, _], om: java.util.Map[_, _]) =>
          result.put(key, deepMerge(
            bm.asInstanceOf[java.util.Map[String, Any]],
            om.asInstanceOf[java.util.Map[String, Any]]
          ))
        case _ => result.put(key, value)
      }
    }
    result
  }

  private def collectSystemOverrides(): Map[String, String] = {
    // "scenarios." excluded — List fields can't be overridden via -D dot-notation
    val knownPrefixes = Set("load.", "assertions.", "http.", "metadata.")
    sys.props.filter { case (k, _) => knownPrefixes.exists(k.startsWith) }.toMap
  }

  private def applyOverrides(
    config:    java.util.Map[String, Any],
    overrides: Map[String, String]
  ): java.util.Map[String, Any] = {
    if (overrides.isEmpty) return config
    val result = new java.util.LinkedHashMap[String, Any](config)
    overrides.foreach { case (path, value) =>
      setNested(result, path.split("\\."), value)
      println(s"[Config] Override: $path = $value")
    }
    result
  }

  // Preserves original field type when applying string overrides
  private def setNested(map: java.util.Map[String, Any], path: Array[String], value: String): Unit =
    if (path.length == 1) {
      map.get(path(0)) match {
        case _: Integer | _: java.lang.Long => map.put(path(0), value.toInt)
        case _: java.lang.Double            => map.put(path(0), value.toDouble)
        case _: java.lang.Boolean           => map.put(path(0), value.toBoolean)
        case _                              => map.put(path(0), value)
      }
    } else {
      map.get(path(0)) match {
        case nested: java.util.Map[_, _] =>
          setNested(nested.asInstanceOf[java.util.Map[String, Any]], path.tail, value)
        case _ =>
      }
    }

  private def validate(c: SimulationConfig): Unit = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    if (c.load.plateau.users != c.load.rampUp.toUsers)
      errors += s"load.plateau.users (${c.load.plateau.users}) != load.rampUp.toUsers (${c.load.rampUp.toUsers})"

    if (c.load.rampDown.fromUsers != c.load.plateau.users)
      errors += s"load.rampDown.fromUsers (${c.load.rampDown.fromUsers}) != load.plateau.users (${c.load.plateau.users})"

    val activeWeight = c.scenarios.filter(_.enabled).map(_.weight).sum
    if (c.scenarios.exists(_.enabled) && activeWeight != 100)
      errors += s"Sum of enabled scenario weights = $activeWeight (must be 100)"

    if (errors.nonEmpty) {
      errors.foreach(e => println(s"[Config] ERROR: $e"))
      throw new IllegalArgumentException(s"Invalid configuration: ${errors.mkString("; ")}")
    }
  }

  private def printSummary(c: SimulationConfig, env: String): Unit = {
    val total = c.load.totalDurationSeconds
    println(
      s"""
         |╔══════════════════════════════════════════════════╗
         |║  Gatling Test Configuration                      ║
         |╠══════════════════════════════════════════════════╣
         |║  Environment : ${pad(env.ifEmpty("local"), 32)}  ║
         |║  Base URL    : ${pad(c.http.baseUrl, 32)}  ║
         |╠══════════════════════════════════════════════════╣
         |║  Load Profile                                    ║
         |║    Warmup    : ${pad(s"${c.load.warmup.users} users × ${c.load.warmup.duration}s", 32)}  ║
         |║    Ramp up   : ${pad(s"${c.load.rampUp.fromUsers}→${c.load.rampUp.toUsers} users in ${c.load.rampUp.duration}s", 32)}  ║
         |║    Plateau   : ${pad(s"${c.load.plateau.users} users × ${c.load.plateau.duration}s", 32)}  ║
         |║    Ramp down : ${pad(s"${c.load.rampDown.fromUsers}→${c.load.rampDown.toUsers} users in ${c.load.rampDown.duration}s", 32)}  ║
         |║    Total     : ${pad(s"~${total / 60} min ${total % 60} sec", 32)}  ║
         |╠══════════════════════════════════════════════════╣
         |║  Assertions                                      ║
         |║    Mean RT   : < ${pad(s"${c.assertions.meanRtMaxMs}ms", 30)}  ║
         |║    p95 RT    : < ${pad(s"${c.assertions.p95RtMaxMs}ms", 30)}  ║
         |║    p99 RT    : < ${pad(s"${c.assertions.p99RtMaxMs}ms", 30)}  ║
         |║    Success % : > ${pad(s"${c.assertions.minSuccessPercent}%", 30)}  ║
         |╠══════════════════════════════════════════════════╣
         |║  Active Scenarios                                ║
         |${c.scenarios.filter(_.enabled).map(s => s"║    ${pad(s"${s.name} (${s.weight}%)", 46)}  ║").mkString("\n")}
         |╚══════════════════════════════════════════════════╝
         |""".stripMargin
    )
  }

  private def pad(s: String, n: Int): String = s.padTo(n, ' ')

  implicit class StringOps(s: String) {
    def ifEmpty(default: String): String = if (s.isEmpty) default else s
  }
}
