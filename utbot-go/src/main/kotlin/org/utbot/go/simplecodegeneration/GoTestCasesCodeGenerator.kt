package org.utbot.go.simplecodegeneration

import org.utbot.framework.plugin.api.*
import org.utbot.go.api.*
import org.utbot.go.api.util.*
import org.utbot.go.util.goRequiredImports

object GoTestCasesCodeGenerator {

    fun generateTestCasesFileCode(sourceFile: GoUtFile, testCases: List<GoUtFuzzedFunctionTestCase>): String {
        val fileBuilder = GoFileCodeBuilder()

        fileBuilder.setPackage(sourceFile.packageName)

        val imports = mutableSetOf("github.com/stretchr/testify/assert", "testing")
        testCases.forEach { testCase ->
            testCase.fuzzedParametersValues.forEach {
                imports += it.goRequiredImports
            }
            when (val executionResult = testCase.executionResult) {
                is GoUtExecutionCompleted -> {
                    executionResult.models.forEach {
                        imports += it.requiredImports
                    }
                }

                is GoUtPanicFailure -> {
                    imports += executionResult.panicValue.requiredImports
                }
            }
        }
        fileBuilder.setImports(imports)

        fun List<GoUtFuzzedFunctionTestCase>.generateTestFunctions(
            generateTestFunctionForTestCase: (GoUtFuzzedFunctionTestCase, Int?) -> String
        ) {
            this.forEachIndexed { testIndex, testCase ->
                val testIndexToShow = if (this.size == 1) null else testIndex + 1
                val testFunctionCode = generateTestFunctionForTestCase(testCase, testIndexToShow)
                fileBuilder.addTopLevelElements(testFunctionCode)
            }
        }

        fun GoUtFuzzedFunctionTestCase.isPanicTestCase(): Boolean {
            return this.executionResult is GoUtPanicFailure
        }

        val testCasesByFunction = testCases.groupBy { it.function }
        testCasesByFunction.forEach { (_, functionTestCases) ->
            functionTestCases.filterNot { it.isPanicTestCase() }
                .generateTestFunctions(::generateTestFunctionForCompletedExecutionTestCase)
            functionTestCases.filter { it.isPanicTestCase() }
                .generateTestFunctions(::generateTestFunctionForPanicFailureTestCase)
        }

        return fileBuilder.buildCodeString()
    }

    private fun generateTestFunctionForCompletedExecutionTestCase(
        testCase: GoUtFuzzedFunctionTestCase,
        testIndexToShow: Int?
    ): String {
        val (fuzzedFunction, executionResult) = testCase
        val function = fuzzedFunction.function

        val testFunctionNamePostfix =
            if (executionResult is GoUtExecutionWithNonNilError) {
                "WithNonNilError"
            } else {
                ""
            }
        val testIndexToShowString = testIndexToShow ?: ""
        val testFunctionSignatureDeclaration =
            "func Test${function.name.capitalize()}${testFunctionNamePostfix}ByUtGoFuzzer$testIndexToShowString(t *testing.T)"

        if (function.resultTypes.isEmpty()) {
            val actualFunctionCall = generateFuzzedFunctionCall(fuzzedFunction)
            val testFunctionBody = "\tassert.NotPanics(t, func() { $actualFunctionCall })\n"
            return "$testFunctionSignatureDeclaration {\n$testFunctionBody}"
        }

        val testFunctionBodySb = StringBuilder()

        val resultTypes = function.resultTypes
        val doResultTypesImplementError = resultTypes.map { it.implementsError }
        val actualResultVariablesNames = run {
            val errorVariablesNumber = doResultTypesImplementError.count { it }
            val commonVariablesNumber = resultTypes.size - errorVariablesNumber

            var errorVariablesIndex = 0
            var commonVariablesIndex = 0
            doResultTypesImplementError.map { implementsError ->
                if (implementsError) {
                    "actualErr${if (errorVariablesNumber > 1) errorVariablesIndex++ else ""}"
                } else {
                    "actualVal${if (commonVariablesNumber > 1) commonVariablesIndex++ else ""}"
                }
            }
        }
        val actualFunctionCall = generateFuzzedFunctionCallSavedToVariables(actualResultVariablesNames, fuzzedFunction)
        testFunctionBodySb.append("\t$actualFunctionCall\n\n")

        val expectedModels = (executionResult as GoUtExecutionCompleted).models
        val (assertionName, assertionTParameter) =
            if (expectedModels.size > 1 || expectedModels.any { it.isComplexModelAndNeedsSeparateAssertions() }) {
                testFunctionBodySb.append("\tassertMultiple := assert.New(t)\n")
                "assertMultiple" to ""
            } else {
                "assert" to "t, "
            }
        actualResultVariablesNames.zip(expectedModels).zip(doResultTypesImplementError)
            .forEach { (resultVariableAndModel, doesResultTypeImplementError) ->
                val (actualResultVariableName, expectedModel) = resultVariableAndModel

                val assertionCalls = mutableListOf<String>()
                fun generateAssertionCallHelper(refinedExpectedModel: GoUtModel, actualResultCode: String) {
                    val code = generateCompletedExecutionAssertionCall(
                        refinedExpectedModel,
                        actualResultCode,
                        doesResultTypeImplementError,
                        assertionTParameter
                    )
                    assertionCalls.add(code)
                }

                if (expectedModel.isComplexModelAndNeedsSeparateAssertions()) {
                    val complexModel = expectedModel as GoUtComplexModel
                    generateAssertionCallHelper(complexModel.realValue, "real($actualResultVariableName)")
                    generateAssertionCallHelper(complexModel.imagValue, "imag($actualResultVariableName)")
                } else {
                    generateAssertionCallHelper(expectedModel, actualResultVariableName)
                }
                assertionCalls.forEach { testFunctionBodySb.append("\t$assertionName.$it\n") }
            }
        val testFunctionBody = testFunctionBodySb.toString()

        return "$testFunctionSignatureDeclaration {\n$testFunctionBody}"
    }

