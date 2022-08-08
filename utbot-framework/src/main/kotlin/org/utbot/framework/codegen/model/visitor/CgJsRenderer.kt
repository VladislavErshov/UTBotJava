package org.utbot.framework.codegen.model.visitor

import org.apache.commons.text.StringEscapeUtils
import org.utbot.framework.codegen.RegularImport
import org.utbot.framework.codegen.StaticImport
import org.utbot.framework.codegen.isLanguageKeyword
import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.codegen.model.tree.CgAllocateArray
import org.utbot.framework.codegen.model.tree.CgAllocateInitializedArray
import org.utbot.framework.codegen.model.tree.CgAnonymousFunction
import org.utbot.framework.codegen.model.tree.CgArrayAnnotationArgument
import org.utbot.framework.codegen.model.tree.CgArrayElementAccess
import org.utbot.framework.codegen.model.tree.CgArrayInitializer
import org.utbot.framework.codegen.model.tree.CgConstructorCall
import org.utbot.framework.codegen.model.tree.CgDeclaration
import org.utbot.framework.codegen.model.tree.CgEqualTo
import org.utbot.framework.codegen.model.tree.CgErrorTestMethod
import org.utbot.framework.codegen.model.tree.CgErrorWrapper
import org.utbot.framework.codegen.model.tree.CgExecutableCall
import org.utbot.framework.codegen.model.tree.CgExecutableUnderTestCluster
import org.utbot.framework.codegen.model.tree.CgExpression
import org.utbot.framework.codegen.model.tree.CgFieldAccess
import org.utbot.framework.codegen.model.tree.CgForLoop
import org.utbot.framework.codegen.model.tree.CgGetJavaClass
import org.utbot.framework.codegen.model.tree.CgGetKotlinClass
import org.utbot.framework.codegen.model.tree.CgGetLength
import org.utbot.framework.codegen.model.tree.CgInnerBlock
import org.utbot.framework.codegen.model.tree.CgMethod
import org.utbot.framework.codegen.model.tree.CgMethodCall
import org.utbot.framework.codegen.model.tree.CgMultipleArgsAnnotation
import org.utbot.framework.codegen.model.tree.CgNamedAnnotationArgument
import org.utbot.framework.codegen.model.tree.CgNotNullAssertion
import org.utbot.framework.codegen.model.tree.CgParameterDeclaration
import org.utbot.framework.codegen.model.tree.CgParameterizedTestDataProviderMethod
import org.utbot.framework.codegen.model.tree.CgSpread
import org.utbot.framework.codegen.model.tree.CgStaticsRegion
import org.utbot.framework.codegen.model.tree.CgSwitchCase
import org.utbot.framework.codegen.model.tree.CgSwitchCaseLabel
import org.utbot.framework.codegen.model.tree.CgTestClass
import org.utbot.framework.codegen.model.tree.CgTestClassFile
import org.utbot.framework.codegen.model.tree.CgTestMethod
import org.utbot.framework.codegen.model.tree.CgTypeCast
import org.utbot.framework.codegen.model.tree.CgVariable
import org.utbot.framework.codegen.model.util.CgPrinter
import org.utbot.framework.codegen.model.util.CgPrinterImpl
import org.utbot.framework.plugin.api.CodegenLanguage
import org.utbot.framework.plugin.api.JsClassId
import org.utbot.framework.plugin.api.TypeParameters

internal class CgJsRenderer(context: CgContext, printer: CgPrinter = CgPrinterImpl()) : CgAbstractRenderer(context, printer) {

    override val statementEnding: String = ""

    override val logicalAnd: String
        get() = "&&"

    override val logicalOr: String
        get() = "||"

    override val language: CodegenLanguage = CodegenLanguage.JS

    override val langPackage: String = "js"

    override fun visit(element: CgErrorWrapper) {
        element.expression.accept(this)
        print("alert(\"${element.message}\")")
    }

    override fun visit(element: CgInnerBlock) {
        println("{")
        withIndent {
            for (statement in element.statements) {
                statement.accept(this)
            }
        }
        println("}")
    }

    override fun visit(element: CgParameterDeclaration) {
        if (element.isVararg) {
            print("...")
        }
        print(element.name.escapeNamePossibleKeyword())
//        print(getKotlinClassString(element.type))
//        if (element.isReferenceType) {
//            print("?")
//        }
    }

    override fun visit(element: CgStaticsRegion) {
        if (element.content.isEmpty()) return

        print(regionStart)
        element.header?.let { print(" $it") }
        println()

        withIndent {
            for (item in element.content) {
                println()
                item.accept(this)
            }
        }

        println(regionEnd)
    }


    override fun visit(element: CgTestClass) {
        element.body.accept(this)
    }

    override fun visit(element: CgFieldAccess) {
        element.caller.accept(this)
        renderAccess(element.caller)
        print(element.fieldId.name)
    }

    override fun visit(element: CgArrayElementAccess) {
        element.array.accept(this)
        print("[")
        element.index.accept(this)
        print("]")
    }

    override fun visit(element: CgArrayAnnotationArgument) {
        throw UnsupportedOperationException()
    }

    override fun visit(element: CgAnonymousFunction) {
        print("function (")
        element.parameters.renderSeparated(true)
        println(") {")
        // cannot use visit(element.body) here because { was already printed
        withIndent {
            for (statement in element.body) {
                statement.accept(this)
            }
        }
        print("}")
    }

    override fun visit(element: CgEqualTo) {
        element.left.accept(this)
        print(" == ")
        element.right.accept(this)
    }

    // TODO SEVERE
    override fun visit(element: CgTypeCast) {
        element.expression.accept(this)
//        throw Exception("TypeCast not yet implemented")
    }

    override fun visit(element: CgSpread) {
        print("...")
        element.array.accept(this)
    }

