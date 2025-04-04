package uk.gov.justice.digital.hmpps.dpsreconciliation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DpsReconciliation

fun main(args: Array<String>) {
  runApplication<DpsReconciliation>(*args)
}
