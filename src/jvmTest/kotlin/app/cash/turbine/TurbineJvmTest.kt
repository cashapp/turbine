package app.cash.turbine

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TurbineJvmTest {
  @Test
  fun takeItemSuspendingThrows() = runTest {
    val actual = assertFailsWith<IllegalStateException> {
      val channel = Turbine<Any>()
      channel.cancel()
      channel.takeItem()
    }
    assertEquals("Calling context is suspending; use a suspending method instead", actual.message)
  }
}
