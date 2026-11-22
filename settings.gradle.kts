rootProject.name = "ZafiroLatino"

// Incluye automáticamente cada carpeta que tenga un build.gradle.kts
File(rootDir, ".").eachDir { dir ->
    if (File(dir, "build.gradle.kts").exists()) include(dir.name)
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
