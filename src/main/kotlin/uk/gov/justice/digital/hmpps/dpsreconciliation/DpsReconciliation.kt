package uk.gov.justice.digital.hmpps.dpsreconciliation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class DpsReconciliation

fun main(args: Array<String>) {
  runApplication<DpsReconciliation>(*args)
}