    override fun visit(element: CgNotNullAssertion) {
        throw UnsupportedOperationException("JavaScript does not support not null assertions")
    }

    override fun visit(element: CgAllocateArray) {
        print("new Array(${element.size})")
    }

    override fun visit(element: CgAllocateInitializedArray) {
        print("[")
        element.initializer.accept(this)
        print("]")
    }

    // TODO SEVERE: I am unsure about this
    override fun visit(element: CgArrayInitializer) {
        val elementType = element.elementType
        val elementsInLine = arrayElementsInLine(elementType)

        element.values.renderElements(elementsInLine)
    }

    @Suppress("DuplicatedCode")
    override fun visit(element: CgTestClassFile) {
        println("import * as assert from \"assert\"")
        println("import * as fileUnderTest from \"./${(context.classUnderTest as JsClassId).filePath.substringAfterLast("/")}\"")
        println()
        element.testClass.accept(this)
    }

    override fun visit(element: CgSwitchCaseLabel) {
        if (element.label != null) {
            print("case ")
            element.label.accept(this)
        } else {
            print("default")
        }
        println(": ")
        visit(element.statements, printNextLine = true)
    }

    @Suppress("DuplicatedCode")
    override fun visit(element: CgSwitchCase) {
        print("switch (")
        element.value.accept(this)
        println(") {")
        withIndent {
            for (caseLabel in element.labels) {
                caseLabel.accept(this)
            }
            element.defaultLabel?.accept(this)
        }
        println("}")
    }

    override fun visit(element: CgGetLength) {
        element.variable.accept(this)
        print(".size")
    }

    override fun visit(element: CgGetJavaClass) {
        throw UnsupportedOperationException("No Java classes in JavaScript")
    }

    override fun visit(element: CgGetKotlinClass) {
        throw UnsupportedOperationException("No Kotlin classes in JavaScript")
    }

    override fun visit(element: CgConstructorCall) {
        print("new fileUnderTest.${element.executableId.classId.name}")
        print("(")
        element.arguments.renderSeparated()
        print(")")
    }

    //TODO SEVERE
    override fun renderRegularImport(regularImport: RegularImport) {
        throw Exception("Not implemented yet")
    }

    override fun renderStaticImport(staticImport: StaticImport) {
        throw Exception("Not implemented yet")
    }

    override fun renderMethodSignature(element: CgTestMethod) {
        println("it(\"${element.name}\", function ()")
    }

    override fun renderMethodSignature(element: CgErrorTestMethod) {
        println("it(\"${element.name}\", function ()")

    }

    override fun visit(element: CgMethod) {
        super.visit(element)
        if (element is CgTestMethod || element is CgErrorTestMethod) {
            println(")")
        }
    }

    override fun renderMethodSignature(element: CgParameterizedTestDataProviderMethod) {
        throw UnsupportedOperationException()
    }

    override fun visit(element: CgNamedAnnotationArgument) {

    }

    override fun visit(element: CgMultipleArgsAnnotation) {

    }

    override fun visit(element: CgMethodCall) {
        val caller = element.caller
        if (caller != null) {
            caller.accept(this)
            renderAccess(caller)
        } else {
            // for static methods render declaring class only if required
            val method = element.executableId
            if (method.classId.toString() == "assert.equal") {
                print("assert.equal")
            } else if (method.classId.toString() != "undefined") {
                print("new fileUnderTest.${method.classId}()")
                print(".")
            } else {
                print("fileUnderTest.")
            }
        }
        print(element.executableId.name.escapeNamePossibleKeyword())
        renderTypeParameters(element.typeParameters)
        renderExecutableCallArguments(element)
    }

    //TODO MINOR: check
    override fun renderForLoopVarControl(element: CgForLoop) {
        print("for (")
        with(element.initialization) {
            print("let ")
            visit(variable)
            print(" = ")
            initializer?.accept(this@CgJsRenderer)
            print("; ")
            visit(element.condition)
            print("; ")
            print(element.update)
        }
    }

    override fun renderDeclarationLeftPart(element: CgDeclaration) {
        if (element.isMutable) print("var ") else print("let ")
        visit(element.variable)
    }

    override fun toStringConstantImpl(byte: Byte) = "$byte"

    override fun toStringConstantImpl(short: Short) = "$short"

    override fun toStringConstantImpl(int: Int) = "$int"

    override fun toStringConstantImpl(long: Long) = "$long"

    override fun toStringConstantImpl(float: Float) = "$float"

    override fun renderAccess(caller: CgExpression) {
        print(".")
    }

    override fun renderTypeParameters(typeParameters: TypeParameters) {
        //TODO MINOR: check
    }

    override fun renderExecutableCallArguments(executableCall: CgExecutableCall) {
        print("(")
        val lastArgument = executableCall.arguments.lastOrNull()
        if (lastArgument != null && lastArgument is CgAnonymousFunction) {
            executableCall.arguments.dropLast(1).renderSeparated()
            print(") ")
            executableCall.arguments.last().accept(this)
        } else {
            executableCall.arguments.renderSeparated()
            print(")")
        }
    }

    //TODO SEVERE: check
    override fun renderExceptionCatchVariable(exception: CgVariable) {
        print("${exception.name.escapeNamePossibleKeyword()}: ${exception.type}")
    }

    override fun escapeNamePossibleKeywordImpl(s: String): String =
        if (isLanguageKeyword(s, context.codegenLanguage)) "`$s`" else s

    //TODO MINOR: check
    override fun String.escapeCharacters(): String =
        StringEscapeUtils.escapeJava(this)
            .replace("$", "\\$")
            .replace("\\f", "\\u000C")
            .replace("\\xxx", "\\\u0058\u0058\u0058")
}