package stochastacy.core.stats

/** A keyed collection of [[Statistic]]s — the shape a simulator's runner folds its observations into.
 *  Generic over the key `K` (a simulator picks its own, e.g. `(usecase, metric)`). `combine` merges
 *  key-wise, so trial results aggregate by folding (Slice 7). */
final case class Statistics[K](byKey: Map[K, Statistic]):

  def observe(key: K, value: Double): Statistics[K] =
    Statistics(byKey.updatedWith(key)(s => Some(s.getOrElse(Statistic.empty).observe(value))))

  def get(key: K): Option[Statistic] = byKey.get(key)

  def keys: Iterable[K] = byKey.keys

  def combine(other: Statistics[K]): Statistics[K] =
    Statistics(other.byKey.foldLeft(byKey) { case (acc, (k, s)) =>
      acc.updatedWith(k)(cur => Some(cur.map(_.combine(s)).getOrElse(s)))
    })

object Statistics:
  def empty[K]: Statistics[K] = Statistics(Map.empty[K, Statistic])
