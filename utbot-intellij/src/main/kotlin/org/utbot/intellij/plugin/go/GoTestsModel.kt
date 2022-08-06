package org.utbot.intellij.plugin.go

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.utbot.intellij.plugin.models.BaseTestsModel

class GoTestsModel(
    project: Project,
    srcModule: Module,
    testModule: Module,
    val functionsOrMethods: Set<GoFunctionOrMethodDeclaration>,
    val focusedFunctionOrMethod: GoFunctionOrMethodDeclaration?,
) : BaseTestsModel(
    project,
    srcModule,
    testModule
) {
    lateinit var selectedFunctionsOrMethods: Set<GoFunctionOrMethodDeclaration>
    lateinit var srcFiles: Set<GoFile>
}