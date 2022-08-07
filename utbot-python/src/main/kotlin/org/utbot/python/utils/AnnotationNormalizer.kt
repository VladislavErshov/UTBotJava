package org.utbot.python.utils

import org.utbot.framework.plugin.api.PythonClassId
import org.utbot.framework.plugin.api.pythonAnyClassId
import java.io.File


object AnnotationNormalizer {
    private fun getFileWithScript(resourceName: String): File {
        val scriptContent = AnnotationNormalizer::class.java.getResource(resourceName)
            ?.readText()
            ?: error("Didn't find $resourceName")

        return FileManager.createTemporaryFile(scriptContent, tag = "normalize_annotation")
    }

    private fun normalizeAnnotationFromProject(
        annotation: String,
        pythonPath: String,
        projectRoot: String,
        fileOfAnnotation: String,
        filesToAddToSysPath: List<String>
    ): String {
        val scriptFile = getFileWithScript("/normalize_annotation_from_project.py")
        val result = runCommand(
            listOf(
                pythonPath,
                scriptFile.path,
                annotation,
                projectRoot,
                fileOfAnnotation,
            ) + filesToAddToSysPath,
        )
        scriptFile.delete()
        return if (result.exitValue == 0) result.stdout else annotation
    }

    fun annotationFromProjectToClassId(
        annotation: String?,
        pythonPath: String,
        projectRoot: String,
        fileOfAnnotation: String,
        filesToAddToSysPath: List<String>
    ): PythonClassId =
        if (annotation == null)
            pythonAnyClassId
        else
            PythonClassId(
                substituteTypes(
                    normalizeAnnotationFromProject(
                        annotation,
                        pythonPath,
                        projectRoot,
                        fileOfAnnotation,
                        filesToAddToSysPath
                    )
                )
            )

    fun annotationFromStubToClassId(
        annotation: String,
        pythonPath: String,
        moduleOfAnnotation: String
    ): PythonClassId {
        val scriptFile = getFileWithScript("/normalize_annotation_from_stub.py")
        val result = runCommand(listOf(
            pythonPath,
            scriptFile.path,
            annotation,
            moduleOfAnnotation
        ))
        scriptFile.delete()
        return PythonClassId(
            substituteTypes(
                if (result.exitValue == 0) result.stdout else annotation
            )
        )
    }

    val substitutionMapFirstStage = listOf(
        "builtins.list" to "typing.List",
        "builtins.dict" to "typing.Dict",
        "builtins.set" to "typing.Set"
    )

    val substitutionMapSecondStage = listOf(
        Regex("typing.List *([^\\[]|$)") to "typing.List[typing.Any]",
        Regex("typing.Dict *([^\\[]|$)") to "typing.Dict[typing.Any, typing.Any]",
        Regex("typing.Set *([^\\[]|$)") to "typing.Set[typing.Any]"
    )

    fun substituteTypes(annotation: String): String {
        val firstStage = substitutionMapFirstStage.fold(annotation) { acc, (old, new) ->
            acc.replace(old, new)
        }
        return substitutionMapSecondStage.fold(firstStage) { acc, (re, new) ->
            acc.replace(re, new)
        }
    }
}