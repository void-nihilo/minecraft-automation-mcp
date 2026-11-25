package minecraft.automation.mcp.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.DisplayData;
import minecraft.automation.mcp.client.BackgroundInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(Window.class)
abstract class BackgroundWindowMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, remap = false)
    private static DisplayData minecraftAutomationStartWindowed(DisplayData display) {
        if (!BackgroundInput.isBackgroundWindowPending() || !display.isFullscreen) {
            return display;
        }
        return new DisplayData(display.width, display.height, display.fullscreenWidth, display.fullscreenHeight, false);
    }

    @ModifyArg(method = "<init>", index = 2, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/loading/ImmediateWindowHandler;"
                    + "setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;"
                    + "Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J", remap = false))
    private Supplier<String> minecraftAutomationBackgroundLaunch(Supplier<String> title) {
        if (BackgroundInput.isBackgroundLaunchEnabled()) {
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            boolean borderless = BackgroundInput.isBackgroundWindowPending();
            GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, borderless ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
            if (borderless) {
                GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            }
            System.out.println("Minecraft Automation MCP background window hints applied");
        }
        return title;
    }

    @Inject(method = {"toggleFullScreen", "m_85438_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void minecraftAutomationDeferFullscreen(CallbackInfo callback) {
        if (BackgroundInput.isBackgroundWindowPending()) {
            callback.cancel();
        }
    }
}
