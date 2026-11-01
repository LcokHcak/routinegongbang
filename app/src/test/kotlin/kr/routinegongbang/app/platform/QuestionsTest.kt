package kr.routinegongbang.app.platform

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "실행할까요?" notification never pops up while the app is in front (AAOS shows no heads-up for
 * the foreground app), so nobody could answer in time. The question is also shown in the app; the first answer
 * from either place counts.
 */
class QuestionsTest {

    private val content = ConfirmContent("추운 아침", listOf("핸들 열선 2단계"), deadlineMs = 10_000)

    @Test
    fun `the question is on screen until answered, and the answer comes back`() = runTest {
        val questions = Questions()
        val answer = async { questions.ask(content) }
        yield()
        val shown = questions.current.value!!
        assertEquals("추운 아침", shown.content.routineName)
        questions.answer(shown.id, yes = true)
        assertEquals(true, answer.await())
        assertNull(questions.current.value)
    }

    @Test
    fun `an answer to an older question is ignored`() = runTest {
        val questions = Questions()
        val first = async { questions.ask(content) }
        yield()
        val old = questions.current.value!!.id
        questions.answer(old, yes = false)
        assertEquals(false, first.await())
        val second = async { questions.ask(content) }
        yield()
        questions.answer(old, yes = true) // a late tap on the first notification
        assertEquals(questions.current.value?.id, old + 1)
        questions.answer(old + 1, yes = true)
        assertEquals(true, second.await())
    }

    @Test
    fun `a question the run stopped waiting for leaves the screen`() = runTest {
        val questions = Questions()
        val answer = async { questions.ask(content) }
        yield()
        answer.cancel()
        yield()
        assertNull(questions.current.value)
    }
}
