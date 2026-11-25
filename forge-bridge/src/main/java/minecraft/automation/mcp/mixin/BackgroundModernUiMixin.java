package minecraft.automation.mcp.mixin;

import minecraft.automation.mcp.client.BackgroundInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "icyllis.modernui.mc.Config$Client$WindowMode", remap = false)
abstract class BackgroundModernUiMixin {
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void minecraftAutomationKeepModernUiInBackground(CallbackInfo callback) {
        if (BackgroundInput.isBackgroundWindowPending()) {
            BackgroundInput.maximizeBorderlessWithoutFocus(Minecraft.getInstance().getWindow());
            callback.cancel();
        }
    }
}
