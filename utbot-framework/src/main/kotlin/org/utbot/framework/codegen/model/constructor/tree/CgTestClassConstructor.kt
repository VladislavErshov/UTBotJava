package org.utbot.framework.codegen.model.constructor.tree

import org.utbot.common.appendHtmlLine
import org.utbot.engine.displayName
import org.utbot.framework.codegen.ParametrizedTestSource
import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.constructor.context.CgContextOwner
import org.utbot.framework.codegen.model.constructor.util.CgComponents
import org.utbot.framework.codegen.model.constructor.util.CgStatementConstructor
import org.utbot.framework.codegen.model.tree.CgMethod
import org.utbot.framework.codegen.model.tree.CgExecutableUnderTestCluster
import org.utbot.framework.codegen.model.tree.CgParameterDeclaration
import org.utbot.framework.codegen.model.tree.CgRegion
import org.utbot.framework.codegen.model.tree.CgSimpleRegion
import org.utbot.framework.codegen.model.tree.CgStaticsRegion
import org.utbot.framework.codegen.model.tree.CgTestClassFile
import org.utbot.framework.codegen.model.tree.CgTestMethod
import org.utbot.framework.codegen.model.tree.CgTestMethodCluster
import org.utbot.framework.codegen.model.tree.CgTestMethodType.*
import org.utbot.framework.codegen.model.tree.CgTripleSlashMultilineComment
import org.utbot.framework.codegen.model.tree.CgUtilMethod
import org.utbot.framework.codegen.model.tree.buildTestClass
import org.utbot.framework.codegen.model.tree.buildTestClassBody
import org.utbot.framework.codegen.model.tree.buildTestClassFile
import org.utbot.framework.codegen.model.visitor.importUtilMethodDependencies
import org.utbot.framework.plugin.api.CgMethodTestSet
import org.utbot.framework.plugin.api.ExecutableId
import org.utbot.framework.plugin.api.MethodId
import org.utbot.framework.plugin.api.UtMethodTestSet
import org.utbot.framework.plugin.api.util.description
import org.utbot.framework.plugin.api.util.kClass
import kotlin.reflect.KClass

