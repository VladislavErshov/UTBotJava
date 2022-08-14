package org.utbot.go.gocodeanalyzer

import java.io.File
import org.utbot.go.api.GoTypeId
import org.utbot.go.api.GoUtFile
import org.utbot.go.api.GoUtFunction
import org.utbot.go.api.GoUtFunctionParameter
import org.utbot.go.util.executeCommandByNewProcessOrFail
import org.utbot.go.util.findGoExecutableAbsolutePath
import org.utbot.go.util.parseFromJsonOrFail
import org.utbot.go.util.writeJsonToFileOrFail

object GoSourceCodeAnalyzer {

    data class GoSourceFileAnalysisResult(
        val functions: List<GoUtFunction>,
        val notSupportedFunctionsNames: List<String>,
        val notFoundFunctionsNames: List<String>
    )

    /**
     * Takes map from absolute paths of Go source files to names of their selected functions.
     * If list is empty, all containing functions are selected.
     *
     * Returns GoSourceFileAnalysisResult-s grouped by their source files.
     */
    fun analyzeGoSourceFilesForFunctions(targetFunctionsNamesBySourceFiles: Map<String, List<String>>): Map<GoUtFile, GoSourceFileAnalysisResult> {
        val analysisTargets = AnalysisTargets(
            targetFunctionsNamesBySourceFiles.map { (absoluteFilePath, targetFunctionsNames) ->
                AnalysisTarget(absoluteFilePath, targetFunctionsNames)
            }
        )
        val analysisTargetsFileName = createAnalysisTargetsFileName()
        val analysisResultsFileName = createAnalysisResultsFileName()

        val goCodeAnalyzerSourceDir = File(findGoCodeAnalyzerSourceDirectoryPath())
        val analysisTargetsFile = goCodeAnalyzerSourceDir.resolve(analysisTargetsFileName)
        val analysisResultsFile = goCodeAnalyzerSourceDir.resolve(analysisResultsFileName)

        val goCodeAnalyzerRunCommand = listOf(
            findGoExecutableAbsolutePath(),
            "run"
        ) + getGoCodeAnalyzerSourceFilesNames() + listOf(
            "-targets",
            analysisTargetsFileName,
            "-results",
            analysisResultsFileName,
        )

        try {
            writeJsonToFileOrFail(analysisTargets, analysisTargetsFile)
            executeCommandByNewProcessOrFail(
                goCodeAnalyzerRunCommand,
                goCodeAnalyzerSourceDir,
                "GoSourceCodeAnalyzer for $analysisTargets"
            )
            val analysisResults = parseFromJsonOrFail<AnalysisResults>(analysisResultsFile)

            return analysisResults.results.map { analysisResult ->
                GoUtFile(analysisResult.absoluteFilePath, analysisResult.packageName) to analysisResult
            }.associateBy({ (sourceFile, _) -> sourceFile }) { (sourceFile, analysisResult) ->
                val functions = analysisResult.analyzedFunctions.map { analyzedFunction ->
                    fun AnalyzedType.toGoTypeId() = GoTypeId(this.name, implementsError = this.implementsError)
                    val parameters = analyzedFunction.parameters.map { analyzedFunctionParameter ->
                        GoUtFunctionParameter(
                            analyzedFunctionParameter.name,
                            analyzedFunctionParameter.type.toGoTypeId()
                        )
                    }
                    val resultTypes = analyzedFunction.resultTypes.map { analyzedType -> analyzedType.toGoTypeId() }
                    GoUtFunction(
                        analyzedFunction.name,
                        parameters,
                        resultTypes,
                        emptyList(), // TODO: extract concrete values from function's body
                        sourceFile
                    )
                }
                GoSourceFileAnalysisResult(
                    functions,
                    analysisResult.notSupportedFunctionsNames,
                    analysisResult.notFoundFunctionsNames
                )
            }
        } finally {
            analysisTargetsFile.delete()
            analysisResultsFile.delete()
        }
    }

    // TODO: find path by code
    private fun findGoCodeAnalyzerSourceDirectoryPath(): String {
        return "/home/gleb/tabs/UTBotJava/utbot-go/src/main/resources/go_source_code_analyzer/"
    }

    private fun getGoCodeAnalyzerSourceFilesNames(): List<String> {
        return listOf("main.go", "analyzer_core.go", "analysis_targets.go", "analysis_results.go")
    }

    private fun createAnalysisTargetsFileName(): String {
        return "ut_go_analysis_targets.json"
    }

    private fun createAnalysisResultsFileName(): String {
        return "ut_go_analysis_results.json"
    }
}