    private fun GoUtModel.isComplexModelAndNeedsSeparateAssertions(): Boolean =
        this is GoUtComplexModel && this.containsNaNOrInf()

    private fun generateCompletedExecutionAssertionCall(
        expectedModel: GoUtModel,
        actualResultCode: String,
        doesReturnTypeImplementError: Boolean,
        assertionTParameter: String
    ): String {
        if (expectedModel is GoUtNilModel) {
            return "Nil($assertionTParameter$actualResultCode)"
        }
        if (doesReturnTypeImplementError && expectedModel.classId == goStringTypeId) {
            return "ErrorContains($assertionTParameter$actualResultCode, $expectedModel)"
        }
        if (expectedModel is GoUtFloatNaNModel) {
            val castedActualResultCode =
                generateCastIfNeed(goFloat64TypeId, expectedModel.typeId, actualResultCode)
            return "True(${assertionTParameter}math.IsNaN($castedActualResultCode))"
        }
        if (expectedModel is GoUtFloatInfModel) {
            val castedActualResultCode =
                generateCastIfNeed(goFloat64TypeId, expectedModel.typeId, actualResultCode)
            return "True(${assertionTParameter}math.IsInf($castedActualResultCode, ${expectedModel.sign}))"
        }
        val castedExpectedResultCode = generateCastedValueIfPossible(expectedModel as GoUtPrimitiveModel)
        return "Equal($assertionTParameter$castedExpectedResultCode, $actualResultCode)"
    }

    private fun generateTestFunctionForPanicFailureTestCase(
        testCase: GoUtFuzzedFunctionTestCase,
        testIndexToShow: Int?
    ): String {
        val (fuzzedFunction, executionResult) = testCase
        val function = fuzzedFunction.function

        val testIndexToShowString = testIndexToShow ?: ""
        val testFunctionSignatureDeclaration =
            "func Test${function.name.capitalize()}PanicsByUtGoFuzzer$testIndexToShowString(t *testing.T)"

        val actualFunctionCall = generateFuzzedFunctionCall(fuzzedFunction)
        val actualFunctionCallLambda = "func() { $actualFunctionCall }"
        val (expectedPanicValue, expectedPanicValueSourceGoType) = (executionResult as GoUtPanicFailure)

        val isPrimitiveWithOkEquals =
            expectedPanicValueSourceGoType.isPrimitiveGoType && expectedPanicValue.doesNotContainNaNOrInf()
        val testFunctionBody = if (isPrimitiveWithOkEquals || expectedPanicValue is GoUtNilModel) {
            val expectedPanicValueCode = if (expectedPanicValue is GoUtNilModel) {
                "$expectedPanicValue"
            } else {
                generateCastedValueIfPossible(expectedPanicValue as GoUtPrimitiveModel)
            }
            "\tassert.PanicsWithValue(t, $expectedPanicValueCode, $actualFunctionCallLambda)"
        } else if (expectedPanicValueSourceGoType.implementsError) {
            "\tassert.PanicsWithError(t, $expectedPanicValue, $actualFunctionCallLambda)"
        } else {
            "\tassert.Panics(t, $actualFunctionCallLambda)"
        }

        return "$testFunctionSignatureDeclaration {\n$testFunctionBody\n}"
    }
}