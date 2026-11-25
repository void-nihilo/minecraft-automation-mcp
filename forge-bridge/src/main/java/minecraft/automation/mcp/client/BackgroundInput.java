package minecraft.automation.mcp.client;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BackgroundInput {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static boolean startupWindow = true;
    private static boolean waitingForClick;
    private static boolean loggedDeferredCapture;

    private BackgroundInput() {}

    public static boolean isBackgroundLaunchEnabled() {
        return Files.isRegularFile(Path.of("config", "minecraft-automation-mcp-background-start"));
    }

    public static boolean isBackgroundWindowPending() {
        return startupWindow && Platform.isWindows() && isBackgroundLaunchEnabled();
    }

    public static void finishClientConstruction(boolean quickPlaySingleplayer) {
        waitingForClick = isBackgroundLaunchEnabled() && quickPlaySingleplayer;
        startupWindow = waitingForClick;
    }

    public static boolean deferMouseCapture() {
        return waitingForClick;
    }

    public static void logDeferredCapture() {
        if (!loggedDeferredCapture) {
            loggedDeferredCapture = true;
            LOGGER.info("Minecraft Automation MCP deferred startup mouse capture until an in-game click");
        }
    }

    public static void allowMouseCapture() {
        waitingForClick = false;
        startupWindow = false;
        LOGGER.info("Minecraft Automation MCP enabled mouse capture after an in-game click");
    }

    public static boolean maximizeBorderlessWithoutFocus(Window window) {
        if (!Platform.isWindows()) {
            return false;
        }
        Monitor monitor = window.findBestMonitor();
        long monitorHandle = monitor == null ? GLFW.glfwGetPrimaryMonitor() : monitor.getMonitor();
        if (monitorHandle == 0) {
            return false;
        }
        int[] left = new int[1], top = new int[1], width = new int[1], height = new int[1];
        GLFW.glfwGetMonitorWorkarea(monitorHandle, left, top, width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            return false;
        }
        long windowHandle = window.getWindow();
        try {
            GLFW.glfwSetWindowAttrib(windowHandle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            Pointer hwnd = new Pointer(GLFWNativeWin32.glfwGetWin32Window(windowHandle));
            return winFunction(NativeLibrary.getInstance("user32"), "SetWindowPos").invokeInt(new Object[] {
                    hwnd, null, left[0], top[0], width[0], height[0], SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
            }) != 0;
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("Minecraft Automation MCP could not size the background window", failure);
            return false;
        }
    }

    private static Function winFunction(NativeLibrary library, String name) {
        return library.getFunction(name, Function.ALT_CONVENTION);
    }
}
