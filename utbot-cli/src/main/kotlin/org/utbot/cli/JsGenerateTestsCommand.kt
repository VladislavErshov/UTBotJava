package org.utbot.cli

import api.JsTestGenerator
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import mu.KotlinLogging
import utils.JsCmdExec

private val logger = KotlinLogging.logger {}


class JsGenerateTestsCommand : CliktCommand(name = "generate_js", help = "Generates tests for the specified class or toplevel functions") {

    private val sourceCodeFile by option(
        "-s", "--source",
        help = "Specifies source code file for a generated test"
    )
        .required()
        .check("Must exist and ends with .js suffix") {
            it.endsWith(".js") && Files.exists(Paths.get(it))
        }

    private val targetClass by option("-c", "--class", help = "Specifies target class to generate tests for")

    private val output by option("-o", "--output", help = "Specifies output file for generated tests")
        .check("Must end with .js suffix") {
            it.endsWith(".js")
        }

    private val printToStdOut by option(
        "-p",
        "--print-test",
        help = "Specifies whether test should be printed out to StdOut"
    )
        .flag(default = false)

    private val timeout by option(
        "-t",
        "--timeout",
        help = "Timeout for Node.js to run scripts in seconds"
    ).default("5")

    override fun run() {

        val started = LocalDateTime.now()
        try {
            logger.debug { "Installing npm packages" }
            installDeps(sourceCodeFile.substringBeforeLast(File.separator))
            logger.debug { "Generating test for [$sourceCodeFile] - started" }
            val fileText = File(sourceCodeFile).readText()
            val testGenerator = JsTestGenerator(
                fileText = fileText,
                sourceFilePath = sourceCodeFile,
                parentClassName = targetClass,
                outputFilePath = output,
                exportsManager = ::manageExports,
                timeout = timeout.toLong()
            )
            val testCode = testGenerator.run()

            if (printToStdOut || (output == null && !printToStdOut)) {
                logger.info { "\n$testCode" }
            }
            output?.let { filePath ->
                val outputFile = File(filePath)
                outputFile.writeText(testCode)
                outputFile.createNewFile()
            }

        } catch (t: Throwable) {
            logger.error { "An error has occurred while generating tests for file $sourceCodeFile : $t" }
            throw t
        } finally {
            val duration = ChronoUnit.MILLIS.between(started, LocalDateTime.now())
            logger.debug { "Generating test for [$sourceCodeFile] - completed in [$duration] (ms)" }
        }
    }

    private fun manageExports(exports: List<String>) {
        val startComment = "// Start of exports generated by UTBot"
        val endComment = "// End of exports generated by UTBot"
        val exportLine = exports.joinToString(", ")
        val file = File(sourceCodeFile)
        val fileText = file.readText()
        when {
            fileText.contains("module.exports = {$exportLine}") -> {}
            fileText.contains(startComment) && !fileText.contains("module.exports = {$exportLine}") -> {
                val regex = Regex("\n$startComment\n(.*)\n$endComment")
                regex.find(fileText)?.groups?.get(1)?.value?.let {
                    val swappedText = fileText.replace(it, "module.exports = {$exportLine}")
                    file.writeText(swappedText)
                }
            }
            else -> {
                val line = buildString {
                    append("\n$startComment")
                    append("\nmodule.exports = {$exportLine}")
                    append("\n$endComment")
                }
                file.appendText(line)
            }
        }
    }

    private fun installDeps(dir: String) {
        JsCmdExec.runCommand(
            "npm i -D nyc",
            dir,
            true,
            20
        )
        JsCmdExec.runCommand(
            "npm i -g mocha",
            dir,
            true,
            20
        )
    }
}