internal class CgTestClassConstructor(val context: CgContext) :
    CgContextOwner by context,
    CgStatementConstructor by CgComponents.getStatementConstructorBy(context) {

    private val methodConstructor = CgComponents.getMethodConstructorBy(context)
    private val nameGenerator = CgComponents.getNameGeneratorBy(context)

    private val cgDataProviderMethods = mutableListOf<CgMethod>()

    private val testsGenerationReport: TestsGenerationReport = TestsGenerationReport()

    private fun <R> withTestSetScope(executableTestSet: CgMethodTestSet, block: () -> R): R {
        /**
         * This function makes sure that **test set** currently being generated is reset.
         * It is used at the start of test set generation and right after it.
         */
        fun clearTestSetScope() {
            currentTestSet = null
        }

        clearTestSetScope()
        currentTestSet = executableTestSet

        return try {
            block()
        } finally {
            clearTestSetScope()
        }
    }

    /**
     * Given a list of test sets constructs CgTestClass
     */
    fun construct(testSets: Collection<CgMethodTestSet>): CgTestClassFile {
        return buildTestClassFile {
            testClass = buildTestClass {
                // TODO: obtain test class from plugin
                id = currentTestClass
                body = buildTestClassBody {
                    cgDataProviderMethods.clear()
                    for (testSet in testSets) {
                        if (testSet.executions.isEmpty()) continue

                        withTestSetScope (testSet) {
                            val currentTestSetRegions = construct()
                            val executableUnderTestCluster = CgExecutableUnderTestCluster(
                                "Test suites for executable ${testSet.executableId}",
                                currentTestSetRegions,
                            )
                            testMethodRegions += executableUnderTestCluster
                        }

                    }

                    dataProvidersAndUtilMethodsRegion += CgStaticsRegion(
                        "Data providers and utils methods",
                        cgDataProviderMethods + createUtilMethods()
                    )
                }
                // It is important that annotations, superclass and interfaces assignment is run after
                // all methods are generated so that all necessary info is already present in the context
                annotations += context.collectedTestClassAnnotations
                superclass = context.testClassSuperclass
                interfaces += context.collectedTestClassInterfaces
            }
            imports += context.collectedImports
            testsGenerationReport = this@CgTestClassConstructor.testsGenerationReport
        }
    }

    private fun construct(): List<CgRegion<CgMethod>> {
        val currentTestSet = currentTestSet
            ?: error("CurrentTestSet is not defined inside test set scope")

        val regions = mutableListOf<CgRegion<CgMethod>>()
        val requiredFields = mutableListOf<CgParameterDeclaration>()

        when (context.parameterizedTestSource) {
            ParametrizedTestSource.DO_NOT_PARAMETRIZE -> {
                for ((clusterSummary, executionIndices) in currentTestSet.clustersInfo) {
                    val currentTestCaseTestMethods = mutableListOf<CgTestMethod>()
                    emptyLineIfNeeded()
                    for (i in executionIndices) {
                        runCatching {
                            currentTestCaseTestMethods +=
                                methodConstructor.createTestMethod(currentTestSet.executions[i])
                        }.onFailure { error -> processFailure(error) }
                    }
                    val clusterHeader = clusterSummary?.header
                    val clusterContent = clusterSummary?.content
                        ?.split('\n')
                        ?.let { CgTripleSlashMultilineComment(it) }
                    regions += CgTestMethodCluster(clusterHeader, clusterContent, currentTestCaseTestMethods)

                    testsGenerationReport.addTestsByType(currentTestSet, currentTestCaseTestMethods)
                }
            }
            ParametrizedTestSource.PARAMETRIZE -> {
                runCatching {
                    val dataProviderMethodName = nameGenerator.dataProviderMethodNameFor()
                    val parameterizedTestMethod = methodConstructor.createParameterizedTestMethod(dataProviderMethodName)

                    requiredFields += parameterizedTestMethod.requiredFields

                    cgDataProviderMethods += methodConstructor.createParameterizedTestDataProvider(dataProviderMethodName)

                    regions += CgSimpleRegion(
                        "Parameterized test for method ${currentTestSet.executableId.displayName}",
                        listOf(parameterizedTestMethod),
                    )
                }.onFailure { error -> processFailure(error) }
            }
        }

        val errors = currentTestSet.allErrors
        if (errors.isNotEmpty()) {
            regions += methodConstructor.errorMethod(errors)
            testsGenerationReport.addMethodErrors(currentTestSet, errors)
        }

        return regions
    }

    private fun processFailure(failure: Throwable) {
        val testSet = currentTestSet ?: error("CurrentTestSet must be initialized to process failure")
        codeGenerationErrors
            .getOrPut(testSet) { mutableMapOf() }
            .merge(failure.description, 1, Int::plus)
    }

    // TODO: collect imports of util methods
    private fun createUtilMethods(): List<CgUtilMethod> {
        val utilMethods = mutableListOf<CgUtilMethod>()
        // some util methods depend on the others
        // using this loop we make sure that all the
        // util methods dependencies are taken into account
        while (requiredUtilMethods.isNotEmpty()) {
            val method = requiredUtilMethods.first()
            requiredUtilMethods.remove(method)
            if (method.name !in existingMethodNames) {
                utilMethods += CgUtilMethod(method)
                importUtilMethodDependencies(method)
                existingMethodNames += method.name
                requiredUtilMethods += method.dependencies()
            }
        }
        return utilMethods
    }

    /**
     * If @receiver is an util method, then returns a list of util method ids that @receiver depends on
     * Otherwise, an empty list is returned
     */
    private fun MethodId.dependencies(): List<MethodId> = when (this) {
        createInstance -> listOf(getUnsafeInstance)
        deepEquals -> listOf(arraysDeepEquals, iterablesDeepEquals, streamsDeepEquals, mapsDeepEquals, hasCustomEquals)
        arraysDeepEquals, iterablesDeepEquals, streamsDeepEquals, mapsDeepEquals -> listOf(deepEquals)
        else -> emptyList()
    }

    /**
     * Engine errors + codegen errors for a given [UtMethodTestSet]
     */
    private val CgMethodTestSet.allErrors: Map<String, Int>
        get() = errors + codeGenerationErrors.getOrDefault(this, mapOf())
}

