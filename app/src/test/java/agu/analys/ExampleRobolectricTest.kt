package agu.analys

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {
  @Test
  fun `basic test`() {
    val context: Context = RuntimeEnvironment.getApplication()
    assert(context != null)
    assertEquals(4, 2 + 2)
  }
}
