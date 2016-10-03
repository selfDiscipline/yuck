package yuck.annealing

import scala.collection._
import scala.math._

import yuck.core._

/**
 * Generates random moves of random size.
 *
 * For each chosen variable, a value that differs from its current value is
 * randomly chosen from its domain.
 *
 * Choosing the number of variables involved in a move is guided by the given
 * move-size distribution.
 *
 * Variable selection can happen in two ways:
 * In fair mode, all variables are equally likely to occur in a move while
 * in unfair mode the selection probability may be skewed in some way.
 *
 * To facilitate unfair choice, a so-called hot-spot distribution has to be given.
 *
 * With unfair choice enabled, the probability of fair choice comes into play.
 *
 * Falls back to fair mode when the given hot-spot distribution has zero volume.
 *
 * @author Michael Marte
 */
final class RandomReassignmentGenerator
    (space: Space,
     override val xs: immutable.IndexedSeq[AnyVariable],
     randomGenerator: RandomGenerator,
     moveSizeDistribution: Distribution,
     hotSpotDistribution: Distribution,
     probabilityOfFairChoiceInPercent: Int)
    extends MoveGenerator
{

    private val n = xs.size
    require(n > 0)
    for (i <- 0 until n) {
        require(! xs(i).isParameter)
    }
    require(moveSizeDistribution.frequency(0) == 0)
    require(moveSizeDistribution.volume > 0)
    require((0 to 100).contains(probabilityOfFairChoiceInPercent))
    private val uniformDistribution = DistributionFactory.createDistribution(n)
    (0 until n).foreach(i => uniformDistribution.setFrequency(i, 1))
    require(uniformDistribution.volume > 0)
    private val s = moveSizeDistribution.size
    private val effectsByMoveSize = for (n <- 1 until s) yield new Array[AnyEffect](n)
    private val frequencyRestorers = for (i <- 1 until s) yield new FrequencyRestorer
    @inline private def fillEffect(effects: Array[AnyEffect], i: Int, x: AnyVariable) {
        effects.update(i, x.nextRandomEffect(space, randomGenerator))
    }

    override def nextMove = {
        val useUniformDistribution =
            hotSpotDistribution == null ||
            hotSpotDistribution.volume == 0 ||
            probabilityOfFairChoiceInPercent == 100 ||
            (probabilityOfFairChoiceInPercent > 0 && randomGenerator.nextInt(100) < probabilityOfFairChoiceInPercent)
        val priorityDistribution = if (useUniformDistribution) uniformDistribution else hotSpotDistribution
        val m =
            scala.math.min(
                moveSizeDistribution.nextIndex(randomGenerator),
                priorityDistribution.numberOfAlternatives)
        assert(m > 0)
        val effects = effectsByMoveSize(m - 1)
        var i = 0
        if (useUniformDistribution && m < 4) {
            val i = randomGenerator.nextInt(n)
            fillEffect(effects, 0, xs(i))
            if (m > 1) {
                val j = {
                    val k = randomGenerator.nextInt(n - 1)
                    if (k < i) k else k + 1
                }
                fillEffect(effects, 1, xs(j))
                if (m > 2) {
                    val k = {
                        var l = randomGenerator.nextInt(n - 2)
                        if (l < min(i, j)) l else if (l > max(i, j) - 2) l + 2 else l + 1
                    }
                    fillEffect(effects, 2, xs(k))
                }
            }
        } else {
            while (i < m && priorityDistribution.volume > 0) {
                val j = priorityDistribution.nextIndex(randomGenerator)
                fillEffect(effects, i, xs(j))
                if (i < m - 1) {
                    frequencyRestorers(i).store(j, priorityDistribution.frequency(j))
                    priorityDistribution.setFrequency(j, 0)
                }
                i += 1
            }
            if (m > 1) {
                i = 0
                while (i < m - 1) {
                    frequencyRestorers(i).restore(priorityDistribution)
                    i += 1
                }
            }
        }
        val result = new ChangeAnyValues(space.moveIdFactory.nextId, effects)
        result
    }

}