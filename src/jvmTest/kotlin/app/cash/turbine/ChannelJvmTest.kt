package app.cash.turbine

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelJvmTest {
  @Test
  fun takeItemSuspendingThrows() = runTest {
    val actual = assertFailsWith<IllegalStateException> {
      emptyChannel().takeItem()
    }
    assertEquals("Calling context is suspending; use a suspending method instead", actual.message)
  }
}
