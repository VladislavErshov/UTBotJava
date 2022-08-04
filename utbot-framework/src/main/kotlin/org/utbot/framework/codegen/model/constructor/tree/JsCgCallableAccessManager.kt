package org.utbot.framework.codegen.model.constructor.tree

import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.constructor.context.CgContextOwner
import org.utbot.framework.codegen.model.tree.CgConstructorCall
import org.utbot.framework.codegen.model.tree.CgExecutableCall
import org.utbot.framework.codegen.model.tree.CgExpression
import org.utbot.framework.codegen.model.tree.CgMethodCall
import org.utbot.framework.codegen.model.util.resolve
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.ConstructorId
import org.utbot.framework.plugin.api.JsConstructorId
import org.utbot.framework.plugin.api.JsMethodId
import org.utbot.framework.plugin.api.MethodId

internal class JsCgCallableAccessManagerImpl(context: CgContext) : CgCallableAccessManager,
    CgContextOwner by context {
    override operator fun CgExpression?.get(methodId: MethodId): CgIncompleteMethodCall =
        CgIncompleteMethodCall(methodId, this)

    override operator fun ClassId.get(staticMethodId: MethodId): CgIncompleteMethodCall =
        CgIncompleteMethodCall(staticMethodId, null)

    override operator fun ConstructorId.invoke(vararg args: Any?): CgExecutableCall {
        val resolvedArgs = args.resolve()
        val constructorCall = CgConstructorCall(this, resolvedArgs)
        newConstructorCall(this)
        return constructorCall
    }

    override fun CgIncompleteMethodCall.invoke(vararg args: Any?): CgMethodCall {
        val resolvedArgs = args.resolve()
        val methodCall = CgMethodCall(caller, method, resolvedArgs)
        newMethodCall(method)
        return methodCall
    }

    private fun newConstructorCall(constructorId: ConstructorId) {
        importedClasses += constructorId.classId
        // TODO: think
//        for (exception in constructorId.exceptions) {
//            addExceptionIfNeeded(exception)
//        }
    }

    private fun newMethodCall(methodId: MethodId) {
        // TODO: think
        if (methodId.classId.name == "undefined") {
            importedStaticMethods += methodId
            return
        }
        importedClasses += methodId.classId
    }
}