typealias MethodGeneratedTests = MutableMap<ExecutableId, MutableSet<CgTestMethod>>
typealias ErrorsCount = Map<String, Int>

data class TestsGenerationReport(
    val executables: MutableSet<ExecutableId> = mutableSetOf(),
    var successfulExecutions: MethodGeneratedTests = mutableMapOf(),
    var timeoutExecutions: MethodGeneratedTests = mutableMapOf(),
    var failedExecutions: MethodGeneratedTests = mutableMapOf(),
    var crashExecutions: MethodGeneratedTests = mutableMapOf(),
    var errors: MutableMap<ExecutableId, ErrorsCount> = mutableMapOf()
) {
    val classUnderTest: KClass<*>
        get() = executables.firstOrNull()?.classId?.kClass
            ?: error("No executables found in test report")

    val initialWarnings: MutableList<() -> String> = mutableListOf()
    val hasWarnings: Boolean
        get() = initialWarnings.isNotEmpty()

    val detailedStatistics: String
        get() = buildString {
            appendHtmlLine("Class: ${classUnderTest.qualifiedName}")
            val testMethodsStatistic = executables.map { it.countTestMethods() }
            val errors = executables.map { it.countErrors() }
            val overallErrors = errors.sum()

            appendHtmlLine("Successful test methods: ${testMethodsStatistic.sumBy { it.successful }}")
            appendHtmlLine(
                "Failing because of unexpected exception test methods: ${testMethodsStatistic.sumBy { it.failing }}"
            )
            appendHtmlLine(
                "Failing because of exceeding timeout test methods: ${testMethodsStatistic.sumBy { it.timeout }}"
            )
            appendHtmlLine(
                "Failing because of possible JVM crash test methods: ${testMethodsStatistic.sumBy { it.crashes }}"
            )
            appendHtmlLine("Not generated because of internal errors test methods: $overallErrors")
        }

    fun addMethodErrors(testSet: CgMethodTestSet, errors: Map<String, Int>) {
        this.errors[testSet.executableId] = errors
    }

    fun addTestsByType(testSet: CgMethodTestSet, testMethods: List<CgTestMethod>) {
        with(testSet.executableId) {
            executables += this

            testMethods.forEach {
                when (it.type) {
                    SUCCESSFUL -> updateExecutions(it, successfulExecutions)
                    FAILING -> updateExecutions(it, failedExecutions)
                    TIMEOUT -> updateExecutions(it, timeoutExecutions)
                    CRASH -> updateExecutions(it, crashExecutions)
                    PARAMETRIZED -> {
                        // Parametrized tests are not supported in the tests report yet
                        // TODO JIRA:1507
                    }
                }
            }
        }
    }

    fun toString(isShort: Boolean): String = buildString {
        appendHtmlLine("Target: ${classUnderTest.qualifiedName}")
        if (initialWarnings.isNotEmpty()) {
            initialWarnings.forEach { appendHtmlLine(it()) }
            appendHtmlLine()
        }

        val testMethodsStatistic = executables.map { it.countTestMethods() }
        val overallTestMethods = testMethodsStatistic.sumBy { it.count }

        appendHtmlLine("Overall test methods: $overallTestMethods")

        if (!isShort) {
            appendHtmlLine(detailedStatistics)
        }
    }

    override fun toString(): String = toString(false)

    private fun ExecutableId.countTestMethods(): TestMethodStatistic = TestMethodStatistic(
        testMethodsNumber(successfulExecutions),
        testMethodsNumber(failedExecutions),
        testMethodsNumber(timeoutExecutions),
        testMethodsNumber(crashExecutions)
    )

    private fun ExecutableId.countErrors(): Int = errors.getOrDefault(this, emptyMap()).values.sum()

    private fun ExecutableId.testMethodsNumber(executables: MethodGeneratedTests): Int =
        executables.getOrDefault(this, emptySet()).size

    private fun ExecutableId.updateExecutions(it: CgTestMethod, executions: MethodGeneratedTests) {
        executions.getOrPut(this) { mutableSetOf() } += it
    }

    private data class TestMethodStatistic(val successful: Int, val failing: Int, val timeout: Int, val crashes: Int) {
        val count: Int = successful + failing + timeout + crashes
    }
}
