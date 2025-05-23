package uk.gov.justice.digital.hmpps.dpsreconciliation.integration

import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.IOException
import java.net.ServerSocket
import kotlin.apply
import kotlin.jvm.java

object PostgresContainer {
  val instance: PostgreSQLContainer<Nothing>? by lazy { startPostgresqlContainer() }
  fun startPostgresqlContainer(): PostgreSQLContainer<Nothing>? {
    if (isPostgresRunning()) {
      log.warn("Using existing Postgres database")
      return null
    }
    log.info("Creating a Postgres database")
    return PostgreSQLContainer<Nothing>("postgres").apply {
      withEnv("HOSTNAME_EXTERNAL", "localhost")
      withDatabaseName("reconciliation_db")
      withUsername("reconciliation")
      withPassword("reconciliation")
      setWaitStrategy(Wait.forListeningPort())
      withReuse(false)

      start()
    }
  }

  private fun isPostgresRunning(): Boolean = try {
    val serverSocket = ServerSocket(5432)
    serverSocket.localPort == 0
  } catch (_: IOException) {
    true
  }

  private val log = LoggerFactory.getLogger(this::class.java)
}
