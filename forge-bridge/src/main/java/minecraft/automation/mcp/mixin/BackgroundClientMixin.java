package minecraft.automation.mcp.mixin;

import minecraft.automation.mcp.client.BackgroundInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class BackgroundClientMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void minecraftAutomationKeepWorldRunning(CallbackInfo callback) {
        if (BackgroundInput.isBackgroundLaunchEnabled()) {
            Minecraft game = (Minecraft) (Object) this;
            game.options.pauseOnLostFocus = false;
            BackgroundInput.useEnglish(game.getWindow().getWindow());
        }
    }
}
