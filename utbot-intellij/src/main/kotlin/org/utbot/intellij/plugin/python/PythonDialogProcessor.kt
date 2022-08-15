package org.utbot.intellij.plugin.python

import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyClass
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.idea.util.projectStructure.sdk
import org.utbot.common.PathUtil.toPath
import org.utbot.framework.codegen.model.CodeGenerator
import org.utbot.framework.codegen.model.constructor.CgMethodTestSet
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.UtContext
import org.utbot.framework.plugin.api.util.withUtContext
import org.utbot.framework.UtSettings
import org.utbot.intellij.plugin.ui.utils.showErrorDialogLater
import org.utbot.intellij.plugin.ui.utils.testModule
import org.utbot.intellij.plugin.ui.WarningTestsReportNotifier
import org.utbot.python.code.PythonCode
import org.utbot.python.code.PythonCode.Companion.getFromString
import org.utbot.python.PythonMethod
import org.utbot.python.PythonTestCaseGenerator
import org.utbot.python.typing.MypyAnnotations
import org.utbot.python.typing.PythonTypesStorage
import org.utbot.python.typing.StubFileFinder
import org.utbot.python.utils.FileManager
import org.utbot.python.utils.camelToSnakeCase
import org.utbot.python.utils.getLineOfFunction
import org.utbot.common.appendHtmlLine
import org.utbot.python.PythonTestSet
import org.utbot.python.typing.MypyAnnotations.TEMPORARY_MYPY_FILE

object PythonDialogProcessor {
    fun createDialogAndGenerateTests(
        project: Project,
        functionsToShow: Set<PyFunction>,
        containingClass: PyClass?,
        focusedMethod: PyFunction?,
        file: PyFile
    ) {
        val dialog = createDialog(project, functionsToShow, containingClass, focusedMethod, file)
        if (!dialog.showAndGet()) {
            return
        }

        createTests(project, dialog.model)
    }

    private fun createDialog(
        project: Project,
        functionsToShow: Set<PyFunction>,
        containingClass: PyClass?,
        focusedMethod: PyFunction?,
        file: PyFile
    ): PythonDialogWindow {
        val srcModule = findSrcModule(functionsToShow)
        val testModule = srcModule.testModule(project)
        val (directoriesForSysPath, moduleToImport) = getDirectoriesForSysPath(srcModule, file)

        return PythonDialogWindow(
            PythonTestsModel(
                project,
                srcModule,
                testModule,
                functionsToShow,
                containingClass,
                if (focusedMethod != null) setOf(focusedMethod) else null,
                file,
                directoriesForSysPath,
                moduleToImport,
                UtSettings.utBotGenerationTimeoutInMillis
            )
        )
    }

    private fun findSelectedPythonMethods(model: PythonTestsModel): List<PythonMethod> {
        val code = getPyCodeFromPyFile(model.file, model.currentPythonModule)

        val shownFunctions: Set<PythonMethod> =
            if (model.containingClass == null) {
                code.getToplevelFunctions().toSet()
            } else {
                val classes = code.getToplevelClasses()
                val myClass = classes.find { it.name == model.containingClass.name }
                    ?: error("Didn't find containing class")
                myClass.methods.toSet()
            }

        return model.selectedFunctions.map { pyFunction ->
            shownFunctions.find { pythonMethod ->
                pythonMethod.name == pyFunction.name
            } ?: error("Didn't find PythonMethod ${pyFunction.name}")
        }
    }

