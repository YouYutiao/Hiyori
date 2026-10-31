package eu.kanade.tachiyomi.util.system

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProcessCpuSamplerTest {

    private class Clocks {
        var cpuMillis = 0L
        var nanos = 0L
    }

    private fun sampler(clocks: Clocks, cores: Int = 4) = ProcessCpuSampler(
        cores = cores,
        cpuMillis = { clocks.cpuMillis },
        nanoTime = { clocks.nanos },
    )

    @Test
    fun `the first sample only primes the counter`() {
        val clocks = Clocks()
        clocks.cpuMillis = 1_000
        clocks.nanos = 5_000_000_000
        sampler(clocks).sample() shouldBe null
    }

    @Test
    fun `half of four cores for one second is thirteen percent`() {
        val clocks = Clocks()
        val sampler = sampler(clocks)
        sampler.sample()

        clocks.cpuMillis += 500
        clocks.nanos += 1_000_000_000
        // 0.5s of CPU over 1s of wall time on four cores = 12.5%, rounded.
        sampler.sample() shouldBe 13
    }

    @Test
    fun `usage is clamped when every core is busy`() {
        val clocks = Clocks()
        val sampler = sampler(clocks)
        sampler.sample()

        clocks.cpuMillis += 8_000 // 8s of CPU in 1s of wall time on 4 cores
        clocks.nanos += 1_000_000_000
        sampler.sample() shouldBe 100
    }

    @Test
    fun `a restarted counter is treated as a new baseline`() {
        val clocks = Clocks()
        val sampler = sampler(clocks)
        sampler.sample()
        clocks.cpuMillis = 400
        clocks.nanos += 1_000_000_000
        sampler.sample() shouldBe 10

        clocks.cpuMillis = 10 // process restarted, counter reset
        clocks.nanos += 1_000_000_000
        sampler.sample() shouldBe null
    }

    @Test
    fun `samples closer than the minimum interval are ignored`() {
        val clocks = Clocks()
        val sampler = sampler(clocks)
        sampler.sample()
        clocks.cpuMillis += 10
        clocks.nanos += 10_000_000 // 10ms
        sampler.sample() shouldBe null
    }
}
