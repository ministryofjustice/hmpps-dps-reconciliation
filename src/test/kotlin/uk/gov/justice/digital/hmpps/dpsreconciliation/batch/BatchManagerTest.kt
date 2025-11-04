package uk.gov.justice.digital.hmpps.dpsreconciliation.batch

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextRefreshedEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService

class BatchManagerTest {

  private val receiveService = mock<ReceiveService>()
  private val event = mock<ContextRefreshedEvent>()
  private val context = mock<ConfigurableApplicationContext>()

  @BeforeEach
  fun setUp() {
    whenever(event.applicationContext).thenReturn(context)
  }

  @Test
  fun `should call the detect non matches service`() = runTest {
    val batchManager = batchManager(BatchType.DETECT_NON_MATCHES)

    batchManager.onApplicationEvent(event)

    verify(receiveService).detectNonMatches(any(), any())
    verify(context).close()
  }

  private fun batchManager(batchType: BatchType) = BatchManager(
    batchType,
    receiveService,
  )
}
