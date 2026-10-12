package io.github.meiyongai.toki.hook

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.*
import org.junit.Test

class DownloadMenuFingerprintTest {
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    private val context = "Landroid/content/Context;"
    private fun method(owner: String = "LX/Renamed;", name: String = "renamed", flags: Int = 1,
        parameters: List<String> = listOf(context, aweme), second: String = "single_song"): ImmutableMethod =
        ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, emptySet(), null) }, "V", flags,
            emptySet(), emptySet(), ImmutableMethodImplementation(4, listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("download_method")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(second)),
                ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList()))

    @Test fun obfuscatedClassAndMethodNamesDoNotChangeTheDownloadContract() {
        assertTrue(DownloadMenuHook.matches(method()))
        assertTrue(DownloadMenuHook.matches(method("LX/CompletelyDifferent;", "q")))
    }

    @Test fun photoAnalyticsHelpersAndUnrelatedActionsRemainUntouched() {
        assertFalse(DownloadMenuHook.matches(method(second = "single_image_mode_download")))
        assertFalse(DownloadMenuHook.matches(method(flags = 9)))
        assertFalse(DownloadMenuHook.matches(method(parameters = listOf(context))))
        assertFalse(DownloadMenuHook.matches(method(parameters = listOf(aweme))))
        assertFalse(DownloadMenuHook.matches(method(name = "<init>")))
    }
}
