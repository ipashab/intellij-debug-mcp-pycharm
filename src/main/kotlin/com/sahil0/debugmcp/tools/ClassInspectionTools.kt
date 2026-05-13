package com.sahil0.debugmcp.tools

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

fun registerClassInspectionTools(server: Server, project: Project) {

    // --- decompile_class ---
    server.addTool(
        name = "decompile_class",
        description = "Read decompiled source of a class from the project classpath (including dependency JARs). " +
                "IntelliJ's built-in decompiler produces Java source from .class files transparently.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("className", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Fully qualified class name, e.g. com.example.MyClass"))
                })
                put("lineStart", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Optional 1-based start line (for reading a specific range)"))
                })
                put("lineEnd", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Optional 1-based end line (inclusive)"))
                })
            },
            required = listOf("className")
        )
    ) { request ->
        val className = request.arguments?.get("className")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent(text = errorJson("Missing className"))))
        val lineStart = request.arguments?.get("lineStart")?.jsonPrimitive?.intOrNull
        val lineEnd = request.arguments?.get("lineEnd")?.jsonPrimitive?.intOrNull

        try {
            val result = readAction {
                val psiFile = findClassPsiFile(project, className)
                    ?: throw IllegalArgumentException(buildDiagnostic(project, className))

                val fullText = psiFile.text
                val allLines = fullText.lines()
                val totalLines = allLines.size

                val vFile = psiFile.virtualFile
                val fileUrl = vFile?.url ?: "unknown"
                val isDecompiled = vFile?.url?.contains(".jar!") == true ||
                        vFile?.url?.contains("jrt://") == true ||
                        vFile?.fileType?.name == "CLASS"

                // Apply line range if specified
                val outputLines = if (lineStart != null || lineEnd != null) {
                    val start = (lineStart ?: 1).coerceIn(1, totalLines)
                    val end = (lineEnd ?: totalLines).coerceIn(start, totalLines)
                    allLines.subList(start - 1, end)
                } else {
                    allLines
                }

                buildJsonObject {
                    put("className", className)
                    put("source", outputLines.joinToString("\n"))
                    put("totalLines", totalLines)
                    put("fileUrl", fileUrl)
                    put("isDecompiled", isDecompiled)
                    if (lineStart != null || lineEnd != null) {
                        put("lineStart", lineStart ?: 1)
                        put("lineEnd", lineEnd ?: totalLines)
                    }
                }
            }
            CallToolResult(content = listOf(TextContent(text = result.toString())))
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = errorJson("Failed to decompile class: ${e.message}"))))
        }
    }
}

/**
 * Find the PSI file for a fully qualified class name using multiple strategies:
 * 1. JavaPsiFacade index lookup (JDK, indexed Java JARs) — requires smart mode
 * 2. Direct .class file search on all classpath roots — works in dumb mode
 * 3. Project source root search (.kt / .java source files) — works in dumb mode
 */
private fun findClassPsiFile(project: Project, className: String): PsiFile? {
    val psiManager = PsiManager.getInstance(project)
    val isDumb = DumbService.getInstance(project).isDumb

    // Strategy 1: JavaPsiFacade — fast, index-based (requires smart mode)
    if (!isDumb) {
        try {
            val facade = JavaPsiFacade.getInstance(project)
            val psiClass = facade.findClass(className, GlobalSearchScope.allScope(project))
            if (psiClass?.containingFile != null) return psiClass.containingFile
        } catch (_: Exception) { }
    }

    // Strategy 2: Direct .class file search across all discoverable classpath roots
    val classRelPath = className.replace('.', '/') + ".class"
    val classRoots = collectAllClassRoots(project)

    for (root in classRoots) {
        val vf = root.findFileByRelativePath(classRelPath)
        if (vf != null) {
            val psi = psiManager.findFile(vf)
            if (psi != null) return psi
        }
    }

    // Strategy 2b: Inner class patterns (com.example.Outer.Inner -> Outer$Inner.class)
    val parts = className.split('.')
    if (parts.size >= 3) {
        for (splitAt in (parts.size - 2) downTo 1) {
            val dirPart = parts.subList(0, splitAt).joinToString("/")
            val classPart = parts.subList(splitAt, parts.size).joinToString("$")
            val innerPath = "$dirPart/$classPart.class"
            for (root in classRoots) {
                val vf = root.findFileByRelativePath(innerPath)
                if (vf != null) {
                    val psi = psiManager.findFile(vf)
                    if (psi != null) return psi
                }
            }
        }
    }

    // Strategy 3: Project source roots (.kt / .java files)
    val packagePath = className.substringBeforeLast('.', "").replace('.', '/')
    val simpleName = className.substringAfterLast('.')
    val sourceRoots = collectAllSourceRoots(project)

    for (root in sourceRoots) {
        val dir = if (packagePath.isEmpty()) root else root.findFileByRelativePath(packagePath) ?: continue
        // Try exact class name match
        for (ext in listOf("kt", "java")) {
            val vf = dir.findChild("$simpleName.$ext")
            if (vf != null) return psiManager.findFile(vf)
        }
        // Kotlin file facades: UtilsKt -> Utils.kt
        if (simpleName.endsWith("Kt")) {
            val vf = dir.findChild(simpleName.removeSuffix("Kt") + ".kt")
            if (vf != null) return psiManager.findFile(vf)
        }
    }

    return null
}

