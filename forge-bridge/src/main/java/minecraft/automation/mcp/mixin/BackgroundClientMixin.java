package minecraft.automation.mcp.mixin;

import minecraft.automation.mcp.client.BackgroundInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class BackgroundClientMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void minecraftAutomationKeepWorldRunning(GameConfig config, CallbackInfo callback) {
        if (BackgroundInput.isBackgroundLaunchEnabled()) {
            Minecraft game = (Minecraft) (Object) this;
            game.options.pauseOnLostFocus = false;
            if (BackgroundInput.isBackgroundWindowPending()) {
                BackgroundInput.maximizeBorderlessWithoutFocus(game.getWindow());
            }
            String world = config.quickPlay.singleplayer();
            BackgroundInput.finishClientConstruction(world != null && !world.isBlank());
        }
    }
}
