package org.utbot.python

import org.utbot.framework.plugin.api.*
import org.utbot.fuzzer.FuzzedConcreteValue
import org.utbot.fuzzer.FuzzedMethodDescription
import org.utbot.fuzzer.fuzz
import org.utbot.fuzzer.names.MethodBasedNameSuggester
import org.utbot.fuzzer.names.ModelBasedNameSuggester
import org.utbot.python.providers.defaultPythonModelProvider
import org.utbot.python.typing.PythonTypesStorage

class PythonEngine(
    private val methodUnderTest: PythonMethod,
    private val directoriesForSysPath: List<String>,
    private val moduleToImport: String,
    private val pythonPath: String,
    private val fuzzedConcreteValues: List<FuzzedConcreteValue>,
    private val selectedTypeMap: Map<String, NormalizedPythonAnnotation>
) {
    fun fuzzing(): Sequence<PythonResult> = sequence {
        val types = methodUnderTest.arguments.map {
            selectedTypeMap[it.name] ?: pythonAnyClassId
        }

        val methodUnderTestDescription = FuzzedMethodDescription(
            methodUnderTest.name,
            pythonAnyClassId,
            types,
            fuzzedConcreteValues
        ).apply {
            compilableName = methodUnderTest.name // what's the difference with ordinary name?
            parameterNameMap = { index -> methodUnderTest.arguments.getOrNull(index)?.name }
        }

        val pythonTypes = selectedTypeMap.values.map { it.name }

        fuzz(methodUnderTestDescription, defaultPythonModelProvider).forEach { values ->
            val modelList = values.map { it.model }
            val evalResult = PythonEvaluation.evaluate(
                methodUnderTest,
                modelList,
                directoriesForSysPath,
                moduleToImport,
                pythonPath,
                pythonTypes
            )
            if (evalResult is EvaluationError)
                return@sequence

            val (resultJSON, isException) = evalResult as EvaluationSuccess

            if (isException) {
                yield(PythonError(UtError(resultJSON.output.toString(), Throwable()), modelList, pythonTypes))
            } else {

                val resultAsModel = PythonTreeModel(
                    resultJSON.output,
                    resultJSON.type
                )
                val result = UtExecutionSuccess(resultAsModel)

                val nameSuggester = sequenceOf(ModelBasedNameSuggester(), MethodBasedNameSuggester())
                val testMethodName = try {
                    nameSuggester.flatMap { it.suggest(methodUnderTestDescription, values, result) }.firstOrNull()
                } catch (t: Throwable) {
                    null
                }

                yield(
                    PythonExecution(
                        UtExecution(
                            stateBefore = EnvironmentModels(null, modelList, emptyMap()),
                            stateAfter = EnvironmentModels(null, modelList, emptyMap()),
                            result = result,
                            instrumentation = emptyList(),
                            path = mutableListOf(), // ??
                            fullPath = emptyList(), // ??
                            testMethodName = testMethodName?.testName,
                            displayName = testMethodName?.displayName
                        ),
                        modelList,
                        pythonTypes
                    )
                )
            }
        }
    }
}
