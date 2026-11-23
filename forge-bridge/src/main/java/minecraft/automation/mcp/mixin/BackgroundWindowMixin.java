package minecraft.automation.mcp.mixin;

import com.mojang.blaze3d.platform.Window;
import minecraft.automation.mcp.client.BackgroundInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Supplier;

@Mixin(Window.class)
abstract class BackgroundWindowMixin {
    @ModifyArg(method = "<init>", index = 2, remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/loading/ImmediateWindowHandler;"
                    + "setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;"
                    + "Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J", remap = false))
    private Supplier<String> minecraftAutomationBackgroundLaunch(Supplier<String> title) {
        if (BackgroundInput.isBackgroundLaunchEnabled()) {
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, GLFW.GLFW_TRUE);
            System.out.println("Minecraft Automation MCP background window hints applied");
        }
        return title;
    }
}
