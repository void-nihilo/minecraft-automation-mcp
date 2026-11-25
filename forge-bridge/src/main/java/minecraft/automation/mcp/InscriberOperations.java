package minecraft.automation.mcp;

import appeng.blockentity.misc.InscriberBlockEntity;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.entity.BlockEntity;

final class InscriberOperations {
    private InscriberOperations() {}

    static JsonObject nativeStatus(BlockEntity entity) {
        if (!(entity instanceof InscriberBlockEntity inscriber)) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("progress", inscriber.getProcessingTime());
        result.addProperty("maxProgress", inscriber.getMaxProcessingTime());
        return result;
    }
}
