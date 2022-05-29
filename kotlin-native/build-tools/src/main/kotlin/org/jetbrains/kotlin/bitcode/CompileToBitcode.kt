/*
 * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.bitcode

import kotlinBuildProperties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.getByType
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.jetbrains.kotlin.ExecClang
import org.jetbrains.kotlin.execLlvmUtility
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.PlatformManager
import org.jetbrains.kotlin.konan.target.SanitizerKind
import org.jetbrains.kotlin.utils.Maybe
import java.io.File
import javax.inject.Inject

private abstract class CompileToBitcodeJob : WorkAction<CompileToBitcodeJob.Parameters> {
    interface Parameters : WorkParameters {
        val workingDirectory: DirectoryProperty
        // TODO: Figure out a way to pass KonanTarget, but it is used as a key into PlatformManager,
        //       so object identity matters, and platform managers are different between project and worker sides.
        val targetName: Property<String>
        val compilerExecutable: Property<String>
        val compilerInputFiles: ConfigurableFileCollection
        val compilerArgs: ListProperty<String>
        val llvmLinkOutputFile: RegularFileProperty
        val llvmLinkArgs: ListProperty<String>
        val platformManager: Property<PlatformManager>
    }

    @get:Inject
    abstract val objects: ObjectFactory

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun execute() {
        with(parameters) {
            workingDirectory.asFile.get().mkdirs()

            val execClang = ExecClang.create(objects, platformManager.get())

            // TODO: Compile each input separately and make workingDir point to src root.
            execClang.execKonanClang(targetName.get()) {
                workingDir = workingDirectory.asFile.get()
                executable = compilerExecutable.get()
                args = compilerArgs.get() + compilerInputFiles.map { it.absolutePath }
            }

            val compilerOutputFiles = compilerInputFiles.map { workingDirectory.file("${it.nameWithoutExtension}.bc").get().asFile }

            // TODO: Extract llvm-link out. This will allow parallelizing clang compilation.
            execOperations.execLlvmUtility(platformManager.get(), "llvm-link") {
                args = listOf("-o", llvmLinkOutputFile.asFile.get().absolutePath) + llvmLinkArgs.get() + compilerOutputFiles.map { it.absolutePath }
            }
        }
    }
}

abstract class CompileToBitcode @Inject constructor(
        @Input val target: KonanTarget,
        private val _sanitizer: Maybe<SanitizerKind>,
) : DefaultTask() {

    @get:Input
    @get:Optional
    val sanitizer
        get() = _sanitizer.orNull

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    // TODO: Generate output files for this.
    @get:Internal
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val compiler: Property<String>

    @get:Input
    abstract val linkerArgs: ListProperty<String>

    // Marked as input via [compilerFlags].
    @get:Internal
    abstract val compilerArgs: ListProperty<String>

    // Marked as input via [headers] and [compilerFlags].
    @get:Internal
    abstract val headersDirs: ConfigurableFileCollection

    // TODO: Move to module description.
    @get:Internal
    abstract val moduleName: Property<String>

    @get:Input
    val compilerFlags: Provider<List<String>> = project.provider {
        listOfNotNull(
                "-gdwarf-2".takeIf { project.kotlinBuildProperties.getBoolean("kotlin.native.isNativeRuntimeDebugInfoEnabled", false) },
                "-c",
                "-emit-llvm"
        ) + headersDirs.map { "-I${it.absolutePath}" } + when (sanitizer) {
            null -> listOf()
            SanitizerKind.ADDRESS -> listOf("-fsanitize=address")
            SanitizerKind.THREAD -> listOf("-fsanitize=thread")
        } + compilerArgs.get()
    }

    @get:SkipWhenEmpty
    @get:InputFiles
    abstract val inputFiles: ConfigurableFileTree

    @get:InputFiles
    protected val headers: Provider<List<File>> = project.provider {
        // Not using clang's -M* flags because there's a problem with our current include system:
        // We allow includes relative to the current directory and also pass -I for each imported module
        // Given file tree:
        // a:
        //  header.hpp
        // b:
        //  impl.cpp
        // Assume module b adds a to its include path.
        // If b/impl.cpp has #include "header.hpp", it'll be included from a/header.hpp. If we add another file
        // header.hpp into b/, the next compilation of b/impl.cpp will include b/header.hpp. -M flags, however,
        // won't generate a dependency on b/header.hpp, so incremental compilation will be broken.
        // TODO: Apart from dependency generation this also makes it awkward to have two files with
        //       the same name (e.g. Utils.h) in directories a/ and b/: For the b/impl.cpp to include a/header.hpp
        //       it needs to have #include "../a/header.hpp"

        val dirs = mutableSetOf<File>()
        // First add dirs with sources, as clang by default adds directory with the source to the include path.
        inputFiles.forEach {
            dirs.add(it.parentFile)
        }
        // Now add manually given header dirs.
        dirs.addAll(headersDirs.files)
        dirs.flatMap { dir ->
            project.fileTree(dir) {
                include("**/*.h", "**/*.hpp")
            }.files
        }
    }

    @get:Inject
    protected abstract val workerExecutor: WorkerExecutor

    private val platformManager = project.extensions.getByType<PlatformManager>()

    @TaskAction
    fun compile() {
        val workQueue = workerExecutor.noIsolation()

        workQueue.submit(CompileToBitcodeJob::class.java) {
            workingDirectory.set(this@CompileToBitcode.outputDirectory)
            targetName.set(this@CompileToBitcode.target.name)
            compilerExecutable.set(this@CompileToBitcode.compiler)
            compilerArgs.set(this@CompileToBitcode.compilerFlags)
            compilerInputFiles.from(this@CompileToBitcode.inputFiles)
            llvmLinkOutputFile.set(this@CompileToBitcode.outputFile)
            llvmLinkArgs.set(this@CompileToBitcode.linkerArgs)
            platformManager.set(this@CompileToBitcode.platformManager)
        }
    }
}
