package org.utbot.framework.codegen.model.constructor.tree

import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.constructor.util.CgComponents
import org.utbot.framework.codegen.model.tree.CgLiteral
import org.utbot.framework.codegen.model.tree.CgValue
import org.utbot.framework.codegen.model.tree.CgVariable
import org.utbot.framework.plugin.api.*

class PythonCgVariableConstructor(context_: CgContext) : CgVariableConstructor(context_) {
    private val nameGenerator = CgComponents.getNameGeneratorBy(context)

    override fun getOrCreateVariable(model: UtModel, name: String?): CgValue {
        val baseName = name ?: nameGenerator.nameFrom(model.classId)
        return valueByModel.getOrPut(model) {
            when (model) {
                is PythonBoolModel -> CgLiteral(model.classId, model.value)
                is PythonStrModel -> CgLiteral(model.classId, model.value)
                is PythonFloatModel -> CgLiteral(model.classId, model.value)
                is PythonIntModel -> CgLiteral(model.classId, model.value)
                is PythonComplexObjectModel -> TODO()
                is PythonInitObjectModel -> TODO()
                is PythonDictModel -> CgLiteral(model.classId, model.stores)
                is PythonListModel -> CgLiteral(model.classId, model.stores)
                is PythonSetModel -> CgLiteral(model.classId, model.stores)
                is PythonDefaultModel -> CgLiteral(model.classId, model.repr)
                is PythonModel -> error("Unexpected PythonModel: ${model::class}")
                else -> super.getOrCreateVariable(model, name)
            }
        }
    }

    private fun constructDefaultModel(model: PythonDefaultModel, baseName: String): CgVariable {
        val init = model.repr
        return newVar(model.classId, baseName) { CgLiteral(model.classId, init) }
    }
}