package minecraft.automation.mcp;

import com.google.gson.JsonObject;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

final class VanillaFurnaceOperations {
    private VanillaFurnaceOperations() {}

    static JsonObject nativeStatus(BlockEntity entity) {
        if (!(entity instanceof AbstractFurnaceBlockEntity furnace)
                || !furnace.getBlockState().hasProperty(AbstractFurnaceBlock.LIT)) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("lit", furnace.getBlockState().getValue(AbstractFurnaceBlock.LIT));
        return result;
    }
}
