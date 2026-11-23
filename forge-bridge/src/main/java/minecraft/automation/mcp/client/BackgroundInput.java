package minecraft.automation.mcp.client;

import com.mojang.logging.LogUtils;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BackgroundInput {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ENGLISH_US = 0x0409;

    private BackgroundInput() {}

    public static boolean isBackgroundLaunchEnabled() {
        return Files.isRegularFile(Path.of("config", "minecraft-automation-mcp-background-start"));
    }

    public static void useEnglish(long window) {
        if (!Platform.isWindows()) {
            return;
        }
        try {
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
            Pointer handle = new Pointer(hwnd);
            NativeLibrary user = NativeLibrary.getInstance("user32");
            Function currentLayout = winFunction(user, "GetKeyboardLayout");
            if (language(currentLayout.invokePointer(new Object[] {0})) != ENGLISH_US) {
                Pointer english = findEnglishLayout(user);
                if (english != null) {
                    winFunction(user, "ActivateKeyboardLayout").invokePointer(new Object[] {english, 0});
                }
            }
            int active = language(currentLayout.invokePointer(new Object[] {0}));
            boolean directEnglish = active == ENGLISH_US || closeIme(handle);
            LOGGER.info("Minecraft Automation MCP background input: layout={}, directEnglish={}", Integer.toHexString(active), directEnglish);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("Minecraft Automation MCP could not configure the game window's English input", failure);
        }
    }

    private static Pointer findEnglishLayout(NativeLibrary user) {
        Function list = winFunction(user, "GetKeyboardLayoutList");
        int count = list.invokeInt(new Object[] {0, null});
        if (count <= 0) {
            return null;
        }
        try (Memory layouts = new Memory((long) count * Native.POINTER_SIZE)) {
            int copied = list.invokeInt(new Object[] {count, layouts});
            for (int i = 0; i < copied; i++) {
                Pointer layout = layouts.getPointer((long) i * Native.POINTER_SIZE);
                if (language(layout) == ENGLISH_US) {
                    return layout;
                }
            }
        }
        return null;
    }

    private static boolean closeIme(Pointer window) {
        NativeLibrary imm = NativeLibrary.getInstance("imm32");
        Pointer context = winFunction(imm, "ImmGetContext").invokePointer(new Object[] {window});
        if (context == null) {
            return false;
        }
        try {
            winFunction(imm, "ImmSetOpenStatus").invokeInt(new Object[] {context, 0});
            return winFunction(imm, "ImmGetOpenStatus").invokeInt(new Object[] {context}) == 0;
        } finally {
            winFunction(imm, "ImmReleaseContext").invokeInt(new Object[] {window, context});
        }
    }

    private static int language(Pointer layout) {
        return (int) (Pointer.nativeValue(layout) & 0xffff);
    }

    private static Function winFunction(NativeLibrary library, String name) {
        return library.getFunction(name, Function.ALT_CONVENTION);
    }
}
