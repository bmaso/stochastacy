package stochastacy.core.run

import org.apache.commons.rng.simple.RandomSource

/** Deterministic derivation of N independent per-trial seeds from one master seed — the RNG-seeding
 *  utility behind the Monte Carlo layer. `derive(master, n)` always yields the same vector, and trial
 *  `i` always maps to element `i`, so the trial set is reproducible independent of how trials are
 *  scheduled or parallelized.
 *
 *  Uses the same KISS-splitting idiom a single trial uses internally to fan a master seed out into
 *  per-stage RNGs; exposed so callers and tests can reproduce the exact seed set. */
object SeedSequence:

  def derive(masterSeed: Long, count: Int): Vector[Long] =
    val rng = RandomSource.KISS.create(masterSeed)
    Vector.fill(count)(rng.nextLong())
