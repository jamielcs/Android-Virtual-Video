package id.armagic.virtualvideo

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import id.armagic.virtualvideo.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener

            updateShizukuStatus()

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startInspect()
            } else {
                binding.resultText.text =
                    "SHIZUKU PERMISSION: DENIED\n\nIzinkan Vir Vid 8 di Shizuku lalu tekan INSPECT lagi."
            }
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            runOnUiThread { updateShizukuStatus() }
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            runOnUiThread { updateShizukuStatus() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        binding.scanButton.setOnClickListener {
            requestOrInspect()
        }

        updateShizukuStatus()
    }

    private fun updateShizukuStatus() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        if (!alive) {
            binding.shizukuStatusText.text = "Shizuku: TIDAK AKTIF"
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)

        binding.shizukuStatusText.text =
            if (granted) {
                "Shizuku: AKTIF • GRANTED • UID $uid"
            } else {
                "Shizuku: AKTIF • BELUM DIIZINKAN • UID $uid"
            }
    }

    private fun requestOrInspect() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            binding.resultText.text =
                "Shizuku belum aktif.\n\nJalankan Shizuku lalu kembali ke Vir Vid 8."
            updateShizukuStatus()
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!granted) {
            runCatching {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }.onFailure {
                binding.resultText.text =
                    "Gagal meminta permission Shizuku:\n${it.javaClass.simpleName}: ${it.message}"
            }
            return
        }

        startInspect()
    }

    private fun startInspect() {
        binding.scanButton.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.resultText.text =
            "INSPECTING CAMERAOPT...\n\nMembaca Binder descriptor, class Stub, transaction constants, dan lokasi framework."

        thread(name = "cameraopt-binder-inspect") {
            val report = buildReport()

            runOnUiThread {
                binding.resultText.text = report
                binding.progressBar.visibility = android.view.View.GONE
                binding.scanButton.isEnabled = true
                updateShizukuStatus()
            }
        }
    }

    private fun buildReport(): String {
        val reflection = inspectClasses()

        val shellScript = """
            echo '=== SERVICE ENTRY ==='
            service list 2>&1 | grep -i 'cameraopt' || true
            service check cameraopt 2>&1 || true

            echo
            echo '=== STANDARD BINDER INTERFACE_TRANSACTION ==='
            service call cameraopt 1598968902 2>&1 || true

            echo
            echo '=== DUMPSYS CAMERAOPT ==='
            dumpsys cameraopt 2>&1 | head -n 220 || true

            echo
            echo '=== CANDIDATE FILES ==='
            for d in /system/framework /system_ext/framework /product/framework /vendor/framework /system_ext/priv-app /product/priv-app /vendor/app; do
              [ -d "${' ] || continue
              find "$d" -maxdepth 3 -type f \( -iname '*cameraopt*' -o -iname '*camera*opt*' -o -iname '*miui*camera*' \) 2>/dev/null | head -n 120
            done

            echo
            echo '=== INTERFACE STRING LOCATIONS ==='
            for d in /system/framework /system_ext/framework /product/framework /vendor/framework; do
              [ -d "$d" ] || continue
              grep -R -a -l -m 1 'com.miui.cameraopt.ICameraOptManager' "$d" 2>/dev/null | head -n 80
            done

            echo
            echo '=== PACKAGE PATH HINTS ==='
            pm list packages -f 2>&1 | grep -Ei 'cameraopt|miui.*camera|camera.*miui' | head -n 100 || true
        """.trimIndent()

        val shell = runShell(shellScript)

        val allText = reflection + "\n" + shell.output + "\n" + shell.error
        val interesting = extractInteresting(allText)

        return buildString {
            appendLine("=== VIR VID 8 • CAMERAOPT BINDER INSPECTOR ===")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} • API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Shizuku UID: ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
            appendLine()

            appendLine("=== IMPORTANT FINDINGS ===")
            if (interesting.isEmpty()) {
                appendLine("NONE")
            } else {
                interesting.forEach { appendLine(it) }
            }

            appendLine()
            appendLine(reflection)

            appendLine()
            appendLine("=== SHIZUKU CAMERAOPT INSPECTION ===")
            appendLine("Exit: ${shell.exitCode}")
            if (shell.output.isNotBlank()) appendLine(shell.output.trim())
            if (shell.error.isNotBlank()) {
                appendLine()
                appendLine("--- STDERR ---")
                appendLine(shell.error.trim())
            }

            appendLine()
            appendLine("=== DONE ===")
        }
    }

    private fun inspectClasses(): String {
        val candidates = listOf(
            "com.miui.cameraopt.ICameraOptManager",
            "com.miui.cameraopt.ICameraOptManager\$Stub",
            "com.miui.cameraopt.ICameraOptManager\$Stub\$Proxy",
            "com.miui.cameraopt.MiuiCameraManager",
            "com.miui.cameraopt.CameraOptManager"
        )

        return buildString {
            appendLine("=== JAVA REFLECTION ===")

            for (className in candidates) {
                appendLine()
                appendLine("[$className]")

                val clazz = runCatching { Class.forName(className) }.getOrNull()

                if (clazz == null) {
                    appendLine("CLASS: NOT EXPOSED TO APP CLASSLOADER")
                    continue
                }

                appendLine("CLASS: FOUND")
                appendLine("SUPER: ${clazz.superclass?.name ?: "-"}")
                appendLine("INTERFACES: ${clazz.interfaces.joinToString(", ") { it.name }.ifBlank { "-" }}")

                val fields: List<Field> = runCatching {
                    clazz.declaredFields.toList().sortedBy { it.name }
                }.getOrElse { emptyList() }

                if (fields.isNotEmpty()) {
                    appendLine("FIELDS:")
                    fields.take(160).forEach { field ->
                        val staticValue = if (Modifier.isStatic(field.modifiers)) {
                            runCatching {
                                field.isAccessible = true
                                field.get(null)?.toString()
                            }.getOrNull()
                        } else {
                            null
                        }

                        appendLine(
                            "  ${field.type.simpleName} ${field.name}" +
                                if (staticValue != null) " = $staticValue" else ""
                        )
                    }
                }

                val methods: List<Method> = runCatching {
                    clazz.declaredMethods.toList().sortedWith(
                        compareBy<Method>({ it.name.lowercase() }, { it.parameterCount })
                    )
                }.getOrElse { emptyList() }

                if (methods.isNotEmpty()) {
                    appendLine("METHODS:")
                    methods.take(180).forEach { method ->
                        appendLine(
                            "  ${method.returnType.simpleName} ${method.name}(" +
                                method.parameterTypes.joinToString(", ") { it.simpleName } +
                                ")"
                        )
                    }
                }
            }
        }
    }

    private fun extractInteresting(text: String): List<String> {
        val keys = listOf(
            "descriptor",
            "transaction_",
            "cameraopt",
            "virtual",
            "inject",
            "external",
            "source",
            "surface",
            "stream",
            "open",
            "close",
            "camera"
        )

        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    keys.any { key -> line.lowercase().contains(key) }
            }
            .filterNot { it.startsWith("package:") }
            .distinct()
            .take(120)
            .toList()
    }

    private fun runShell(script: String): ShellResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", script),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()

            ShellResult(exit, stdout, stderr)
        } catch (t: Throwable) {
            ShellResult(
                -1,
                "",
                "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 8008
    }
}
}d" ] || continue
              find "${' -maxdepth 3 -type f \( -iname '*cameraopt*' -o -iname '*camera*opt*' -o -iname '*miui*camera*' \) 2>/dev/null | head -n 120
            done

            echo
            echo '=== INTERFACE STRING LOCATIONS ==='
            for d in /system/framework /system_ext/framework /product/framework /vendor/framework; do
              [ -d "$d" ] || continue
              grep -R -a -l -m 1 'com.miui.cameraopt.ICameraOptManager' "$d" 2>/dev/null | head -n 80
            done

            echo
            echo '=== PACKAGE PATH HINTS ==='
            pm list packages -f 2>&1 | grep -Ei 'cameraopt|miui.*camera|camera.*miui' | head -n 100 || true
        """.trimIndent()

        val shell = runShell(shellScript)

        val allText = reflection + "\n" + shell.output + "\n" + shell.error
        val interesting = extractInteresting(allText)

        return buildString {
            appendLine("=== VIR VID 8 • CAMERAOPT BINDER INSPECTOR ===")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} • API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Shizuku UID: ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
            appendLine()

            appendLine("=== IMPORTANT FINDINGS ===")
            if (interesting.isEmpty()) {
                appendLine("NONE")
            } else {
                interesting.forEach { appendLine(it) }
            }

            appendLine()
            appendLine(reflection)

            appendLine()
            appendLine("=== SHIZUKU CAMERAOPT INSPECTION ===")
            appendLine("Exit: ${shell.exitCode}")
            if (shell.output.isNotBlank()) appendLine(shell.output.trim())
            if (shell.error.isNotBlank()) {
                appendLine()
                appendLine("--- STDERR ---")
                appendLine(shell.error.trim())
            }

            appendLine()
            appendLine("=== DONE ===")
        }
    }

    private fun inspectClasses(): String {
        val candidates = listOf(
            "com.miui.cameraopt.ICameraOptManager",
            "com.miui.cameraopt.ICameraOptManager\$Stub",
            "com.miui.cameraopt.ICameraOptManager\$Stub\$Proxy",
            "com.miui.cameraopt.MiuiCameraManager",
            "com.miui.cameraopt.CameraOptManager"
        )

        return buildString {
            appendLine("=== JAVA REFLECTION ===")

            for (className in candidates) {
                appendLine()
                appendLine("[$className]")

                val clazz = runCatching { Class.forName(className) }.getOrNull()

                if (clazz == null) {
                    appendLine("CLASS: NOT EXPOSED TO APP CLASSLOADER")
                    continue
                }

                appendLine("CLASS: FOUND")
                appendLine("SUPER: ${clazz.superclass?.name ?: "-"}")
                appendLine("INTERFACES: ${clazz.interfaces.joinToString(", ") { it.name }.ifBlank { "-" }}")

                val fields: List<Field> = runCatching {
                    clazz.declaredFields.toList().sortedBy { it.name }
                }.getOrElse { emptyList() }

                if (fields.isNotEmpty()) {
                    appendLine("FIELDS:")
                    fields.take(160).forEach { field ->
                        val staticValue = if (Modifier.isStatic(field.modifiers)) {
                            runCatching {
                                field.isAccessible = true
                                field.get(null)?.toString()
                            }.getOrNull()
                        } else {
                            null
                        }

                        appendLine(
                            "  ${field.type.simpleName} ${field.name}" +
                                if (staticValue != null) " = $staticValue" else ""
                        )
                    }
                }

                val methods: List<Method> = runCatching {
                    clazz.declaredMethods.toList().sortedWith(
                        compareBy<Method>({ it.name.lowercase() }, { it.parameterCount })
                    )
                }.getOrElse { emptyList() }

                if (methods.isNotEmpty()) {
                    appendLine("METHODS:")
                    methods.take(180).forEach { method ->
                        appendLine(
                            "  ${method.returnType.simpleName} ${method.name}(" +
                                method.parameterTypes.joinToString(", ") { it.simpleName } +
                                ")"
                        )
                    }
                }
            }
        }
    }

    private fun extractInteresting(text: String): List<String> {
        val keys = listOf(
            "descriptor",
            "transaction_",
            "cameraopt",
            "virtual",
            "inject",
            "external",
            "source",
            "surface",
            "stream",
            "open",
            "close",
            "camera"
        )

        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    keys.any { key -> line.lowercase().contains(key) }
            }
            .filterNot { it.startsWith("package:") }
            .distinct()
            .take(120)
            .toList()
    }

    private fun runShell(script: String): ShellResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", script),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()

            ShellResult(exit, stdout, stderr)
        } catch (t: Throwable) {
            ShellResult(
                -1,
                "",
                "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 8008
    }
}
}d" -maxdepth 3 -type f \( -iname '*cameraopt*' -o -iname '*camera*opt*' -o -iname '*miui*camera*' \) 2>/dev/null | head -n 120
            done

            echo
            echo '=== INTERFACE STRING LOCATIONS ==='
            for d in /system/framework /system_ext/framework /product/framework /vendor/framework; do
              [ -d "${' ] || continue
              grep -R -a -l -m 1 'com.miui.cameraopt.ICameraOptManager' "$d" 2>/dev/null | head -n 80
            done

            echo
            echo '=== PACKAGE PATH HINTS ==='
            pm list packages -f 2>&1 | grep -Ei 'cameraopt|miui.*camera|camera.*miui' | head -n 100 || true
        """.trimIndent()

        val shell = runShell(shellScript)

        val allText = reflection + "\n" + shell.output + "\n" + shell.error
        val interesting = extractInteresting(allText)

        return buildString {
            appendLine("=== VIR VID 8 • CAMERAOPT BINDER INSPECTOR ===")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} • API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Shizuku UID: ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
            appendLine()

            appendLine("=== IMPORTANT FINDINGS ===")
            if (interesting.isEmpty()) {
                appendLine("NONE")
            } else {
                interesting.forEach { appendLine(it) }
            }

            appendLine()
            appendLine(reflection)

            appendLine()
            appendLine("=== SHIZUKU CAMERAOPT INSPECTION ===")
            appendLine("Exit: ${shell.exitCode}")
            if (shell.output.isNotBlank()) appendLine(shell.output.trim())
            if (shell.error.isNotBlank()) {
                appendLine()
                appendLine("--- STDERR ---")
                appendLine(shell.error.trim())
            }

            appendLine()
            appendLine("=== DONE ===")
        }
    }

    private fun inspectClasses(): String {
        val candidates = listOf(
            "com.miui.cameraopt.ICameraOptManager",
            "com.miui.cameraopt.ICameraOptManager\$Stub",
            "com.miui.cameraopt.ICameraOptManager\$Stub\$Proxy",
            "com.miui.cameraopt.MiuiCameraManager",
            "com.miui.cameraopt.CameraOptManager"
        )

        return buildString {
            appendLine("=== JAVA REFLECTION ===")

            for (className in candidates) {
                appendLine()
                appendLine("[$className]")

                val clazz = runCatching { Class.forName(className) }.getOrNull()

                if (clazz == null) {
                    appendLine("CLASS: NOT EXPOSED TO APP CLASSLOADER")
                    continue
                }

                appendLine("CLASS: FOUND")
                appendLine("SUPER: ${clazz.superclass?.name ?: "-"}")
                appendLine("INTERFACES: ${clazz.interfaces.joinToString(", ") { it.name }.ifBlank { "-" }}")

                val fields: List<Field> = runCatching {
                    clazz.declaredFields.toList().sortedBy { it.name }
                }.getOrElse { emptyList() }

                if (fields.isNotEmpty()) {
                    appendLine("FIELDS:")
                    fields.take(160).forEach { field ->
                        val staticValue = if (Modifier.isStatic(field.modifiers)) {
                            runCatching {
                                field.isAccessible = true
                                field.get(null)?.toString()
                            }.getOrNull()
                        } else {
                            null
                        }

                        appendLine(
                            "  ${field.type.simpleName} ${field.name}" +
                                if (staticValue != null) " = $staticValue" else ""
                        )
                    }
                }

                val methods: List<Method> = runCatching {
                    clazz.declaredMethods.toList().sortedWith(
                        compareBy<Method>({ it.name.lowercase() }, { it.parameterCount })
                    )
                }.getOrElse { emptyList() }

                if (methods.isNotEmpty()) {
                    appendLine("METHODS:")
                    methods.take(180).forEach { method ->
                        appendLine(
                            "  ${method.returnType.simpleName} ${method.name}(" +
                                method.parameterTypes.joinToString(", ") { it.simpleName } +
                                ")"
                        )
                    }
                }
            }
        }
    }

    private fun extractInteresting(text: String): List<String> {
        val keys = listOf(
            "descriptor",
            "transaction_",
            "cameraopt",
            "virtual",
            "inject",
            "external",
            "source",
            "surface",
            "stream",
            "open",
            "close",
            "camera"
        )

        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    keys.any { key -> line.lowercase().contains(key) }
            }
            .filterNot { it.startsWith("package:") }
            .distinct()
            .take(120)
            .toList()
    }

    private fun runShell(script: String): ShellResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", script),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()

            ShellResult(exit, stdout, stderr)
        } catch (t: Throwable) {
            ShellResult(
                -1,
                "",
                "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 8008
    }
}
}d" ] || continue
              grep -R -a -l -m 1 'com.miui.cameraopt.ICameraOptManager' "${' 2>/dev/null | head -n 80
            done

            echo
            echo '=== PACKAGE PATH HINTS ==='
            pm list packages -f 2>&1 | grep -Ei 'cameraopt|miui.*camera|camera.*miui' | head -n 100 || true
        """.trimIndent()

        val shell = runShell(shellScript)

        val allText = reflection + "\n" + shell.output + "\n" + shell.error
        val interesting = extractInteresting(allText)

        return buildString {
            appendLine("=== VIR VID 8 • CAMERAOPT BINDER INSPECTOR ===")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} • API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Shizuku UID: ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
            appendLine()

            appendLine("=== IMPORTANT FINDINGS ===")
            if (interesting.isEmpty()) {
                appendLine("NONE")
            } else {
                interesting.forEach { appendLine(it) }
            }

            appendLine()
            appendLine(reflection)

            appendLine()
            appendLine("=== SHIZUKU CAMERAOPT INSPECTION ===")
            appendLine("Exit: ${shell.exitCode}")
            if (shell.output.isNotBlank()) appendLine(shell.output.trim())
            if (shell.error.isNotBlank()) {
                appendLine()
                appendLine("--- STDERR ---")
                appendLine(shell.error.trim())
            }

            appendLine()
            appendLine("=== DONE ===")
        }
    }

    private fun inspectClasses(): String {
        val candidates = listOf(
            "com.miui.cameraopt.ICameraOptManager",
            "com.miui.cameraopt.ICameraOptManager\$Stub",
            "com.miui.cameraopt.ICameraOptManager\$Stub\$Proxy",
            "com.miui.cameraopt.MiuiCameraManager",
            "com.miui.cameraopt.CameraOptManager"
        )

        return buildString {
            appendLine("=== JAVA REFLECTION ===")

            for (className in candidates) {
                appendLine()
                appendLine("[$className]")

                val clazz = runCatching { Class.forName(className) }.getOrNull()

                if (clazz == null) {
                    appendLine("CLASS: NOT EXPOSED TO APP CLASSLOADER")
                    continue
                }

                appendLine("CLASS: FOUND")
                appendLine("SUPER: ${clazz.superclass?.name ?: "-"}")
                appendLine("INTERFACES: ${clazz.interfaces.joinToString(", ") { it.name }.ifBlank { "-" }}")

                val fields: List<Field> = runCatching {
                    clazz.declaredFields.toList().sortedBy { it.name }
                }.getOrElse { emptyList() }

                if (fields.isNotEmpty()) {
                    appendLine("FIELDS:")
                    fields.take(160).forEach { field ->
                        val staticValue = if (Modifier.isStatic(field.modifiers)) {
                            runCatching {
                                field.isAccessible = true
                                field.get(null)?.toString()
                            }.getOrNull()
                        } else {
                            null
                        }

                        appendLine(
                            "  ${field.type.simpleName} ${field.name}" +
                                if (staticValue != null) " = $staticValue" else ""
                        )
                    }
                }

                val methods: List<Method> = runCatching {
                    clazz.declaredMethods.toList().sortedWith(
                        compareBy<Method>({ it.name.lowercase() }, { it.parameterCount })
                    )
                }.getOrElse { emptyList() }

                if (methods.isNotEmpty()) {
                    appendLine("METHODS:")
                    methods.take(180).forEach { method ->
                        appendLine(
                            "  ${method.returnType.simpleName} ${method.name}(" +
                                method.parameterTypes.joinToString(", ") { it.simpleName } +
                                ")"
                        )
                    }
                }
            }
        }
    }

    private fun extractInteresting(text: String): List<String> {
        val keys = listOf(
            "descriptor",
            "transaction_",
            "cameraopt",
            "virtual",
            "inject",
            "external",
            "source",
            "surface",
            "stream",
            "open",
            "close",
            "camera"
        )

        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    keys.any { key -> line.lowercase().contains(key) }
            }
            .filterNot { it.startsWith("package:") }
            .distinct()
            .take(120)
            .toList()
    }

    private fun runShell(script: String): ShellResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", script),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()

            ShellResult(exit, stdout, stderr)
        } catch (t: Throwable) {
            ShellResult(
                -1,
                "",
                "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 8008
    }
}
}d" 2>/dev/null | head -n 80
            done

            echo
            echo '=== PACKAGE PATH HINTS ==='
            pm list packages -f 2>&1 | grep -Ei 'cameraopt|miui.*camera|camera.*miui' | head -n 100 || true
        """.trimIndent()

        val shell = runShell(shellScript)

        val allText = reflection + "\n" + shell.output + "\n" + shell.error
        val interesting = extractInteresting(allText)

        return buildString {
            appendLine("=== VIR VID 8 • CAMERAOPT BINDER INSPECTOR ===")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} • API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Shizuku UID: ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
            appendLine()

            appendLine("=== IMPORTANT FINDINGS ===")
            if (interesting.isEmpty()) {
                appendLine("NONE")
            } else {
                interesting.forEach { appendLine(it) }
            }

            appendLine()
            appendLine(reflection)

            appendLine()
            appendLine("=== SHIZUKU CAMERAOPT INSPECTION ===")
            appendLine("Exit: ${shell.exitCode}")
            if (shell.output.isNotBlank()) appendLine(shell.output.trim())
            if (shell.error.isNotBlank()) {
                appendLine()
                appendLine("--- STDERR ---")
                appendLine(shell.error.trim())
            }

            appendLine()
            appendLine("=== DONE ===")
        }
    }

    private fun inspectClasses(): String {
        val candidates = listOf(
            "com.miui.cameraopt.ICameraOptManager",
            "com.miui.cameraopt.ICameraOptManager\$Stub",
            "com.miui.cameraopt.ICameraOptManager\$Stub\$Proxy",
            "com.miui.cameraopt.MiuiCameraManager",
            "com.miui.cameraopt.CameraOptManager"
        )

        return buildString {
            appendLine("=== JAVA REFLECTION ===")

            for (className in candidates) {
                appendLine()
                appendLine("[$className]")

                val clazz = runCatching { Class.forName(className) }.getOrNull()

                if (clazz == null) {
                    appendLine("CLASS: NOT EXPOSED TO APP CLASSLOADER")
                    continue
                }

                appendLine("CLASS: FOUND")
                appendLine("SUPER: ${clazz.superclass?.name ?: "-"}")
                appendLine("INTERFACES: ${clazz.interfaces.joinToString(", ") { it.name }.ifBlank { "-" }}")

                val fields: List<Field> = runCatching {
                    clazz.declaredFields.toList().sortedBy { it.name }
                }.getOrElse { emptyList() }

                if (fields.isNotEmpty()) {
                    appendLine("FIELDS:")
                    fields.take(160).forEach { field ->
                        val staticValue = if (Modifier.isStatic(field.modifiers)) {
                            runCatching {
                                field.isAccessible = true
                                field.get(null)?.toString()
                            }.getOrNull()
                        } else {
                            null
                        }

                        appendLine(
                            "  ${field.type.simpleName} ${field.name}" +
                                if (staticValue != null) " = $staticValue" else ""
                        )
                    }
                }

                val methods: List<Method> = runCatching {
                    clazz.declaredMethods.toList().sortedWith(
                        compareBy<Method>({ it.name.lowercase() }, { it.parameterCount })
                    )
                }.getOrElse { emptyList() }

                if (methods.isNotEmpty()) {
                    appendLine("METHODS:")
                    methods.take(180).forEach { method ->
                        appendLine(
                            "  ${method.returnType.simpleName} ${method.name}(" +
                                method.parameterTypes.joinToString(", ") { it.simpleName } +
                                ")"
                        )
                    }
                }
            }
        }
    }

    private fun extractInteresting(text: String): List<String> {
        val keys = listOf(
            "descriptor",
            "transaction_",
            "cameraopt",
            "virtual",
            "inject",
            "external",
            "source",
            "surface",
            "stream",
            "open",
            "close",
            "camera"
        )

        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    keys.any { key -> line.lowercase().contains(key) }
            }
            .filterNot { it.startsWith("package:") }
            .distinct()
            .take(120)
            .toList()
    }

    private fun runShell(script: String): ShellResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", script),
                null,
                null
            ) as Process

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()

            ShellResult(exit, stdout, stderr)
        } catch (t: Throwable) {
            ShellResult(
                -1,
                "",
                "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 8008
    }
}