/**
 * Collect class roots from multiple sources to maximize coverage.
 * IntelliJ's project model may be incomplete (e.g. Maven system-scope deps,
 * partially resolved dependencies), so we cast a wide net.
 */
private fun collectAllClassRoots(project: Project): Set<VirtualFile> {
    val roots = LinkedHashSet<VirtualFile>()
    val jarFs = JarFileSystem.getInstance()

    // 1. Project-level OrderEnumerator
    try {
        roots.addAll(OrderEnumerator.orderEntries(project).classes().getRoots())
    } catch (_: Exception) { }

    // 2. Per-module OrderEnumerator (may find deps missed at project level)
    try {
        for (module in ModuleManager.getInstance(project).modules) {
            roots.addAll(OrderEnumerator.orderEntries(module).recursively().classes().getRoots())
        }
    } catch (_: Exception) { }

    // 3. Project library table
    try {
        val libTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        for (lib in libTable.libraries) {
            roots.addAll(lib.getFiles(OrderRootType.CLASSES))
        }
    } catch (_: Exception) { }

    // 4. Application-level (global) library table
    try {
        val globalTable = LibraryTablesRegistrar.getInstance().libraryTable
        for (lib in globalTable.libraries) {
            roots.addAll(lib.getFiles(OrderRootType.CLASSES))
        }
    } catch (_: Exception) { }

    // 5. Per-module library roots (direct enumeration of order entries)
    try {
        for (module in ModuleManager.getInstance(project).modules) {
            val mrm = ModuleRootManager.getInstance(module)
            for (entry in mrm.orderEntries) {
                try {
                    roots.addAll(entry.getFiles(OrderRootType.CLASSES))
                } catch (_: Exception) { }
            }
        }
    } catch (_: Exception) { }

    // 6. Scan pom.xml system-scope dependencies (IntelliJ SDK JARs)
    //    These use <systemPath> and may not be in the project model.
    try {
        val basePath = project.basePath
        if (basePath != null) {
            val pomIoFile = File(basePath, "pom.xml")
            if (pomIoFile.isFile) {
                val pomText = pomIoFile.readText(Charsets.UTF_8)
                val systemPaths = Regex("""<systemPath>\s*(.*?)\s*</systemPath>""")
                    .findAll(pomText)
                    .map { it.groupValues[1] }
                    .toList()

                // Resolve ${intellij.home} and other Maven properties from the pom
                val properties = mutableMapOf<String, String>()
                Regex("""<(\w[\w.]*?)>\s*(.*?)\s*</\1>""").findAll(pomText).forEach { m ->
                    val key = m.groupValues[1]
                    val value = m.groupValues[2]
                    if (!value.contains('<') && value.isNotEmpty()) {
                        properties[key] = value
                    }
                }

                for (rawPath in systemPaths) {
                    var resolved = rawPath
                    Regex("""\$\{(.*?)}""").findAll(rawPath).forEach { m ->
                        val propName = m.groupValues[1]
                        val propValue = properties[propName]
                        if (propValue != null) {
                            resolved = resolved.replace("\${$propName}", propValue)
                        }
                    }
                    val normalizedPath = resolved.replace('\\', '/')
                    val jarRoot = jarFs.findFileByPath("$normalizedPath!/")
                    if (jarRoot != null) {
                        roots.add(jarRoot)
                    }
                }
            }
        }
    } catch (_: Exception) { }

    // 7. IntelliJ IDE installation lib/ directory (for platform classes)
    try {
        val ideHome = PathManager.getHomePath()
        val libDir = File(ideHome, "lib")
        if (libDir.isDirectory) {
            libDir.listFiles()?.filter { it.extension == "jar" }?.forEach { jar ->
                val jarRoot = jarFs.findFileByPath("${jar.absolutePath.replace('\\', '/')}!/")
                if (jarRoot != null) roots.add(jarRoot)
            }
        }
    } catch (_: Exception) { }

    return roots
}

