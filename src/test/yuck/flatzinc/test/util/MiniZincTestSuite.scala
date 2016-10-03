package yuck.flatzinc.test.util

import scala.collection._
import scala.math._

import org.junit._

import yuck.annealing._
import yuck.core._
import yuck.flatzinc.compiler.InconsistentProblemException
import yuck.flatzinc.parser._
import yuck.flatzinc.runner._
import yuck.util.testing.IntegrationTest
import yuck.util.arm.using

/**
 * @author Michael Marte
 *
 */
class MiniZincTestSuite extends IntegrationTest {

    def solve(task: MiniZincTestTask): Result = {
        logger.setThresholdLogLevel(task.logLevel)
        try {
            trySolve(task)
        }
        catch {
            case error: Throwable => handleException(findUltimateCause(error))
        }
    }

    private def trySolve(task: MiniZincTestTask): Result = {
        val timeStamp = new java.text.SimpleDateFormat("yyyy-MM-dd-HH:mm:ss").format(new java.util.Date);
        val suiteName = if (task.suiteName.isEmpty) new java.io.File(task.relativeSuitePath).getName else task.suiteName
        val (fznFilePath, logFilePath) = task.directoryLayout match {
            case MiniZincExamplesLayout =>
                ("%s/models/%s.fzn".format(task.relativeSuitePath, task.problemName),
                 "tmp/%s-%s.log".format(task.problemName, timeStamp))
            case StandardMiniZincChallengeLayout | NonStandardMiniZincChallengeLayout =>
                ("%s/models/%s/%s.fzn".format(task.relativeSuitePath, task.problemName, task.instanceName),
                 "tmp/%s-%s-%s-%s.log".format(suiteName, task.problemName, task.instanceName, timeStamp))
            case MiniZincBenchmarksLayout => {
                val modelName = if (task.modelName.isEmpty) task.problemName else task.modelName
                ("%s/models/%s/%s/%s.fzn".format(task.relativeSuitePath, task.problemName, modelName, task.instanceName),
                 "tmp/%s-%s-%s-%s.log".format(suiteName, task.problemName, task.instanceName.replace('/', '-'), timeStamp))
            }
        }
        val logFileHandler = new java.util.logging.FileHandler(logFilePath)
        logFileHandler.setFormatter(formatter)
        nativeLogger.addHandler(logFileHandler)
        logger.log("Processing %s".format(fznFilePath))
        logger.log("Logging into %s".format(logFilePath))
        val file = new java.io.File(fznFilePath)
        val reader = new java.io.InputStreamReader(new java.io.FileInputStream(file))
        val ast = logger.withTimedLogScope("Parsing FlatZinc file")(FlatZincParser.parse(reader))
        logger.withRootLogLevel(yuck.util.logging.FineLogLevel) {
            logger.withLogScope("AST statistics") {
                logger.log("%d predicate declarations".format(ast.predDecls.size))
                logger.log("%d parameter declarations".format(ast.paramDecls.size))
                logger.log("%d variable declarations".format(ast.varDecls.size))
                logger.log("%d constraints".format(ast.constraints.size))
            }
        }
        val cfg =
            task.solverConfiguration.copy(
                maybeOptimum = task.maybeOptimum,
                maybeQualityTolerance = task.maybeQualityTolerance)
        val result =
            using(new StandardAnnealingMonitor(logger))(
                monitor => new FlatZincSolverGenerator(ast, cfg, logger, monitor).call.call
            )
        Assert.assertNotNull("Solver returned no proposal", result)
        logger.log("Quality of best proposal: %s".format(result.costsOfBestProposal))
        logger.log("Best proposal was produced by: %s".format(result.solverName))
        logger.withLogScope("%s statistics".format(result.solverName)) {
            if (result.isInstanceOf[AnnealingResult]) {
                val annealingResult = result.asInstanceOf[AnnealingResult]
                logger.log("Number of rounds: %d".format(annealingResult.roundLogs.size))
                if (annealingResult.roundLogs.size > 0) {
                    logger.log("Moves per second: %d".format(annealingResult.movesPerSecond))
                    logger.log("Consultations per second: %d".format(annealingResult.consultationsPerSecond))
                    logger.log("Consultations per move: %d".format(annealingResult.consultationsPerMove))
                    logger.log("Commitments per second: %d".format(annealingResult.commitmentsPerSecond))
                    logger.log("Commitments per move: %d".format(annealingResult.commitmentsPerMove))
                }
            }
        }
        if (! result.isSolution) {
            logger.withRootLogLevel(yuck.util.logging.FinerLogLevel) {
                logger.withLogScope("Violated constraints") {
                    logViolatedConstraints(result)
                }
            }
        }
        logger.withLogScope("Best proposal") {
            new FlatZincResultFormatter(result).call.foreach(logger.log(_))
        }
        Assert.assertTrue(
            "No solution found, quality of best proposal was %s".format(result.costsOfBestProposal),
            result.isSolution)
        logger.withTimedLogScope("Verifying solution") {
            Assert.assertTrue(
                "Solution not verified",
                new MiniZincSolutionVerifier(task, result, timeStamp, logger).call)
        }
        result
    }

    private def logViolatedConstraints(result: Result) {
        val visited = new mutable.HashSet[AnyVariable]
        result.space.definingConstraint(result.objective.topLevelGoalVariable).get match {
            case sum: yuck.constraints.Sum[IntegerValue @ unchecked] =>
                for (x <- sum.xs if result.space.searchState.value(x) > Zero) {
                    logViolatedConstraints(result, x, visited)
                }
        }
    }

    private def logViolatedConstraints(
        result: Result, x: AnyVariable, visited: mutable.Set[AnyVariable])
    {
        val a = result.bestProposal.anyValue(x)
        if (! visited.contains(x)) {
            visited += x
            val maybeConstraint = result.space.definingConstraint(x)
            if (maybeConstraint.isDefined) {
                val constraint = maybeConstraint.get
                logger.withLogScope("%s = %s computed by %s [%s]".format(x, a, constraint, constraint.goal)) {
                    for (x <- constraint.inVariables) {
                        logViolatedConstraints(result, x, visited)
                    }
                }
             } else if (! x.isParameter) {
                logger.logg("%s = %s".format(x, a, visited))
            }
        }
    }

    private def handleException(error: Throwable): Result = error match {
        case error: FlatZincParserException =>
            nativeLogger.info(error.getMessage)
            throw error
        case error: InconsistentProblemException =>
            nativeLogger.info(error.getMessage)
            nativeLogger.info(FLATZINC_INCONSISTENT_PROBLEM_INDICATOR)
            throw error
        case error: Throwable =>
            nativeLogger.log(java.util.logging.Level.SEVERE, "", error)
            throw error
    }

    private def findUltimateCause(error: Throwable): Throwable =
        if (error.getCause == null) error else findUltimateCause(error.getCause)

}