    private fun createTests(project: Project, model: PythonTestsModel) {
        ProgressManager.getInstance().run(object : Backgroundable(project, "Generate python tests") {
            override fun run(indicator: ProgressIndicator) {
                val pythonPath = model.srcModule.sdk?.homePath ?: error("Couldn't find Python interpreter")
                val testSourceRoot = model.testSourceRoot!!.path
                val filePath = model.file.virtualFile.path
                FileManager.assignTestSourceRoot(testSourceRoot)

                if (!MypyAnnotations.mypyInstalled(pythonPath) && !indicator.isCanceled) {
                    indicator.text = "Installing mypy"
                    MypyAnnotations.installMypy(pythonPath)
                    if (!MypyAnnotations.mypyInstalled(pythonPath))
                        error("Something wrong with mypy")
                }

                if (!indicator.isCanceled) {
                    indicator.text = "Loading information about Python types"

                    PythonTypesStorage.pythonPath = pythonPath
                    PythonTypesStorage.refreshProjectClassesList(
                        model.directoriesForSysPath
                    )

                    while (!StubFileFinder.isInitialized);

                    indicator.text = "Generating tests"
                }
                val startTime = System.currentTimeMillis()

                val pythonMethods = findSelectedPythonMethods(model)

                val testCaseGenerator = PythonTestCaseGenerator.apply {
                    init(
                        model.directoriesForSysPath,
                        model.currentPythonModule,
                        pythonPath,
                        filePath
                    ) { indicator.isCanceled || (System.currentTimeMillis() - startTime) > model.timeout }
                }

                val tests = pythonMethods.map { method ->
                    testCaseGenerator.generate(method)
                }

                val notEmptyTests = tests.filter { it.executions.isNotEmpty() || it.errors.isNotEmpty() }
                val emptyTestSets = tests.filter { it.executions.isEmpty() && it.errors.isEmpty() }

                if (emptyTestSets.isNotEmpty() && !indicator.isCanceled) {
                    val functionNames = emptyTestSets.map { it.method.name }
                    showErrorDialogLater(
                        project,
                        message = "Cannot create tests for the following functions: " + functionNames.joinToString(),
                        title = "Python test generation error"
                    )
                    if (notEmptyTests.isEmpty())
                        return
                }

                val classId =
                    if (model.containingClass == null)
                        PythonClassId(model.currentPythonModule + ".TopLevelFunctions")
                    else
                        PythonClassId(model.currentPythonModule + "." + model.containingClass.name)

                val methods = notEmptyTests.associate {
                    it.method to PythonMethodId(
                        classId,
                        it.method.name,
                        RawPythonAnnotation(it.method.returnAnnotation ?: pythonNoneClassId.name),
                        it.method.arguments.map { argument ->
                            argument.annotation?.let { annotation ->
                                RawPythonAnnotation(annotation)
                            } ?: pythonAnyClassId
                        }
                    )
                }
                val paramNames = notEmptyTests.associate { testSet ->
                    methods[testSet.method] as ExecutableId to testSet.method.arguments.map { it.name }
                }.toMutableMap()

                val context = UtContext(this::class.java.classLoader)
                withUtContext(context) {
                    val codegen = CodeGenerator(
                        classId,
                        paramNames = paramNames,
                        testFramework = model.testFramework,
                        codegenLanguage = model.codegenLanguage,
                        testClassPackageName = "",
                    )
                    val testCode = codegen.generateAsStringWithTestReport(
                        notEmptyTests.map { testSet ->
                            CgMethodTestSet(
                                methods[testSet.method] as ExecutableId,
                                testSet.executions,
                                model.directoriesForSysPath,
                            )
                        }
                    ).generatedCode
                    val fileName = "test_${classId.moduleName.camelToSnakeCase()}.py"
                    val testFile = FileManager.createPermanentFile(fileName, testCode)
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(testFile)
                    if (virtualFile != null) {
                        invokeAndWaitIfNeeded {
                            OpenFileDescriptor(model.project, virtualFile).navigate(true)
                        }
                    }
                }

                showNotifications(notEmptyTests, model)
            }
        })
    }

    private fun showNotifications(notEmptyTests: List<PythonTestSet>, model: PythonTestsModel) {
        val codeAsString = getContentFromPyFile(model.file)
        val mypyReport = notEmptyTests.fold(StringBuilder()) { acc, testSet ->
            val lineOfFunction = getLineOfFunction(codeAsString, testSet.method.name)
            val msgLines = testSet.mypyReport.map {
                if (lineOfFunction != null && it.line >= 0 && it.file == TEMPORARY_MYPY_FILE)
                    ":${it.line + lineOfFunction}: ${it.type}: ${it.message}"
                else
                    "${it.type}: ${it.message}"
            }
            if (msgLines.isNotEmpty()) {
                acc.appendHtmlLine("MYPY REPORT (function ${testSet.method.name})")
                msgLines.forEach { acc.appendHtmlLine(it) }
            }
            acc
        }.toString()
        if (mypyReport != "")
            WarningTestsReportNotifier.notify(mypyReport)
    }
}

fun findSrcModule(functions: Collection<PyFunction>): Module {
    val srcModules = functions.mapNotNull { it.module }.distinct()
    return when (srcModules.size) {
        0 -> error("Module for source classes not found")
        1 -> srcModules.first()
        else -> error("Can not generate tests for classes from different modules")
    }
}

fun getContentFromPyFile(file: PyFile) = file.viewProvider.contents.toString()

fun getPyCodeFromPyFile(file: PyFile, pythonModule: String): PythonCode {
    val content = getContentFromPyFile(file)
    return getFromString(content, pythonModule = pythonModule)
}

fun getDirectoriesForSysPath(
    srcModule: Module,
    file: PyFile
): Pair<Set<String>, String> {
    val sources = ModuleRootManager.getInstance(srcModule).getSourceRoots(false).toMutableList()
    val ancestor = ProjectFileIndex.SERVICE.getInstance(file.project).getContentRootForFile(file.virtualFile)
    if (ancestor != null && !sources.contains(ancestor))
        sources.add(ancestor)

    var importPath = ancestor?.let { VfsUtil.getParentDir(VfsUtilCore.getRelativeLocation(file.virtualFile, it)) } ?: ""
    if (importPath != "")
        importPath += "."

    return Pair(
        sources.map { it.path }.toSet(),
        "${importPath}${file.name}".removeSuffix(".py").toPath().joinToString(".")
    )
}