/**
 * Collect source roots from multiple sources.
 */
private fun collectAllSourceRoots(project: Project): Set<VirtualFile> {
    val roots = LinkedHashSet<VirtualFile>()

    // 1. ProjectRootManager
    try {
        roots.addAll(ProjectRootManager.getInstance(project).contentSourceRoots)
    } catch (_: Exception) { }

    // 2. Per-module source roots
    try {
        for (module in ModuleManager.getInstance(project).modules) {
            roots.addAll(ModuleRootManager.getInstance(module).sourceRoots)
        }
    } catch (_: Exception) { }

    // 3. Fallback: check common source directories relative to project base
    try {
        val basePath = project.basePath
        if (basePath != null && roots.isEmpty()) {
            val lfs = LocalFileSystem.getInstance()
            for (srcDir in listOf("src/main/kotlin", "src/main/java", "src/main/scala")) {
                val ioDir = File(basePath, srcDir)
                if (ioDir.isDirectory) {
                    val vf = lfs.findFileByIoFile(ioDir)
                    if (vf != null) roots.add(vf)
                }
            }
        }
    } catch (_: Exception) { }

    return roots
}

private fun buildDiagnostic(project: Project, className: String): String {
    val sb = StringBuilder("Class not found: $className | ")
    try {
        val basePath = project.basePath ?: "null"
        sb.append("basePath=$basePath | ")
        sb.append("isDumb=${DumbService.getInstance(project).isDumb} | ")

        val classRoots = collectAllClassRoots(project)
        sb.append("classRoots=${classRoots.size} | ")
        classRoots.take(5).forEachIndexed { i, r -> sb.append("root[$i]=${r.url} | ") }

        val sourceRoots = collectAllSourceRoots(project)
        sb.append("sourceRoots=${sourceRoots.size} | ")
        sourceRoots.take(3).forEachIndexed { i, r -> sb.append("src[$i]=${r.url} | ") }

        val pomFile = File(basePath, "pom.xml")
        sb.append("pomExists=${pomFile.isFile} | ")
        if (pomFile.isFile) {
            val pomText = pomFile.readText()
            val sysPaths = Regex("""<systemPath>\s*(.*?)\s*</systemPath>""")
                .findAll(pomText).map { it.groupValues[1] }.toList()
            sb.append("systemPaths=${sysPaths.size} | ")
            if (sysPaths.isNotEmpty()) sb.append("firstSysPath=${sysPaths.first()} | ")
            val props = mutableMapOf<String, String>()
            Regex("""<(\w[\w.]*?)>\s*(.*?)\s*</\1>""").findAll(pomText).forEach { m ->
                val k = m.groupValues[1]; val v = m.groupValues[2]
                if (!v.contains('<') && v.isNotEmpty()) props[k] = v
            }
            sb.append("intellij.home=${props["intellij.home"] ?: "NOT_FOUND"} | ")
        }

        val modules = ModuleManager.getInstance(project).modules
        sb.append("modules=${modules.size} | ")
        modules.forEach { sb.append("mod=${it.name} | ") }
    } catch (e: Exception) {
        sb.append("diagError=${e.message}")
    }
    return sb.toString()
}
