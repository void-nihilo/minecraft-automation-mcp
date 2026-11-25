package minecraft.automation.mcp.mixin;

import minecraft.automation.mcp.client.BackgroundInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class BackgroundMouseMixin {
    @Inject(method = {"grabMouse", "m_91601_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void minecraftAutomationDeferMouse(CallbackInfo callback) {
        if (BackgroundInput.deferMouseCapture()) {
            BackgroundInput.logDeferredCapture();
            callback.cancel();
        }
    }

    @Inject(method = {"onPress(JIII)V", "m_91530_(JIII)V"}, at = @At("HEAD"), remap = false)
    private void minecraftAutomationAcceptGameClick(long window, int button, int action, int mods, CallbackInfo callback) {
        Minecraft game = Minecraft.getInstance();
        if (BackgroundInput.deferMouseCapture() && action == GLFW.GLFW_PRESS && window == game.getWindow().getWindow()
                && game.isWindowActive() && game.level != null && game.screen == null && game.getOverlay() == null) {
            BackgroundInput.allowMouseCapture();
        }
    }
}
